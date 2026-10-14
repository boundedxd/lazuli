package dev.lazuli.core.move;

import dev.lazuli.core.physics.Reach;
import dev.lazuli.core.physics.Reach;
import dev.lazuli.core.physics.Reach;
import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

/**
 * Maneuvers: flat walk/sprint (cardinal + diagonal), ascend one block, descend/fall up to maxFall, and jumps
 * to any standable block within {@link #JUMP_RANGE} in any direction, from {@code maxFall} below to one above.
 * Nothing about jumps is hard-coded: the optimistic cost only rules out geometrically impossible ones (no gap
 * to cross, blocked arc, gap wider than the player could fly), and {@link #evaluate} runs the physics
 * ({@link JumpEvaluator}) on the edges the planner actually wants to use. A jump edge starts standing at u, so
 * its run-up is part of the edge and costs stay independent of how the player got to u.
 */
public final class MoveGraph implements Graph {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double INF = LazyDStarLite.INF;
    /** Farthest a jump edge reaches horizontally (run-up included). */
    public static final int JUMP_RANGE = 7;
    /** Upper bound on horizontal speed in the air (blocks/tick), for ruling out gaps that can't be flown. */
    private static final double MAX_AIR_SPEED = 0.42;
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final int[][] JUMP_OFFSETS = jumpOffsets();

    private final BlockView w;
    private final int maxFall;
    private final boolean sprint;
    private final double flat;
    private final double heuristicWeight;
    private final boolean parkour;

    private final java.util.Map<Long, Boolean> nearGap = new java.util.HashMap<>();
    private final java.util.Map<Edge, Double> jumpTicks = new java.util.HashMap<>();

    private record Edge(long u, long v) {}

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

    /** True for an edge that is a jump rather than a walk/ascend/descend step. */
    public static boolean isJump(long u, long v) {
        int dx = Math.abs(Pos.x(v) - Pos.x(u)), dz = Math.abs(Pos.z(v) - Pos.z(u));
        return Math.max(dx, dz) >= 2 || (dx == 1 && dz == 1 && Pos.y(v) != Pos.y(u));
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
        if (diag) {
            // diagonal step up or down: plausible if the arc has room above u; the physics decides
            if (!parkour || dy > 1 || dy < -maxFall) return INF;
            if (dy == 1 && !passable(w.get(ux, uy + 2, uz))) return INF;
            // only over loaded terrain: the sim can't judge unknown blocks
            int vy = Pos.y(v);
            for (int y = Math.min(uy, vy) - 1; y <= Math.max(uy, vy) + 1; y++) {
                if (w.get(ux + dx, y, uz) == BlockKind.UNKNOWN || w.get(ux, y, uz + dz) == BlockKind.UNKNOWN
                    || w.get(ux + dx, y, uz + dz) == BlockKind.UNKNOWN) {
                    return INF;
                }
            }
            return Reach.minTicks(Costs.SQRT2 - 0.71) + Costs.JUMP_PENALTY;
        }
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
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        int dy = vy - uy;
        if (dy > 1 || dy < -maxFall) return INF;
        double dx = vx - ux, dz = vz - uz, d = Math.hypot(dx, dz);
        if (d > JUMP_RANGE + 1e-9) return INF;
        if (!isNode(u) || !isNode(v)) return INF;
        int top = Math.max(uy, vy) + 2;
        // walk the straight line between the centers: the arc needs room, and there must be a real gap
        double ux0 = ux + 0.5, uz0 = uz + 0.5, nx = dx / d, nz = dz / d, px = -nz * 0.3, pz = nx * 0.3;
        double firstGap = -1, lastGap = -1;
        long prevCell = Long.MIN_VALUE;
        int steps = (int) Math.ceil(d / 0.25);
        for (int i = 1; i < steps; i++) {
            double t = i * 0.25, cx = ux0 + nx * t, cz = uz0 + nz * t;
            int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
            boolean ends = (bx == ux && bz == uz) || (bx == vx && bz == vz);
            boolean gap = !floor(w.get(bx, uy - 1, bz));
            if (gap && !ends) {
                if (firstGap < 0) firstGap = t;
                lastGap = t;
            }
            long cell = Pos.pack(bx, 0, bz);
            if (!ends && cell != prevCell) {
                int low = gap ? Math.min(uy, vy) : uy;
                for (int y = low; y <= top; y++) {
                    if (!passable(w.get(bx, y, bz))) return INF;
                }
            }
            prevCell = cell;
            for (int side = -1; side <= 1; side += 2) {
                int sx = (int) Math.floor(cx + px * side), sz = (int) Math.floor(cz + pz * side);
                if ((sx == ux && sz == uz) || (sx == vx && sz == vz)) continue;
                for (int y = uy; y <= top - 1; y++) {
                    if (!passable(w.get(sx, y, sz))) return INF;
                }
            }
        }
        if (firstGap < 0) return INF; // nothing to jump over: walking edges cover it
        double gapLen = lastGap - firstGap + 0.25;
        if (gapLen > maxFlight(dy) + 2 * PLAYER_HALF_WIDTH) return INF;
        // the landing counts anywhere inside v, so the center travels at least d - 0.71
        return Reach.minTicks(d - 0.71) + Costs.JUMP_PENALTY;
    }

