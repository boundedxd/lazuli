package dev.lazuli.core.control;

import java.util.List;
import java.util.Map;

/**
 * Fight or flee: weighs the monsters close by against the player's health, armor and weapon. Each kind of monster
 * counts for about how many zombies it's worth; the player handles about 2.5 zombies bare-handed at full health, more
 * with armor and a weapon.
 */
public final class Threat {
    private static final Map<String, Double> WEIGHT = Map.ofEntries(
        Map.entry("minecraft:zombie", 1.0), Map.entry("minecraft:husk", 1.0), Map.entry("minecraft:drowned", 1.2),
        Map.entry("minecraft:zombie_villager", 1.0), Map.entry("minecraft:skeleton", 1.5), Map.entry("minecraft:stray", 1.6),
        Map.entry("minecraft:bogged", 1.6), Map.entry("minecraft:spider", 1.0), Map.entry("minecraft:cave_spider", 1.5),
        Map.entry("minecraft:creeper", 2.0), Map.entry("minecraft:witch", 2.0), Map.entry("minecraft:pillager", 1.5),
        Map.entry("minecraft:vindicator", 3.0), Map.entry("minecraft:evoker", 4.0), Map.entry("minecraft:ravager", 8.0),
        Map.entry("minecraft:piglin_brute", 5.0), Map.entry("minecraft:hoglin", 3.0), Map.entry("minecraft:zoglin", 3.0),
        Map.entry("minecraft:blaze", 2.0), Map.entry("minecraft:wither_skeleton", 3.0), Map.entry("minecraft:slime", 0.7),
        Map.entry("minecraft:magma_cube", 1.0), Map.entry("minecraft:silverfish", 0.3), Map.entry("minecraft:phantom", 1.5),
        Map.entry("minecraft:warden", 100.0), Map.entry("minecraft:breeze", 2.0), Map.entry("minecraft:vex", 1.5));

    private Threat() {}

    /** How many zombies' worth the mobs (entity ids) are. */
    public static double danger(List<String> mobs) {
        double d = 0;
        for (String m : mobs) d += WEIGHT.getOrDefault(m, 1.5);
        return d;
    }

    /** How many zombies' worth the player can take on. */
    public static double capacity(double health, double armor, double weaponDamage) {
        return 2.5 * (health / 20) * (1 + armor / 10) * (1 + Math.max(0, weaponDamage - 1) / 5);
    }

    /** Better to run than fight. */
    public static boolean outmatched(List<String> mobs, double health, double armor, double weaponDamage) {
        return danger(mobs) > capacity(health, armor, weaponDamage);
    }
}
