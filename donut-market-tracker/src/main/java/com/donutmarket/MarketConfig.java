package com.donutmarket;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saved to .minecraft/config/donutmarket/config.json */
public class MarketConfig {
    private String apiKey = "";
    private int pollSeconds = 60;
    private String sortParam = "lowest_price";
    private List<String> watchlist = new ArrayList<>(List.of("minecraft:diamond", "minecraft:netherite_ingot"));
    /** Items that are always tracked in the background so history exists from day one. */
    private List<String> autoTrack = new ArrayList<>(List.of(
            "minecraft:diamond", "minecraft:emerald", "minecraft:netherite_ingot", "minecraft:netherite_scrap",
            "minecraft:ancient_debris", "minecraft:gold_ingot", "minecraft:iron_ingot", "minecraft:obsidian",
            "minecraft:ender_pearl", "minecraft:golden_apple", "minecraft:enchanted_golden_apple",
            "minecraft:totem_of_undying", "minecraft:elytra", "minecraft:end_crystal", "minecraft:beacon",
            "minecraft:shulker_box", "minecraft:skeleton_spawner", "minecraft:experience_bottle"));

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static MarketConfig instance;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutmarket").resolve("config.json");
    }

    public static synchronized MarketConfig get() {
        if (instance == null) {
            try {
                Path p = path();
                if (Files.exists(p)) instance = GSON.fromJson(Files.readString(p), MarketConfig.class);
            } catch (Exception e) {
                System.err.println("[DonutMarket] Could not read config: " + e);
            }
            if (instance == null) { instance = new MarketConfig(); instance.save(); }
        }
        return instance;
    }

    public synchronized void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this));
        } catch (Exception e) {
            System.err.println("[DonutMarket] Could not save config: " + e);
        }
    }

    public synchronized String apiKey() { return apiKey == null ? "" : apiKey; }
    public synchronized void setApiKey(String k) { apiKey = k; save(); }
    public synchronized int pollSeconds() { return Math.max(15, pollSeconds); }
    public synchronized String sortParam() { return sortParam; }
    public synchronized List<String> watchlist() { return new ArrayList<>(watchlist); }
    public synchronized List<String> autoTrack() { return new ArrayList<>(autoTrack); }
    public synchronized boolean isWatched(String id) { return watchlist.contains(id); }

    public synchronized boolean toggleWatch(String id) {
        boolean now;
        if (watchlist.contains(id)) { watchlist.remove(id); now = false; }
        else { watchlist.add(id); now = true; }
        save();
        return now;
    }
}