    /** Generous bound on horizontal flight distance for a jump landing {@code dy} higher. */
    private static double maxFlight(int dy) {
        double y = 0, vy = 0.42;
        int t = 0;
        boolean falling = false;
        while (t < 200) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            t++;
            if (vy < 0) falling = true;
            if (falling && y <= dy) break;
        }
        return 0.65 + MAX_AIR_SPEED * t;
    }

    @Override
    public double evaluate(long u, long v) {
        double opt = optimisticCost(u, v);
        if (opt == INF || !isJump(u, v)) return opt;
        Edge e = new Edge(u, v);
        Double ticks = jumpTicks.get(e);
        if (ticks == null) {
            ticks = JumpEvaluator.ticks(w, u, v, sprint);
            jumpTicks.put(e, ticks);
        }
        return Math.max(opt, ticks + Costs.JUMP_PENALTY);
    }

    @Override
    public void invalidate() {
        nearGap.clear();
        jumpTicks.clear();
    }

    /** Some block within jump range around u's floor level is not floor, so jumps from u could matter. */
    private boolean nearGap(long u) {
        Boolean cached = nearGap.get(u);
        if (cached != null) return cached;
        int x = Pos.x(u), y = Pos.y(u) - 1, z = Pos.z(u);
        boolean found = false;
        for (int[] o : JUMP_OFFSETS) {
            if (!floor(w.get(x + o[0], y, z + o[1]))) {
                found = true;
                break;
            }
        }
        nearGap.put(u, found);
        return found;
    }

    private static int[][] jumpOffsets() {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int dx = -JUMP_RANGE; dx <= JUMP_RANGE; dx++) {
            for (int dz = -JUMP_RANGE; dz <= JUMP_RANGE; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) >= 2 && dx * dx + dz * dz <= JUMP_RANGE * JUMP_RANGE) {
                    out.add(new int[]{dx, dz});
                }
            }
        }
        // nearest first, so nearGap() usually stops early
        out.sort(java.util.Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        return out.toArray(new int[0][]);
    }

    @Override
    public void successors(long u, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -maxFall; dy <= 1; dy++) out.accept(Pos.offset(u, d[0], dy, d[1]));
        }
        if (!parkour || !nearGap(u)) return;
        for (int[] o : JUMP_OFFSETS) {
            for (int dy = -maxFall; dy <= 1; dy++) out.accept(Pos.offset(u, o[0], dy, o[1]));
        }
    }

    @Override
    public void predecessors(long v, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -1; dy <= maxFall; dy++) out.accept(Pos.offset(v, d[0], dy, d[1]));
        }
        if (!parkour) return;
        for (int[] o : JUMP_OFFSETS) {
            for (int dy = -1; dy <= maxFall; dy++) {
                long u = Pos.offset(v, -o[0], dy, -o[1]);
                if (nearGap(u)) out.accept(u);
            }
        }
    }

    @Override
    public double heuristic(long a, long b) {
        int dx = Math.abs(Pos.x(a) - Pos.x(b)), dz = Math.abs(Pos.z(a) - Pos.z(b));
        return heuristicWeight * flat * (Math.max(dx, dz) + (Costs.SQRT2 - 1) * Math.min(dx, dz));
    }

    @Override
    public int horizontalReach() {
        return parkour ? JUMP_RANGE + 1 : 1;
    }

    @Override
    public int verticalReach() {
        return maxFall + 3;
    }
}
