package dev.lazuli.core.craft;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which blocks drop which items when mined with a normal tool, read from the game's block loot tables (anything that
 * needs silk touch or shears is left out).
 */
public final class Drops {
    private final Map<String, List<String>> byItem = new HashMap<>();

    private Drops() {}

    /** @param lootTables block id -> its loot table JSON */
    public static Drops load(Map<String, String> lootTables) {
        Map<String, Set<String>> found = new HashMap<>();
        for (var e : lootTables.entrySet()) {
            Set<String> items = new TreeSet<>();
            try {
                read(Json.parse(e.getValue()), items);
            } catch (RuntimeException ignored) {
                continue; // a format it doesn't know
            }
            for (String item : items) found.computeIfAbsent(item, k -> new TreeSet<>()).add(e.getKey());
        }
        Drops d = new Drops();
        found.forEach((item, blocks) -> d.byItem.put(item, List.copyOf(blocks)));
        return d;
    }

    /** The blocks dropping {@code item}, by id. */
    public List<String> blocksDropping(String item) {
        return byItem.getOrDefault(item, List.of());
    }

    @SuppressWarnings("unchecked")
    private static void read(Object json, Set<String> out) {
        Map<String, Object> table = (Map<String, Object>) json;
        if (!(table.get("pools") instanceof List<?> pools)) return;
        for (Object p : pools) {
            Map<String, Object> pool = (Map<String, Object>) p;
            if (special(pool.get("conditions"))) continue;
            if (pool.get("entries") instanceof List<?> entries) for (Object e : entries) entry(e, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static void entry(Object json, Set<String> out) {
        Map<String, Object> e = (Map<String, Object>) json;
        if (special(e.get("conditions"))) return;
        String type = String.valueOf(e.get("type"));
        if (type.equals("minecraft:item") && e.get("name") instanceof String name) out.add(name);
        if (e.get("children") instanceof List<?> children) for (Object c : children) entry(c, out);
    }

    /** Needs a particular tool (silk touch, shears): match_tool, not inverted. */
    @SuppressWarnings("unchecked")
    private static boolean special(Object conditions) {
        if (!(conditions instanceof List<?> list)) return false;
        for (Object c : list) if ("minecraft:match_tool".equals(((Map<String, Object>) c).get("condition"))) return true;
        return false;
    }

    /**
     * About how much work one of these blocks is to find and mine: common ground and logs are cheap, ores by how rare
     * they are, anything else (placed blocks, rare plants) {@link #UNKNOWN}.
     */
    public static int abundance(String block) {
        String b = block.startsWith("minecraft:") ? block.substring(10) : block;
        if (b.startsWith("deepslate_") && b.endsWith("_ore")) b = b.substring(10);
        if (b.startsWith("stripped_")) return UNKNOWN; // only where someone stripped them
        if (b.endsWith("_log") || b.endsWith("_stem") || b.equals("bamboo_block")) return 4;
        return switch (b) {
            case "stone", "deepslate", "netherrack", "andesite", "diorite", "granite", "tuff", "dirt", "grass_block", "sand",
                 "red_sand", "gravel", "coarse_dirt", "podzol", "end_stone", "basalt", "soul_sand", "soul_soil", "calcite" -> 2;
            case "sandstone", "red_sandstone", "blackstone", "snow_block", "mud", "moss_block", "terracotta" -> 6;
            case "cobblestone", "cobbled_deepslate", "clay", "ice", "sugar_cane", "cactus", "kelp", "pumpkin", "melon" -> 10;
            case "coal_ore" -> 6;
            case "copper_ore", "nether_quartz_ore" -> 8;
            case "iron_ore", "nether_gold_ore" -> 10;
            case "redstone_ore" -> 14;
            case "lapis_ore", "obsidian" -> 16;
            case "gold_ore" -> 20;
            case "diamond_ore" -> 40;
            case "emerald_ore" -> 80;
            case "ancient_debris" -> 200;
            default -> b.endsWith("_leaves") ? 12 : UNKNOWN;
        };
    }

    /** Placed or rare blocks: only when there's no other way (worse than making any tool). */
    public static final int UNKNOWN = 1_000_000;
}
