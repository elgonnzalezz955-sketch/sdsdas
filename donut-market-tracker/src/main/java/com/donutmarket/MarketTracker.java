package com.donutmarket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Background poller. Asks the official DonutSMP API for the auction listings of each tracked item
 * and records the "market price" (median of the 3 cheapest unit prices) into the PriceStore.
 * Uses ~1 request per second at most, well under the 250/min API limit.
 */
public final class MarketTracker {
    private static final MarketTracker I = new MarketTracker();
    public static MarketTracker get() { return I; }

    private static final String BASE = "https://api.donutsmp.net/v1/auction/list/";

    private final PriceStore store = PriceStore.get();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "DonutMarket-Poller");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> extra = ConcurrentHashMap.newKeySet();
    private final Queue<String> urgent = new ConcurrentLinkedQueue<>();
    private final Map<String, Long> attempts = new ConcurrentHashMap<>();
    private volatile String status = "Starting...";
    private volatile long lastOk = 0;
    private volatile boolean useGet = true;
    private boolean started = false;
    private int cursor = 0;
    private long lastSave = System.currentTimeMillis();

    public synchronized void start() {
        if (started) return;
        started = true;
        ex.scheduleWithFixedDelay(this::tick, 3, 1, TimeUnit.SECONDS);
    }

    public void stop() { store.save(); }

    /** Called when the user opens an item: fetch it right away and keep tracking it. */
    public void requestNow(String id) {
        extra.add(id);
        long last = attempts.getOrDefault(id, 0L);
        if (System.currentTimeMillis() - last > 15_000 && !urgent.contains(id)) urgent.add(id);
    }

    public List<String> tracked() {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        MarketConfig c = MarketConfig.get();
        s.addAll(c.watchlist());
        s.addAll(extra);
        s.addAll(c.autoTrack());
        return new ArrayList<>(s);
    }

    public String statusLine() {
        long ago = lastOk == 0 ? -1 : (System.currentTimeMillis() - lastOk) / 1000;
        return status + "  |  tracking " + tracked().size() + " items"
                + (ago >= 0 ? "  |  last update " + ago + "s ago" : "");
    }

    private void tick() {
        try {
            MarketConfig cfg = MarketConfig.get();
            long now = System.currentTimeMillis();
            if (now - lastSave > 120_000) { store.save(); lastSave = now; }

            if (cfg.apiKey().isBlank()) {
                status = "No API key - run /api on DonutSMP, then /dmarket key <token>";
                return;
            }
            String id = urgent.poll();
            if (id == null) {
                List<String> ids = tracked();
                long pollMs = cfg.pollSeconds() * 1000L;
                for (int i = 0; i < ids.size(); i++) {
                    String c = ids.get(Math.floorMod(cursor++, ids.size()));
                    long last = Math.max(store.lastTime(c), attempts.getOrDefault(c, 0L));
                    if (now - last >= pollMs) { id = c; break; }
                }
            }
            if (id == null) { if (!status.startsWith("API")) status = "Up to date"; return; }

            attempts.put(id, now);
            OptionalDouble p = fetchPrice(cfg, id);
            if (p.isPresent()) {
                store.add(id, p.getAsDouble(), System.currentTimeMillis());
                lastOk = System.currentTimeMillis();
                status = "Live";
            }
        } catch (Throwable t) {
            status = "Error: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
        }
    }

    private static String searchTerm(String id) {
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        return path.replace('_', ' ');
    }

    private OptionalDouble fetchPrice(MarketConfig cfg, String id) throws Exception {
        String term = searchTerm(id);
        List<Double> unit = new ArrayList<>();
        for (int page = 1; page <= 2; page++) {
            JsonObject root = request(cfg, page, term);
            if (root == null) return OptionalDouble.empty();
            JsonElement res = root.get("result");
            if (res == null || !res.isJsonArray()) break;
            JsonArray arr = res.getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                JsonObject item = o.has("item") && o.get("item").isJsonObject() ? o.getAsJsonObject("item") : null;
                if (item == null || !item.has("id") || !o.has("price")) continue;
                String itemId = item.get("id").getAsString();
                if (!itemId.contains(":")) itemId = "minecraft:" + itemId;
                if (!itemId.equals(id)) continue;
                int count = item.has("count") ? Math.max(1, item.get("count").getAsInt()) : 1;
                unit.add(o.get("price").getAsDouble() / count);
            }
            if (unit.size() >= 3 || arr.isEmpty()) break;
        }
        if (unit.isEmpty()) return OptionalDouble.empty();
        Collections.sort(unit);
        int n = Math.min(3, unit.size());
        return OptionalDouble.of(unit.get(n / 2));
    }

    private JsonObject request(MarketConfig cfg, int page, String term) throws Exception {
        HttpResponse<String> r = send(cfg, page, term, useGet);
        if (r.statusCode() == 400 || r.statusCode() == 404 || r.statusCode() == 405 || r.statusCode() == 415) {
            // Not sure which style the API wants for search params - try the other one and remember it.
            HttpResponse<String> r2 = send(cfg, page, term, !useGet);
            if (r2.statusCode() == 200) { useGet = !useGet; r = r2; }
        }
        int sc = r.statusCode();
        if (sc == 401 || sc == 403) { status = "API key rejected - run /api again and /dmarket key <token>"; return null; }
        if (sc == 429) { status = "API rate limited - slowing down"; Thread.sleep(5000); return null; }
        if (sc != 200) { status = "API error " + sc; return null; }
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private HttpResponse<String> send(MarketConfig cfg, int page, String term, boolean get) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Accept", "application/json");
        if (get) {
            String q = "?search=" + URLEncoder.encode(term, StandardCharsets.UTF_8)
                    + "&sort=" + URLEncoder.encode(cfg.sortParam(), StandardCharsets.UTF_8);
            b.uri(URI.create(BASE + page + q)).GET();
        } else {
            JsonObject body = new JsonObject();
            body.addProperty("search", term);
            body.addProperty("sort", cfg.sortParam());
            b.uri(URI.create(BASE + page)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
