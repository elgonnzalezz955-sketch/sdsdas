package com.donutmarket.ui;

import com.donutmarket.MarketConfig;
import com.donutmarket.MarketTracker;
import com.donutmarket.PriceStore;
import com.donutmarket.PriceStore.Sample;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class MarketScreen extends Screen {
    // ---- palette (dark trading-terminal look) ----
    private static final int BG = 0xFF0A0D13, PANEL = 0xFF111621, PANEL2 = 0xFF0D121B, BORDER = 0xFF222B3C;
    private static final int TEXT = 0xFFE8ECF4, MUTED = 0xFF7C879B, GREEN = 0xFF16C784, RED = 0xFFEA3943;
    private static final int ACCENT = 0xFF3B82F6, GRID = 0xFF1A2232, HOVER = 0xFF1A2231;

    private enum Range {
        H1("1H", 3_600_000L, 300_000L),
        H24("24H", 86_400_000L, 3_600_000L),
        D7("7D", 604_800_000L, 21_600_000L),
        D30("30D", 2_592_000_000L, 86_400_000L);
        final String label; final long ms; final long candleMs;
        Range(String l, long ms, long c) { label = l; this.ms = ms; candleMs = c; }
    }

    private record Hit(int x, int y, int w, int h, Runnable action) {
        boolean in(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }
    }
    private record ItemEntry(String id, Item item, String name, String lower) {}

    private static List<ItemEntry> ALL;
    private static Map<String, ItemEntry> BY_ID;

    private static void buildIndex() {
        if (ALL != null) return;
        List<ItemEntry> l = new ArrayList<>();
        Map<String, ItemEntry> m = new HashMap<>();
        for (Item it : Registries.ITEM) {
            if (it == Items.AIR) continue;
            Identifier id = Registries.ITEM.getId(it);
            String n = it.getName().getString();
            ItemEntry e = new ItemEntry(id.toString(), it, n, (n + " " + id.getPath().replace('_', ' ')).toLowerCase(Locale.ROOT));
            l.add(e); m.put(e.id, e);
        }
        ALL = l; BY_ID = m;
    }

    private TextFieldWidget search;
    private String selected;
    private Range range = Range.H24;
    private boolean candles = true;
    private float anim = 0f;
    private long lastNanos = System.nanoTime();
    private double shownPrice = Double.NaN;
    private int listScroll = 0;
    private final List<String> results = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final Map<String, List<Sample>> histCache = new HashMap<>();
    private long histCacheTime = 0;
    private List<String> tipLines = null;

    private int lx, ly, lw, lh, rx, ry, rw, rh;

    public MarketScreen() { super(Text.literal("DonutSMP Market Tracker")); buildIndex(); }

    // ------------------------------------------------------------------ setup
    @Override
    protected void init() {
        int pad = 10;
        lw = Math.min(190, Math.max(150, width / 3));
        lx = pad; ly = pad; lh = height - 2 * pad;
        rx = lx + lw + pad; ry = pad; rw = width - rx - pad; rh = lh;

        search = new TextFieldWidget(textRenderer, lx + 6, ly + 22, lw - 12, 16, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search any item...").formatted(Formatting.DARK_GRAY));
        search.setMaxLength(40);
        search.setChangedListener(s -> { refreshResults(); listScroll = 0; });
        addDrawableChild(search);
        setInitialFocus(search);

        if (selected == null) {
            List<String> w = MarketConfig.get().watchlist();
            if (!w.isEmpty()) select(w.get(0));
        }
    }

    @Override public boolean shouldPause() { return false; }
    @Override public void renderBackground(DrawContext c, int mx, int my, float d) { /* custom background */ }

    private void refreshResults() {
        results.clear();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return;
        List<ItemEntry> m = new ArrayList<>();
        for (ItemEntry e : ALL) if (e.lower.contains(q)) m.add(e);
        m.sort(Comparator.comparingInt((ItemEntry e) -> e.name.toLowerCase(Locale.ROOT).startsWith(q) ? 0 : 1)
                .thenComparingInt(e -> e.name.length()));
        for (int i = 0; i < Math.min(m.size(), 200); i++) results.add(m.get(i).id);
    }

    private void select(String id) {
        selected = id; anim = 0f; shownPrice = Double.NaN;
        MarketTracker.get().requestNow(id);
    }

    private void setRange(Range r) { range = r; anim = 0f; }
    private void setCandles(boolean c) { candles = c; anim = 0f; }

    private List<Sample> hist(String id) {
        long now = System.currentTimeMillis();
        if (now - histCacheTime > 1000) { histCache.clear(); histCacheTime = now; }
        return histCache.computeIfAbsent(id, k -> PriceStore.get().history(k));
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (h.in(mx, my)) { h.action.run(); return true; }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (mx >= lx && mx < lx + lw && my >= ly + 56) {
            listScroll -= (int) Math.signum(vAmount) * 2;
            return true;
        }
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && search.isFocused() && !results.isEmpty()) {
            select(results.get(0));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ render
    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        long n = System.nanoTime();
        float dt = Math.min(0.1f, (n - lastNanos) / 1e9f);
        lastNanos = n;
        anim = Math.min(1f, anim + dt / 0.9f);
        hits.clear();
        tipLines = null;

        c.fill(0, 0, width, height, BG);
        drawLeft(c, mx, my);
        drawRight(c, mx, my, dt);
        super.render(c, mx, my, delta);   // search box
        if (tipLines != null) drawTip(c, mx, my);
    }

    private void panel(DrawContext c, int x, int y, int w, int h) {
        c.fill(x, y, x + w, y + h, BORDER);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL);
    }

    private void txt(DrawContext c, String s, int x, int y, int color) { c.drawText(textRenderer, s, x, y, color, true); }
    private void txtRight(DrawContext c, String s, int xRight, int y, int color) { txt(c, s, xRight - textRenderer.getWidth(s), y, color); }

    private void bigText(DrawContext c, String s, int x, int y, int color, float scale) {
        MatrixStack m = c.getMatrices();
        m.push(); m.translate(x, y, 0); m.scale(scale, scale, 1f);
        c.drawText(textRenderer, s, 0, 0, color, true);
        m.pop();
    }

    private void drawItemScaled(DrawContext c, Item item, int x, int y, float scale) {
        MatrixStack m = c.getMatrices();
        m.push(); m.translate(x, y, 0); m.scale(scale, scale, 1f);
        c.drawItem(new ItemStack(item), 0, 0);
        m.pop();
    }

    private void triangle(DrawContext c, int cx, int y, boolean up, int color) {
        for (int i = 0; i < 5; i++) {
            int wd = up ? 2 * i + 1 : 9 - 2 * i;
            c.fill(cx - wd / 2, y + i, cx - wd / 2 + wd, y + i + 1, color);
        }
    }

    private boolean button(DrawContext c, int x, int y, int w, int h, String label, boolean active, int mx, int my, Runnable r) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        c.fill(x, y, x + w, y + h, active ? ACCENT : BORDER);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, active ? 0xFF1F3F7A : (hov ? HOVER : PANEL2));
        txt(c, label, x + (w - textRenderer.getWidth(label)) / 2, y + (h - 8) / 2 + 1, active ? TEXT : (hov ? TEXT : MUTED));
        hits.add(new Hit(x, y, w, h, r));
        return hov;
    }

    // ---------- left: search + watchlist
    private void drawLeft(DrawContext c, int mx, int my) {
        panel(c, lx, ly, lw, lh);
        txt(c, "DONUT MARKET", lx + 8, ly + 8, ACCENT);
        boolean searching = !search.getText().trim().isEmpty();
        List<String> ids = searching ? results : MarketConfig.get().watchlist();
        txt(c, searching ? "RESULTS (" + ids.size() + ")" : "WATCHLIST", lx + 8, ly + 44, MUTED);

        int top = ly + 56, bottom = ly + lh - 8, rowH = 24;
        int visible = Math.max(1, (bottom - top) / rowH);
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, ids.size() - visible)));

        if (ids.isEmpty()) {
            txt(c, searching ? "No items match." : "Watchlist is empty.", lx + 8, top + 4, MUTED);
            if (!searching) txt(c, "Search and click Watch.", lx + 8, top + 16, MUTED);
        }

        c.enableScissor(lx + 1, top, lx + lw - 1, bottom);
        for (int i = 0; i < visible + 1 && i + listScroll < ids.size(); i++) {
            String id = ids.get(i + listScroll);
            ItemEntry e = BY_ID.get(id);
            if (e == null) continue;
            int y = top + i * rowH;
            boolean sel = id.equals(selected);
            boolean hov = mx >= lx + 4 && mx < lx + lw - 4 && my >= y && my < y + rowH && my < bottom;
            if (sel) { c.fill(lx + 4, y, lx + lw - 4, y + rowH - 1, 0xFF16233B); c.fill(lx + 4, y, lx + 6, y + rowH - 1, ACCENT); }
            else if (hov) c.fill(lx + 4, y, lx + lw - 4, y + rowH - 1, HOVER);
            c.drawItem(new ItemStack(e.item), lx + 10, y + 3);
            txt(c, textRenderer.trimToWidth(e.name, lw - 44), lx + 30, y + 3, TEXT);

            List<Sample> h = hist(id);
            if (h.isEmpty()) txt(c, "no data yet", lx + 30, y + 13, MUTED);
            else {
                double cur = h.get(h.size() - 1).p;
                double base = baseAt(h, System.currentTimeMillis() - Range.H24.ms);
                int col = TEXT; String pct = "";
                if (!Double.isNaN(base) && base != 0) {
                    double ch = (cur - base) / base * 100;
                    col = ch >= 0 ? GREEN : RED;
                    pct = String.format(Locale.ROOT, "%+.1f%%", ch);
                }
                txt(c, money(cur), lx + 30, y + 13, col);
                if (!pct.isEmpty()) txtRight(c, pct, lx + lw - 10, y + 13, col);
            }
            if (my < bottom) hits.add(new Hit(lx + 4, y, lw - 8, rowH, () -> select(id)));
        }
        c.disableScissor();
    }

    private static double baseAt(List<Sample> h, long since) {
        for (Sample s : h) if (s.t >= since) return s.p;
        return Double.NaN;
    }

    // ---------- right: dashboard
    private void drawRight(DrawContext c, int mx, int my, float dt) {
        panel(c, rx, ry, rw, rh);
        if (selected == null || BY_ID.get(selected) == null) {
            String s = "Search an item to open its market chart";
            txt(c, s, rx + (rw - textRenderer.getWidth(s)) / 2, ry + rh / 2 - 4, MUTED);
            drawFooter(c);
            return;
        }
        ItemEntry e = BY_ID.get(selected);
        long now = System.currentTimeMillis();
        long start = now - range.ms;
        List<Sample> hist = hist(selected);
        List<Sample> win = new ArrayList<>();
        for (Sample s : hist) if (s.t >= start) win.add(s);

        int ix = rx + 12, iy = ry + 12, innerW = rw - 24;

        // header
        drawItemScaled(c, e.item, ix, iy, 2f);
        bigText(c, e.name, ix + 40, iy + 3, TEXT, 1.2f);
        txt(c, e.id, ix + 40, iy + 19, MUTED);
        boolean watched = MarketConfig.get().isWatched(selected);
        String id = selected;
        button(c, rx + rw - 12 - 84, iy + 4, 84, 18, watched ? "Watching" : "+ Watch", watched, mx, my,
                () -> MarketConfig.get().toggleWatch(id));

        // price + change
        double cur = hist.isEmpty() ? Double.NaN : hist.get(hist.size() - 1).p;
        if (!Double.isNaN(cur)) {
            if (Double.isNaN(shownPrice)) shownPrice = cur;
            shownPrice += (cur - shownPrice) * Math.min(1.0, dt * 8.0);
        }
        int y2 = iy + 44;
        String priceStr = Double.isNaN(cur) ? "--" : money(shownPrice);
        bigText(c, priceStr, ix, y2, TEXT, 2f);
        int priceW = textRenderer.getWidth(priceStr) * 2;

        boolean up = true;
        double first = win.size() >= 2 ? win.get(0).p : Double.NaN;
        if (!Double.isNaN(first) && !Double.isNaN(cur)) {
            double ch = cur - first, pct = first != 0 ? ch / first * 100 : 0;
            up = ch >= 0;
            int col = up ? GREEN : RED;
            int bx = ix + priceW + 14;
            triangle(c, bx + 4, y2 + 5, up, col);
            txt(c, (up ? "+" : "-") + money(Math.abs(ch)) + "  (" + String.format(Locale.ROOT, "%+.2f%%", pct) + ")", bx + 14, y2 + 2, col);
            txt(c, "past " + range.label, bx + 14, y2 + 13, MUTED);
        } else {
            txt(c, win.isEmpty() ? "Collecting price data..." : "Need at least 2 samples", ix + priceW + 14, y2 + 6, MUTED);
        }

        // stat boxes
        int y3 = y2 + 28;
        double hi = Double.NaN, lo = Double.NaN;
        for (Sample s : win) { hi = Double.isNaN(hi) ? s.p : Math.max(hi, s.p); lo = Double.isNaN(lo) ? s.p : Math.min(lo, s.p); }
        double prev = hist.size() >= 2 ? hist.get(hist.size() - 2).p : Double.NaN;
        String[] labels = {"OPEN", "HIGH", "LOW", "PREVIOUS"};
        double[] vals = {win.isEmpty() ? Double.NaN : win.get(0).p, hi, lo, prev};
        int gap = 8, bw = (innerW - 3 * gap) / 4;
        for (int i = 0; i < 4; i++) {
            int bx = ix + i * (bw + gap);
            c.fill(bx, y3, bx + bw, y3 + 28, BORDER);
            c.fill(bx + 1, y3 + 1, bx + bw - 1, y3 + 27, PANEL2);
            txt(c, labels[i], bx + 6, y3 + 4, MUTED);
            txt(c, Double.isNaN(vals[i]) ? "--" : money(vals[i]), bx + 6, y3 + 15, i == 1 ? GREEN : i == 2 ? RED : TEXT);
        }

        // controls
        int y4 = y3 + 36;
        Range[] rs = Range.values();
        for (int i = 0; i < rs.length; i++) {
            Range r = rs[i];
            button(c, ix + i * 38, y4, 34, 16, r.label, r == range, mx, my, () -> setRange(r));
        }
        button(c, ix + innerW - 56, y4, 56, 16, "Line", !candles, mx, my, () -> setCandles(false));
        button(c, ix + innerW - 56 - 4 - 62, y4, 62, 16, "Candles", candles, mx, my, () -> setCandles(true));

        // chart + recent column
        int y5 = y4 + 22, bottom = ry + rh - 22;
        boolean showRecent = rw >= 470;
        int recentW = showRecent ? 104 : 0;
        int cw = innerW - (showRecent ? recentW + 8 : 0), ch = bottom - y5;
        if (ch >= 50) {
            c.fill(ix, y5, ix + cw, y5 + ch, BORDER);
            c.fill(ix + 1, y5 + 1, ix + cw - 1, y5 + ch - 1, PANEL2);
            drawChart(c, ix + 1, y5 + 1, cw - 2, ch - 2, win, start, now, up, mx, my);
            if (showRecent) drawRecent(c, ix + cw + 8, y5, recentW, ch, hist, now);
        }
        drawFooter(c);
    }

    private void drawFooter(DrawContext c) {
        txt(c, textRenderer.trimToWidth(MarketTracker.get().statusLine(), rw - 24), rx + 12, ry + rh - 15, MUTED);
    }

    private void drawRecent(DrawContext c, int x, int y, int w, int h, List<Sample> hist, long now) {
        c.fill(x, y, x + w, y + h, BORDER);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL2);
        txt(c, "RECENT PRICES", x + 6, y + 5, MUTED);
        int rows = Math.max(0, (h - 22) / 11);
        for (int i = 0; i < rows && i < hist.size(); i++) {
            int idx = hist.size() - 1 - i;
            Sample s = hist.get(idx);
            int col = TEXT;
            if (idx > 0) { double pp = hist.get(idx - 1).p; col = s.p > pp ? GREEN : s.p < pp ? RED : TEXT; }
            int yy = y + 18 + i * 11;
            txt(c, ago(now - s.t), x + 6, yy, MUTED);
            txtRight(c, money(s.p), x + w - 6, yy, col);
        }
    }

    // ---------- chart
    private static float ease(float t) { t = Math.max(0f, Math.min(1f, t)); float u = 1f - t; return 1f - u * u * u; }

    private void drawChart(DrawContext c, int x, int y, int w, int h, List<Sample> win, long start, long now,
                           boolean up, int mx, int my) {
        int gutter = 56;
        int px = x + gutter, py = y + 8, pw = w - gutter - 8, ph = h - 8 - 18;
        if (pw < 20 || ph < 20) return;

        if (win.isEmpty()) {
            String a = "No samples in this window yet.";
            String b = "The mod records prices while the game is open.";
            txt(c, a, x + (w - textRenderer.getWidth(a)) / 2, y + h / 2 - 10, MUTED);
            txt(c, b, x + (w - textRenderer.getWidth(b)) / 2, y + h / 2 + 2, MUTED);
            return;
        }

        double max = -Double.MAX_VALUE, min = Double.MAX_VALUE;
        for (Sample s : win) { max = Math.max(max, s.p); min = Math.min(min, s.p); }
        double padv = (max - min) * 0.12;
        if (padv <= 0) padv = Math.max(Math.abs(max) * 0.02, 0.01);
        max += padv; min -= padv;
        final double fmin = min, fmax = max;
        int baseY = py + ph;

        // grid + y labels
        for (int i = 0; i <= 4; i++) {
            int yy = py + ph - (int) (ph * (i / 4.0));
            c.fill(px, yy, px + pw, yy + 1, GRID);
            txtRight(c, money(min + (max - min) * i / 4.0), px - 5, yy - 4, MUTED);
        }
        // x labels
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern(range.ms <= Range.H24.ms ? "HH:mm" : "MMM d").withZone(ZoneId.systemDefault());
        for (int i = 0; i <= 4; i++) {
            float f = i / 4f;
            String lab = fmt.format(Instant.ofEpochMilli(start + (long) (f * range.ms)));
            int lx0 = (int) (px + f * pw - textRenderer.getWidth(lab) / 2f);
            lx0 = Math.max(x + 2, Math.min(lx0, x + w - textRenderer.getWidth(lab) - 2));
            txt(c, lab, lx0, baseY + 6, MUTED);
        }

        int color = up ? GREEN : RED;
        float e = ease(anim);
        float xLim = px + e * pw;
        double cur = win.get(win.size() - 1).p;
        java.util.function.DoubleToIntFunction yOf = v -> py + ph - (int) ((v - fmin) / (fmax - fmin) * ph);

        if (!candles) {
            int n = win.size() + 1;
            float[] xs = new float[n], ys = new float[n];
            for (int i = 0; i < win.size(); i++) {
                Sample s = win.get(i);
                xs[i] = px + (s.t - start) / (float) range.ms * pw;
                ys[i] = yOf.applyAsInt(s.p);
            }
            xs[n - 1] = px + pw; ys[n - 1] = yOf.applyAsInt(cur);
            if (xs[n - 1] < xs[n - 2]) xs[n - 1] = xs[n - 2];
            drawLine(c, xs, ys, n, baseY, color, xLim);
        } else {
            int slots = (int) Math.ceil(range.ms / (double) range.candleMs);
            double[] o = new double[slots], hh = new double[slots], ll = new double[slots], cl = new double[slots];
            Arrays.fill(o, Double.NaN);
            for (Sample s : win) {
                int i = Math.max(0, Math.min(slots - 1, (int) ((s.t - start) / range.candleMs)));
                if (Double.isNaN(o[i])) { o[i] = s.p; hh[i] = s.p; ll[i] = s.p; }
                else { hh[i] = Math.max(hh[i], s.p); ll[i] = Math.min(ll[i], s.p); }
                cl[i] = s.p;
            }
            double prevClose = Double.NaN;
            for (int i = 0; i < slots; i++) {
                if (Double.isNaN(o[i])) continue;
                if (!Double.isNaN(prevClose)) { o[i] = prevClose; hh[i] = Math.max(hh[i], o[i]); ll[i] = Math.min(ll[i], o[i]); }
                prevClose = cl[i];
            }
            float sw = pw / (float) slots;
            int cwid = Math.max(1, (int) (sw * 0.66f));
            int mid = py + ph / 2;
            for (int i = 0; i < slots; i++) {
                if (Double.isNaN(o[i])) continue;
                float ee = ease(anim * 1.5f - (i / (float) slots) * 0.5f);
                int cx = (int) (px + (i + 0.5f) * sw);
                int yH = lerp(mid, yOf.applyAsInt(hh[i]), ee), yL = lerp(mid, yOf.applyAsInt(ll[i]), ee);
                int yO = lerp(mid, yOf.applyAsInt(o[i]), ee), yC = lerp(mid, yOf.applyAsInt(cl[i]), ee);
                int col = cl[i] >= o[i] ? GREEN : RED;
                c.fill(cx, Math.min(yH, yL), cx + 1, Math.max(yH, yL) + 1, col);
                int top = Math.min(yO, yC), bot = Math.max(Math.max(yO, yC), top + 1);
                c.fill(cx - cwid / 2, top, cx - cwid / 2 + cwid, bot + 1, col);
            }
        }

        // current price line + tag
        int yc = yOf.applyAsInt(cur);
        if (e > 0.95f) {
            for (int xx = px; xx < px + pw; xx += 6) c.fill(xx, yc, Math.min(xx + 3, px + pw), yc + 1, (color & 0x00FFFFFF) | 0x88000000);
            String tag = money(cur);
            int tw = textRenderer.getWidth(tag) + 6;
            c.fill(px - tw - 2, yc - 6, px - 2, yc + 6, color);
            txt(c, tag, px - tw + 1, yc - 4, 0xFF06100C);
        }

        // hover crosshair + tooltip
        if (mx >= px && mx < px + pw && my >= py && my < py + ph) {
            DateTimeFormatter tf = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
            if (!candles) {
                Sample best = null; float bd = Float.MAX_VALUE;
                for (Sample s : win) {
                    float sx = px + (s.t - start) / (float) range.ms * pw;
                    if (Math.abs(sx - mx) < bd) { bd = Math.abs(sx - mx); best = s; }
                }
                if (best != null) {
                    int sx = (int) (px + (best.t - start) / (float) range.ms * pw);
                    c.fill(sx, py, sx + 1, py + ph, 0x55FFFFFF);
                    int sy = yOf.applyAsInt(best.p);
                    c.fill(sx - 2, sy - 2, sx + 3, sy + 3, color);
                    tipLines = List.of(money(best.p), tf.format(Instant.ofEpochMilli(best.t)));
                }
            } else {
                int slots = (int) Math.ceil(range.ms / (double) range.candleMs);
                int idx = Math.max(0, Math.min(slots - 1, (int) ((mx - px) / (pw / (float) slots))));
                double o = Double.NaN, hi = 0, lo = 0, cl = 0; long t0 = start + idx * range.candleMs;
                for (Sample s : win) {
                    int i = Math.max(0, Math.min(slots - 1, (int) ((s.t - start) / range.candleMs)));
                    if (i != idx) continue;
                    if (Double.isNaN(o)) { o = s.p; hi = s.p; lo = s.p; } else { hi = Math.max(hi, s.p); lo = Math.min(lo, s.p); }
                    cl = s.p;
                }
                if (!Double.isNaN(o)) {
                    int cx = (int) (px + (idx + 0.5f) * (pw / (float) slots));
                    c.fill(cx, py, cx + 1, py + ph, 0x33FFFFFF);
                    tipLines = List.of(tf.format(Instant.ofEpochMilli(t0)), "O " + money(o), "H " + money(hi), "L " + money(lo), "C " + money(cl));
                }
            }
        }
    }

    private static int lerp(int a, int b, float t) { return (int) (a + (b - a) * t); }

    private void drawLine(DrawContext c, float[] xs, float[] ys, int n, int baseY, int color, float xLim) {
        int area = (color & 0x00FFFFFF) | 0x28000000;
        if (n == 1) { c.fill((int) xs[0] - 1, (int) ys[0] - 1, (int) xs[0] + 2, (int) ys[0] + 2, color); return; }
        for (int i = 0; i < n - 1; i++) {
            float x0 = xs[i], y0 = ys[i], x1 = xs[i + 1], y1 = ys[i + 1], dx = x1 - x0;
            if (dx < 1f) {
                int xx = (int) x0;
                if (xx <= xLim) c.fill(xx, (int) Math.min(y0, y1), xx + 1, (int) Math.max(y0, y1) + 2, color);
                continue;
            }
            for (int xx = (int) Math.floor(x0); xx < (int) Math.floor(x1); xx++) {
                if (xx > xLim) return;
                float t0 = Math.max(0f, Math.min(1f, (xx - x0) / dx));
                float t1 = Math.max(0f, Math.min(1f, (xx + 1 - x0) / dx));
                float ya = y0 + (y1 - y0) * t0, yb = y0 + (y1 - y0) * t1;
                c.fill(xx, (int) Math.min(ya, yb) + 2, xx + 1, baseY, area);
                c.fill(xx, (int) Math.min(ya, yb), xx + 1, (int) Math.max(ya, yb) + 2, color);
            }
        }
    }

    private void drawTip(DrawContext c, int mx, int my) {
        int w = 0;
        for (String s : tipLines) w = Math.max(w, textRenderer.getWidth(s));
        int h = tipLines.size() * 11 + 6;
        int x = mx + 12, y = my - h - 4;
        if (x + w + 10 > width) x = mx - w - 18;
        if (y < 2) y = my + 12;
        c.getMatrices().push();
        c.getMatrices().translate(0, 0, 400);
        c.fill(x, y, x + w + 10, y + h, ACCENT);
        c.fill(x + 1, y + 1, x + w + 9, y + h - 1, 0xFF0B1019);
        for (int i = 0; i < tipLines.size(); i++) txt(c, tipLines.get(i), x + 5, y + 4 + i * 11, i == 0 ? TEXT : MUTED);
        c.getMatrices().pop();
    }

    // ------------------------------------------------------------------ formatting
    private static String money(double v) {
        double a = Math.abs(v);
        String s;
        if (a >= 1e9) s = String.format(Locale.ROOT, "%.2fB", v / 1e9);
        else if (a >= 1e6) s = String.format(Locale.ROOT, "%.2fM", v / 1e6);
        else if (a >= 1e3) s = String.format(Locale.ROOT, "%.2fK", v / 1e3);
        else if (a >= 10) s = String.format(Locale.ROOT, "%.1f", v);
        else s = String.format(Locale.ROOT, "%.2f", v);
        return "$" + s;
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s ago";
        if (s < 3600) return (s / 60) + "m ago";
        if (s < 86400) return (s / 3600) + "h ago";
        return (s / 86400) + "d ago";
    }
}
