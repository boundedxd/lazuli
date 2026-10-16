package dev.lazuli.core.craft;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Brewing stand recipes (the game keeps them in code, not data: this is its table) and the shortest way to a potion
 * from the bottles there are: water, or a potion part of the way there already.
 */
public final class Brewing {
    private Brewing() {}

    /** Ticks one brew takes. */
    public static final int BREW_TICKS = 400;
    /** Brews one blaze powder fuels. */
    public static final int BREWS_PER_POWDER = 20;

    public enum Form {
        DRINK("minecraft:potion"), SPLASH("minecraft:splash_potion"), LINGERING("minecraft:lingering_potion");

        public final String item;

        Form(String item) {
            this.item = item;
        }
    }

    /** Potion ids (minecraft:...), from {@code from}, adding each ingredient in turn (the last ones change the bottle). */
    public record Route(String from, List<String> ingredients) {}

    private record Mix(String from, String ingredient, String to) {}

    private static final List<Mix> MIXES = new ArrayList<>();
    private static final Set<String> POTIONS = new java.util.HashSet<>();

    private static void mix(String from, String ingredient, String to) {
        MIXES.add(new Mix("minecraft:" + from, "minecraft:" + ingredient, "minecraft:" + to));
        POTIONS.add("minecraft:" + from);
        POTIONS.add("minecraft:" + to);
    }

    /** An ingredient that makes {@code to} from awkward (and mundane from water). */
    private static void start(String ingredient, String to) {
        mix("water", ingredient, "mundane");
        mix("awkward", ingredient, to);
    }

    static {
        mix("water", "glowstone_dust", "thick");
        mix("water", "redstone", "mundane");
        mix("water", "nether_wart", "awkward");
        start("breeze_rod", "wind_charged");
        start("slime_block", "oozing");
        start("stone", "infested");
        start("cobweb", "weaving");
        mix("awkward", "golden_carrot", "night_vision");
        mix("night_vision", "redstone", "long_night_vision");
        mix("night_vision", "fermented_spider_eye", "invisibility");
        mix("long_night_vision", "fermented_spider_eye", "long_invisibility");
        mix("invisibility", "redstone", "long_invisibility");
        start("magma_cream", "fire_resistance");
        mix("fire_resistance", "redstone", "long_fire_resistance");
        start("rabbit_foot", "leaping");
        mix("leaping", "redstone", "long_leaping");
        mix("leaping", "glowstone_dust", "strong_leaping");
        mix("leaping", "fermented_spider_eye", "slowness");
        mix("long_leaping", "fermented_spider_eye", "long_slowness");
        mix("slowness", "redstone", "long_slowness");
        mix("slowness", "glowstone_dust", "strong_slowness");
        mix("awkward", "turtle_helmet", "turtle_master");
        mix("turtle_master", "redstone", "long_turtle_master");
        mix("turtle_master", "glowstone_dust", "strong_turtle_master");
        mix("swiftness", "fermented_spider_eye", "slowness");
        mix("long_swiftness", "fermented_spider_eye", "long_slowness");
        start("sugar", "swiftness");
        mix("swiftness", "redstone", "long_swiftness");
        mix("swiftness", "glowstone_dust", "strong_swiftness");
        mix("awkward", "pufferfish", "water_breathing");
        mix("water_breathing", "redstone", "long_water_breathing");
        start("glistering_melon_slice", "healing");
        mix("healing", "glowstone_dust", "strong_healing");
        mix("healing", "fermented_spider_eye", "harming");
        mix("strong_healing", "fermented_spider_eye", "strong_harming");
        mix("harming", "glowstone_dust", "strong_harming");
        mix("poison", "fermented_spider_eye", "harming");
        mix("long_poison", "fermented_spider_eye", "harming");
        mix("strong_poison", "fermented_spider_eye", "strong_harming");
        start("spider_eye", "poison");
        mix("poison", "redstone", "long_poison");
        mix("poison", "glowstone_dust", "strong_poison");
        start("ghast_tear", "regeneration");
        mix("regeneration", "redstone", "long_regeneration");
        mix("regeneration", "glowstone_dust", "strong_regeneration");
        start("blaze_powder", "strength");
        mix("strength", "redstone", "long_strength");
        mix("strength", "glowstone_dust", "strong_strength");
        mix("water", "fermented_spider_eye", "weakness");
        mix("weakness", "redstone", "long_weakness");
        mix("awkward", "phantom_membrane", "slow_falling");
        mix("slow_falling", "redstone", "long_slow_falling");
    }

    /** "Fire_Resistance" -> "minecraft:fire_resistance". */
    public static String id(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains(":") ? n : "minecraft:" + n;
    }

    /** A potion that can be brewed (or water). */
    public static boolean known(String potion) {
        return POTIONS.contains(id(potion));
    }

    /**
     * The fewest brews to {@code potion} in {@code form} from any of the potions in {@code have} (water usually), or
     * null if there's no way. Splash adds gunpowder, lingering gunpowder then dragon's breath, last.
     */
    public static Route route(String potion, Form form, Set<String> have) {
        String target = id(potion);
        if (!POTIONS.contains(target)) return null;
        // backwards from the target: the first potion reached that's on hand is the nearest start
        Map<String, Mix> next = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(target));
        java.util.Set<String> seen = new java.util.HashSet<>(List.of(target));
        String from = null;
        while (!queue.isEmpty()) {
            String p = queue.poll();
            if (have.contains(p)) {
                from = p;
                break;
            }
            for (Mix m : MIXES) {
                if (!m.to().equals(p) || !seen.add(m.from())) continue;
                next.put(m.from(), m);
                queue.add(m.from());
            }
        }
        if (from == null) return null;
        List<String> steps = new ArrayList<>();
        for (String p = from; !p.equals(target); ) {
            Mix m = next.get(p);
            steps.add(m.ingredient());
            p = m.to();
        }
        if (form != Form.DRINK) steps.add("minecraft:gunpowder");
        if (form == Form.LINGERING) steps.add("minecraft:dragon_breath");
        return new Route(from, steps);
    }
}
