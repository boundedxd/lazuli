package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Notices when the path asks for something the player can't do: standing still on a node for too long (no input
 * plan finds a way along the next edge), or a jump that landed somewhere other than its target. Either way that edge
 * is reported so the planner stops counting on it.
 */
public final class EdgeWatch {
    /** Ticks standing still on a node, not building, before its next edge counts as impossible. */
    static final int STUCK_TICKS = 25;

    private long stuckOn = Long.MIN_VALUE;
    private int stuckTicks;
    /** The jump edge under way (from leaving the ground until landing), or null. */
    private long[] jump;

    public void reset() {
        stuckOn = Long.MIN_VALUE;
        stuckTicks = 0;
        jump = null;
    }

    /**
     * @param block    the player's node (block under them while on the ground)
     * @param building breaking or placing this tick (standing still is expected then)
     * @return the edge {u, v} to report as failed, or null
     */
    public long[] update(List<Long> path, int idx, long block, boolean onGround, double speed, boolean building) {
        boolean atNode = idx >= 0 && idx + 1 < path.size() && path.get(idx) == block;
        if (!onGround) {
            if (jump == null && atNode && isJump(path.get(idx), path.get(idx + 1))) {
                jump = new long[]{path.get(idx), path.get(idx + 1)};
            }
            stuckTicks = 0;
            return null;
        }
        if (jump != null) {
            long[] j = jump;
            jump = null;
            // landed: on its target or further along the path is fine; anywhere else, that jump doesn't work
            if (block != j[1] && !path.subList(Math.max(0, idx), path.size()).contains(block)) return j;
        }
        if (atNode && !building && speed < 0.01) {
            if (block != stuckOn) {
                stuckOn = block;
                stuckTicks = 0;
            }
            if (++stuckTicks >= STUCK_TICKS) {
                stuckTicks = 0;
                return new long[]{path.get(idx), path.get(idx + 1)};
            }
        } else {
            stuckTicks = 0;
        }
        return null;
    }

    private static boolean isJump(long u, long v) {
        return Math.max(Math.abs(Pos.x(v) - Pos.x(u)), Math.abs(Pos.z(v) - Pos.z(u))) >= 2;
    }
}
