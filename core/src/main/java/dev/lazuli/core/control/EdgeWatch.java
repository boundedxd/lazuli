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
    /** Ticks on one node, moving or not (sliding along a wall, hopping in place), before its next edge is given up. */
    static final int NO_PROGRESS_TICKS = 80;

    private long stuckOn = Long.MIN_VALUE;
    private int stuckTicks;
    private long progressNode = Long.MIN_VALUE;
    private int progressTicks;
    /** The jump edge under way (from leaving the ground until landing), or null. */
    private long[] jump;
    /** Missed jumps per edge: a step up gets a second try before it counts as impossible. */
    private final java.util.Map<java.util.List<Long>, Integer> misses = new java.util.HashMap<>();

    public void reset() {
        stuckOn = Long.MIN_VALUE;
        stuckTicks = 0;
        jump = null;
        misses.clear();
        progressNode = Long.MIN_VALUE;
        progressTicks = 0;
    }

    /**
     * @param block    the player's node (block under them while on the ground)
     * @param building breaking or placing this tick (standing still is expected then)
     * @return the edge {u, v} to report as failed, or null
     */
    public long[] update(List<Long> path, int idx, long block, boolean onGround, double speed, boolean building) {
        boolean atNode = idx >= 0 && idx + 1 < path.size() && path.get(idx) == block;
        long node = idx >= 0 && idx + 1 < path.size() ? path.get(idx) : Long.MIN_VALUE;
        if (building || node != progressNode) {
            progressNode = node;
            progressTicks = 0;
        } else if (node != Long.MIN_VALUE && ++progressTicks >= NO_PROGRESS_TICKS) {
            progressTicks = 0;
            jump = null;
            return new long[]{node, path.get(idx + 1)};
        }
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
            // landed: on its target or further along the path is fine; anywhere else (back where it took off from,
            // after bonking a corner, included) that jump doesn't work
            int from = path.indexOf(j[0]);
            List<Long> ahead = from >= 0 ? path.subList(from + 1, path.size()) : path.subList(Math.max(0, idx), path.size());
            if (block != j[1] && (block == j[0] || !ahead.contains(block))) {
                boolean gap = Math.max(Math.abs(Pos.x(j[1]) - Pos.x(j[0])), Math.abs(Pos.z(j[1]) - Pos.z(j[0]))) >= 2;
                int n = misses.merge(List.of(j[0], j[1]), 1, Integer::sum);
                if (gap || n >= 2) return j;
            }
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

    /** Gaps, and steps up (a bonked head or corner drops the player back where they started). */
    private static boolean isJump(long u, long v) {
        return Math.max(Math.abs(Pos.x(v) - Pos.x(u)), Math.abs(Pos.z(v) - Pos.z(u))) >= 2 || Pos.y(v) > Pos.y(u);
    }
}
