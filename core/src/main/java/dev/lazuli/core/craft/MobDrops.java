package dev.lazuli.core.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What mobs drop when killed, read from the game's entity loot tables: per item, the mobs that drop it and about how
 * many per kill (the middle of the count range, times any chance; looting ignored).
 */
public final class MobDrops {
    /** {@code perKill} of the item, on average, from killing a {@code mob}. */
    public record Source(String mob, double perKill) {}

    private final Map<String, List<Source>> byItem = new HashMap<>();

    private MobDrops() {}

    /** @param lootTables entity id -> its loot table JSON */
    public static MobDrops load(Map<String, String> lootTables) {
        MobDrops d = new MobDrops();
        for (var e : lootTables.entrySet()) {
            Map<String, Double> items = new HashMap<>();
            try {
                read(Json.parse(e.getValue()), items);
            } catch (RuntimeException ignored) {
                continue; // a format it doesn't know
            }
            items.forEach((item, n) -> {
                if (n > 0) d.byItem.computeIfAbsent(item, k -> new ArrayList<>()).add(new Source(e.getKey(), n));
            });
        }
        d.byItem.values().forEach(l -> l.sort(Comparator.comparingDouble(s -> -s.perKill())));
        return d;
    }

    /** The mobs dropping {@code item}, most per kill first. */
    public List<Source> sources(String item) {
        return byItem.getOrDefault(item, List.of());
    }

    /**
     * About how much work finding and killing one of these is (on the scale of {@link Drops#abundance}): animals and
     * the usual monsters are cheap, rarer ones dearer, the nether's and end's (or bosses) {@link Drops#UNKNOWN}.
     */
    public static int rarity(String mob) {
        String m = mob.startsWith("minecraft:") ? mob.substring(10) : mob;
        return switch (m) {
            case "cow", "pig", "sheep", "chicken" -> 12;
            case "rabbit", "squid", "cod", "salmon" -> 20;
            case "zombie", "skeleton", "spider", "creeper", "husk", "stray", "drowned" -> 25;
            case "slime", "witch", "cave_spider", "glow_squid", "goat", "turtle" -> 60;
            case "enderman" -> 80;
            default -> Drops.UNKNOWN;
        };
    }

    @SuppressWarnings("unchecked")
    private static void read(Object json, Map<String, Double> out) {
        Map<String, Object> table = (Map<String, Object>) json;
        if (!(table.get("pools") instanceof List<?> pools)) return;
        for (Object p : pools) {
            Map<String, Object> pool = (Map<String, Object>) p;
            double rolls = number(pool.get("rolls"), 1) * chance(pool.get("conditions"));
            if (rolls <= 0) continue;
            if (!(pool.get("entries") instanceof List<?> entries)) continue;
            // one entry per roll, picked by weight
            double total = 0;
            for (Object e : entries) total += number(((Map<String, Object>) e).get("weight"), 1);
            for (Object e : entries) {
                Map<String, Object> entry = (Map<String, Object>) e;
                if (!"minecraft:item".equals(entry.get("type")) || !(entry.get("name") instanceof String name)) continue;
                double share = number(entry.get("weight"), 1) / total;
                double n = rolls * share * chance(entry.get("conditions")) * count(entry.get("functions"));
                out.merge(name, n, Double::sum);
            }
        }
    }

    /** Odds the conditions hold: random chances multiply, killed-by-player holds, anything else (on fire...) doesn't count. */
    @SuppressWarnings("unchecked")
    private static double chance(Object conditions) {
        if (!(conditions instanceof List<?> list)) return 1;
        double c = 1;
        for (Object o : list) {
            Map<String, Object> cond = (Map<String, Object>) o;
            String type = String.valueOf(cond.get("condition"));
            switch (type) {
                case "minecraft:killed_by_player" -> {}
                case "minecraft:random_chance" -> c *= number(cond.get("chance"), 1);
                case "minecraft:random_chance_with_enchanted_bonus" -> c *= number(cond.get("unenchanted_chance"), 1);
                default -> {
                    return 0; // set on fire, a particular variant, a charged creeper...: not counted on
                }
            }
        }
        return c;
    }

    /** Items per drop from set_count (the middle of a range), else 1. */
    @SuppressWarnings("unchecked")
    private static double count(Object functions) {
        double n = 1;
        if (!(functions instanceof List<?> list)) return n;
        for (Object o : list) {
            Map<String, Object> f = (Map<String, Object>) o;
            if (!"minecraft:set_count".equals(f.get("function")) || f.containsKey("conditions")) continue;
            Object c = f.get("count");
            if (c instanceof Map<?, ?> range) n = (number(range.get("min"), 0) + number(range.get("max"), 0)) / 2;
            else n = number(c, 1);
        }
        return Math.max(0, n);
    }

    private static double number(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof Map<?, ?> m && m.get("value") instanceof Number v) return v.doubleValue(); // constant provider
        return fallback;
    }
}
