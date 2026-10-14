package dev.lazuli.core.move;

import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

/**
 * Rule-based maneuvers: flat walk/sprint (cardinal + diagonal), ascend one block, descend/fall up to maxFall,
 * and cardinal parkour gap jumps. A gap jump edge starts at the beginning of its run-up, so the planner never
 * needs to know the player's speed (every edge starts from a near-standstill).
 */
public final class MoveGraph implements Graph {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final double INF = LazyDStarLite.INF;

    /** A gap jump: land {@code dy} higher after clearing {@code gap} blocks, with {@code runup} blocks of run-up before the edge. */
    public record Jump(int dy, int gap, int runup, boolean needsSprint) {
        /** Horizontal distance from the edge's start (run-up start) to the landing. */
        public int span() {
            return runup + gap + 1;
        }
    }

    public static final Jump[] JUMPS = {
        new Jump(0, 1, 0, false), new Jump(0, 2, 1, true), new Jump(0, 3, 2, true),
        new Jump(1, 1, 1, true), new Jump(1, 2, 2, true),
        new Jump(-1, 1, 0, false), new Jump(-1, 2, 1, true), new Jump(-1, 3, 2, true),
    };

    private final BlockView w;
    private final int maxFall;
    private final boolean sprint;
    private final double flat;
    private final double heuristicWeight;
    private final boolean parkour;

    public MoveGraph(BlockView world, int maxFall, boolean sprint) {
        this(world, maxFall, sprint, 1.0, false);
    }

    /**
     * @param heuristicWeight {@code > 1} trades path optimality (at most that factor) for far fewer expansions
     * @param parkour         whether gap jumps are allowed
     */
    public MoveGraph(BlockView world, int maxFall, boolean sprint, double heuristicWeight, boolean parkour) {
        this.w = world;
        this.maxFall = maxFall;
        this.sprint = sprint;
        this.flat = sprint ? Costs.SPRINT_ONE : Costs.WALK_ONE;
        this.heuristicWeight = heuristicWeight;
        this.parkour = parkour;
    }

    public static boolean passable(BlockKind k) {
        return k == BlockKind.AIR || k == BlockKind.UNKNOWN;
    }

    public static boolean floor(BlockKind k) {
        return k == BlockKind.SOLID || k == BlockKind.UNKNOWN;
    }

    public static boolean isStandable(BlockView w, int x, int y, int z) {
        return floor(w.get(x, y - 1, z)) && passable(w.get(x, y, z)) && passable(w.get(x, y + 1, z));
    }

    private boolean open2(int x, int y, int z) {
        return passable(w.get(x, y, z)) && passable(w.get(x, y + 1, z));
    }

    @Override
    public boolean isNode(long u) {
        return isStandable(w, Pos.x(u), Pos.y(u), Pos.z(u));
    }

    /** The gap jump an edge u->v represents, or null if it isn't one. */
    public static Jump jumpFor(long u, long v) {
        int dx = Pos.x(v) - Pos.x(u), dy = Pos.y(v) - Pos.y(u), dz = Pos.z(v) - Pos.z(u);
        if ((dx != 0) == (dz != 0)) return null;
        int span = Math.abs(dx + dz);
        for (Jump j : JUMPS) {
            if (j.dy() == dy && j.span() == span) return j;
        }
        return null;
    }

    @Override
    public double optimisticCost(long u, long v) {
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int dx = Pos.x(v) - ux, dy = Pos.y(v) - uy, dz = Pos.z(v) - uz;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) return jumpCost(u, v);
        if (dx == 0 && dz == 0) return INF;
        if (!isNode(u) || !isNode(v)) return INF;
        boolean diag = dx != 0 && dz != 0;
        if (dy == 0) {
            if (diag && !(open2(ux + dx, uy, uz) && open2(ux, uy, uz + dz))) return INF;
            return diag ? flat * Costs.SQRT2 : flat;
        }
        if (diag) return INF;
        if (dy == 1) {
            return passable(w.get(ux, uy + 2, uz)) ? flat + Costs.JUMP_PENALTY : INF;
        }
        if (dy < 0 && -dy <= maxFall) {
            for (int y = Pos.y(v) + 2; y <= uy + 1; y++) {
                if (!passable(w.get(ux + dx, y, uz + dz))) return INF;
            }
            return flat + Costs.fallTicks(-dy);
        }
        return INF;
    }

    private double jumpCost(long u, long v) {
        if (!parkour) return INF;
        Jump j = jumpFor(u, v);
        if (j == null || (j.needsSprint() && !sprint)) return INF;
        int x = Pos.x(u), y = Pos.y(u), z = Pos.z(u);
        int sx = Integer.signum(Pos.x(v) - x), sz = Integer.signum(Pos.z(v) - z);
        // run-up and take-off blocks
        for (int k = 0; k <= j.runup(); k++) {
            if (!isStandable(w, x + sx * k, y, z + sz * k)) return INF;
        }
        int tx = x + sx * j.runup(), tz = z + sz * j.runup();
        if (!passable(w.get(tx, y + 2, tz))) return INF;
        boolean tall = j.dy() > 0 || j.gap() >= 3;
        // the gap: a real gap (no floor; unknown counts as floor, so no jumps through unloaded terrain),
        // with room for the whole jump arc
        for (int k = 1; k <= j.gap(); k++) {
            int gx = tx + sx * k, gz = tz + sz * k;
            if (floor(w.get(gx, y - 1, gz))) return INF;
            for (int yy = y; yy <= y + (tall ? 3 : 2); yy++) {
                if (!passable(w.get(gx, yy, gz))) return INF;
            }
        }
        // landing
        if (!isNode(v)) return INF;
        int vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        if (!passable(w.get(vx, Math.max(vy, y) + 2, vz))) return INF;
        return flat * j.span() + Costs.JUMP_PENALTY + (j.gap() >= 3 ? Costs.JUMP_PENALTY : 0);
    }

    @Override
    public void successors(long u, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -maxFall; dy <= 1; dy++) out.accept(Pos.offset(u, d[0], dy, d[1]));
        }
        if (!parkour) return;
        for (int[] d : CARDINALS) {
            for (Jump j : JUMPS) out.accept(Pos.offset(u, d[0] * j.span(), j.dy(), d[1] * j.span()));
        }
    }

    @Override
    public void predecessors(long v, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -1; dy <= maxFall; dy++) out.accept(Pos.offset(v, d[0], dy, d[1]));
        }
        if (!parkour) return;
        for (int[] d : CARDINALS) {
            for (Jump j : JUMPS) out.accept(Pos.offset(v, -d[0] * j.span(), -j.dy(), -d[1] * j.span()));
        }
    }

    @Override
    public double heuristic(long a, long b) {
        int dx = Math.abs(Pos.x(a) - Pos.x(b)), dz = Math.abs(Pos.z(a) - Pos.z(b));
        return heuristicWeight * flat * (Math.max(dx, dz) + (Costs.SQRT2 - 1) * Math.min(dx, dz));
    }

    @Override
    public int horizontalReach() {
        return parkour ? 6 : 1;
    }

    @Override
    public int verticalReach() {
        return maxFall + 3;
    }
}
