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
        Drops d = new Drops();
        for (var e : lootTables.entrySet()) {
            Map<String, Double> items = new HashMap<>();
            try {
                read(Json.parse(e.getValue()), items);
            } catch (RuntimeException ignored) {
                continue; // a format it doesn't know
            }
            for (var i : items.entrySet()) {
                found.computeIfAbsent(i.getKey(), k -> new TreeSet<>()).add(e.getKey());
                d.yields.put(e.getKey() + "|" + i.getKey(), i.getValue());
            }
        }
        found.forEach((item, blocks) -> d.byItem.put(item, List.copyOf(blocks)));
        return d;
    }

    /** block|item -> about how many of the item one block drops (without fortune). */
    private final Map<String, Double> yields = new HashMap<>();

    /** About how many {@code item} one {@code block} drops (no fortune): 4.5 redstone, 0.1 flint; 1 when unknown. */
    public double perBlock(String block, String item) {
        return yields.getOrDefault(block + "|" + item, 1.0);
    }

    /** How many blocks to mine for {@code count} of {@code item}, from the best-yielding of {@code blocks}. */
    public int blocksFor(String item, int count, List<String> blocks) {
        double best = 0;
        for (String b : blocks) best = Math.max(best, perBlock(b, item));
        if (best <= 0) best = 1;
        return Math.max(1, (int) Math.ceil(count / best - 1e-9));
    }

    /** The blocks dropping {@code item}, by id. */
    public List<String> blocksDropping(String item) {
        return byItem.getOrDefault(item, List.of());
    }

    @SuppressWarnings("unchecked")
    private static void read(Object json, Map<String, Double> out) {
        Map<String, Object> table = (Map<String, Object>) json;
        if (!(table.get("pools") instanceof List<?> pools)) return;
        for (Object p : pools) {
            Map<String, Object> pool = (Map<String, Object>) p;
            if (special(pool.get("conditions"))) continue;
            double rolls = amount(pool.get("rolls"), 1) * chance(pool.get("conditions"));
            if (pool.get("entries") instanceof List<?> entries) for (Object e : entries) entry(e, rolls, out);
        }
    }

    @SuppressWarnings("unchecked")
    private static void entry(Object json, double times, Map<String, Double> out) {
        Map<String, Object> e = (Map<String, Object>) json;
        if (special(e.get("conditions"))) return;
        times *= chance(e.get("conditions"));
        String type = String.valueOf(e.get("type"));
        if (type.equals("minecraft:item") && e.get("name") instanceof String name) {
            double n = times;
            if (e.get("functions") instanceof List<?> fs) {
                for (Object f : fs) {
                    Map<String, Object> fn = (Map<String, Object>) f;
                    if ("minecraft:set_count".equals(fn.get("function")) && !Boolean.TRUE.equals(fn.get("add"))) n = times * amount(fn.get("count"), 1);
                    n *= chance(fn.get("conditions"));
                }
            }
            out.merge(name, n, Math::max);
        }
        if (e.get("children") instanceof List<?> children) for (Object c : children) entry(c, times, out);
    }

    /** A loot number: a constant, uniform (the middle), binomial (n * p); {@code otherwise} when it can't tell. */
    @SuppressWarnings("unchecked")
    private static double amount(Object v, double otherwise) {
        if (v instanceof Number n) return n.doubleValue();
        if (!(v instanceof Map<?, ?> m)) return otherwise;
        Map<String, Object> o = (Map<String, Object>) m;
        String type = String.valueOf(o.get("type"));
        if (type.endsWith("uniform")) return (amount(o.get("min"), otherwise) + amount(o.get("max"), otherwise)) / 2;
        if (type.endsWith("binomial")) return amount(o.get("n"), otherwise) * amount(o.get("p"), 1);
        if (type.endsWith("constant")) return amount(o.get("value"), otherwise);
        return otherwise;
    }

    /** The chance from random_chance / table_bonus conditions (no enchantment: the first chance), else 1. */
    @SuppressWarnings("unchecked")
    private static double chance(Object conditions) {
        if (!(conditions instanceof List<?> list)) return 1;
        double c = 1;
        for (Object o : list) {
            Map<String, Object> cond = (Map<String, Object>) o;
            String type = String.valueOf(cond.get("condition"));
            if (type.equals("minecraft:random_chance")) c *= amount(cond.get("chance"), 1);
            if (type.equals("minecraft:table_bonus") && cond.get("chances") instanceof List<?> ch && !ch.isEmpty()) c *= amount(ch.get(0), 1);
        }
        return c;
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
