package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockPhysics;

/**
 * What the player may do to the world while pathing: break blocks (at what speed, given their tools) and place
 * throwaway blocks. A snapshot taken when a path starts.
 */
public record Interaction(boolean allowBreak, boolean allowPlace, int throwaways, BreakTime breakTime) {
    /** Ticks to break a block with the best available tool, or {@link Double#POSITIVE_INFINITY} if it can't be. */
    public interface BreakTime {
        double ticks(BlockPhysics p);
    }

    public static final Interaction NONE = new Interaction(false, false, 0, p -> Double.POSITIVE_INFINITY);

    /** Bare hands, vanilla formula: a tool-requiring block takes hardness * 100 ticks, others hardness * 30. */
    public static double handTicks(BlockPhysics p, boolean needsTool) {
        if (p.hardness < 0) return Double.POSITIVE_INFINITY;
        if (p.hardness == 0) return 1;
        return Math.ceil(p.hardness * (needsTool ? 100 : 30));
    }

    public boolean canPlace() {
        return allowPlace && throwaways > 0;
    }
}
