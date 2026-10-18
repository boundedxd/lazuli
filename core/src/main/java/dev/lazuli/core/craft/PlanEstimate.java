package dev.lazuli.core.craft;

import dev.lazuli.core.mine.OreDepth;

import java.util.List;
import java.util.Locale;

/**
 * Rough time for a #getitem plan, for its dry run: a few seconds per crafting step, ten per item smelted (one
 * furnace), a couple per block for common blocks and more for ores (they have to be found first), half a minute per
 * mob drop. Only a guess, meant to tell a minute's job from an hour's.
 */
public final class PlanEstimate {
    private PlanEstimate() {}

    /** Seconds for one step. */
    public static double seconds(ItemPlanner.Action a) {
        return switch (a.kind()) {
            case MINE -> 10 + a.count() * (a.blocks().stream().anyMatch(OreDepth::isOre) ? 12 : 2.5);
            case CRAFT -> 2 + a.count() * 0.1;
            case SMELT -> 8 + a.count() * 10;
            case KILL -> 20 + a.count() * 25;
            case SMITH -> 15 + a.count() * 2;
            case BUCKET -> 15 + a.count() * 10;
            case CAST -> 30 + a.count() * 12;
        };
    }

    public static double seconds(List<ItemPlanner.Action> plan) {
        double s = 0;
        for (ItemPlanner.Action a : plan) s += seconds(a);
        return s;
    }

    /** "45s", "3m 20s", "1h 05m". */
    public static String format(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) return s + "s";
        if (s < 3600) return String.format(Locale.ROOT, "%dm %02ds", s / 60, s % 60);
        return String.format(Locale.ROOT, "%dh %02dm", s / 3600, s / 60 % 60);
    }
}
