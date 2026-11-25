package com.donutmarket;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Persistent price history. Saved to .minecraft/config/donutmarket/prices.json */
public class PriceStore {
    public static final class Sample {
        public long t;
        public double p;
        public Sample(long t, double p) { this.t = t; this.p = p; }
    }

    private static final PriceStore I = new PriceStore();
    public static PriceStore get() { return I; }

    private static final long DAY = 86_400_000L;
    private final Gson gson = new Gson();
    private final Map<String, List<Sample>> data = new HashMap<>();

    private Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutmarket").resolve("prices.json");
    }

    public synchronized void add(String id, double price, long now) {
        List<Sample> l = data.computeIfAbsent(id, k -> new ArrayList<>());
        if (!l.isEmpty()) {
            Sample last = l.get(l.size() - 1);
            if (now - last.t < 20_000) { last.t = now; last.p = price; return; }
        }
        l.add(new Sample(now, price));
    }

    public synchronized List<Sample> history(String id) {
        List<Sample> l = data.get(id);
        if (l == null) return List.of();
        List<Sample> copy = new ArrayList<>(l.size());
        for (Sample s : l) copy.add(new Sample(s.t, s.p));
        return copy;
    }

    public synchronized long lastTime(String id) {
        List<Sample> l = data.get(id);
        return (l == null || l.isEmpty()) ? 0L : l.get(l.size() - 1).t;
    }

    public synchronized int itemCount() { return data.size(); }

    /** Keeps 30 days: full detail for 2 days, 15-min buckets to 7 days, hourly after that. */
    public synchronized void compact(long now) {
        for (Map.Entry<String, List<Sample>> e : data.entrySet()) {
            List<Sample> out = new ArrayList<>();
            long lastBucket = Long.MIN_VALUE, lastGran = -1;
            for (Sample s : e.getValue()) {
                long age = now - s.t;
                if (age > 30 * DAY) continue;
                long gran = age > 7 * DAY ? 3_600_000L : age > 2 * DAY ? 900_000L : 0L;
                if (gran == 0) { out.add(s); lastGran = 0; continue; }
                long bucket = s.t / gran;
                if (!out.isEmpty() && gran == lastGran && bucket == lastBucket) out.set(out.size() - 1, s);
                else out.add(s);
                lastBucket = bucket; lastGran = gran;
            }
            e.setValue(out);
        }
    }

    public synchronized void load() {
        try {
            Path p = path();
            if (!Files.exists(p)) return;
            Map<String, List<Sample>> m = gson.fromJson(Files.readString(p),
                    new TypeToken<Map<String, List<Sample>>>() {}.getType());
            if (m != null) { data.clear(); data.putAll(m); }
            compact(System.currentTimeMillis());
        } catch (Exception ex) {
            System.err.println("[DonutMarket] Could not read price history: " + ex);
        }
    }

    public synchronized void save() {
        try {
            compact(System.currentTimeMillis());
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("prices.json.tmp");
            Files.writeString(tmp, gson.toJson(data));
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            System.err.println("[DonutMarket] Could not save price history: " + ex);
        }
    }
}
