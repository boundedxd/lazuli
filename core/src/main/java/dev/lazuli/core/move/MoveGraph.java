package dev.lazuli.core.move;

import dev.lazuli.core.physics.Reach;
import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;
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
    private final Digging dig;

    private final java.util.Map<Long, Boolean> nearGap = new java.util.HashMap<>();
    private final java.util.Map<Edge, Double> jumpTicks = new java.util.HashMap<>();
    private final java.util.Map<Long, Double> feetCache = new java.util.HashMap<>();
    private final java.util.Map<Edge, Double> costCache = new java.util.HashMap<>();

    private record Edge(long u, long v) {}

    public MoveGraph(BlockView world, int maxFall, boolean sprint) {
        this(world, maxFall, sprint, 1.0, false);
    }

    /**
     * @param heuristicWeight {@code > 1} trades path optimality (at most that factor) for far fewer expansions
     * @param parkour         whether gap jumps are allowed
     */
    public MoveGraph(BlockView world, int maxFall, boolean sprint, double heuristicWeight, boolean parkour) {
        this(world, maxFall, sprint, heuristicWeight, parkour, Interaction.NONE);
    }

    /** @param interaction what the path may break and place (see {@link Digging}) */
    public MoveGraph(BlockView world, int maxFall, boolean sprint, double heuristicWeight, boolean parkour,
                     Interaction interaction) {
        this.dig = new Digging(world, interaction);
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
        return Standing.standable(w, x, y, z);
    }

    /** A cell the jump arc can pass through: no collision, no liquid (unknown counts as open). */
    private boolean openCell(int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.UNKNOWN) return true;
        if (k == BlockKind.WATER) return false;
        return w.physics(x, y, z).empty();
    }

    /** Feet height at u (NaN if not standable), cached: the search asks about the same cells hundreds of times. */
    private double feet(long u) {
        Double f = feetCache.get(u);
        if (f == null) {
            f = Standing.feet(w, Pos.x(u), Pos.y(u), Pos.z(u));
            feetCache.put(u, f);
        }
        return f;
    }

    @Override
    public boolean isNode(long u) {
        return !Double.isNaN(feet(u)) || dig.kind(Pos.x(u), Pos.y(u), Pos.z(u)) != Digging.Kind.NONE;
    }

    /**
     * What edge u->v needs done to the world before it can be walked: nothing for edges that work as the world is,
     * else the breaks and placement {@link Digging} priced in.
     */
    public List<Digging.Action> actions(long u, long v) {
        return baseCost(u, v) < INF ? List.of() : Digging.actions(w, u, v);
    }

    /** True for an edge whose feasibility the physics decides: a gap jump, or a diagonal jump up or drop. */
    public boolean isJump(long u, long v) {
        int dx = Math.abs(Pos.x(v) - Pos.x(u)), dz = Math.abs(Pos.z(v) - Pos.z(u));
        if (Math.max(dx, dz) >= 2) return true;
        if (dx == 1 && dz == 1) {
            double fu = feet(u), fv = feet(v);
            return Math.abs(fv - fu) > STEP;
        }
        return false;
    }

    /** Vanilla step height: rises up to this are walked, not jumped. */
    private static final double STEP = 0.6 + 1e-6;
    /** Highest rise a standing jump clears. */
    private static final double JUMP_RISE = 1.25;

    @Override
    public double optimisticCost(long u, long v) {
        // repairs re-price the same edges many times over; the world can't change without invalidate()
        Edge e = new Edge(u, v);
        Double cached = costCache.get(e);
        if (cached != null) return cached;
        double c = computeOptimistic(u, v);
        costCache.put(e, c);
        return c;
    }

    private double computeOptimistic(long u, long v) {
        double c = baseCost(u, v);
        return c < INF || !dig.enabled() ? c : Math.min(c, dig.cost(u, v, flat, maxFall));
    }

    /** Cost of moving through the world as it is. */
    private double baseCost(long u, long v) {
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int dx = Pos.x(v) - ux, dz = Pos.z(v) - uz;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) return jumpCost(u, v);
        if (dx == 0 && dz == 0) return INF;
        double fu = feet(u), fv = feet(v);
        if (Double.isNaN(fu) || Double.isNaN(fv)) return INF;
        int vx = ux + dx, vz = uz + dz;
        boolean diag = dx != 0 && dz != 0;
        double dh = fv - fu;
        if (Math.abs(dh) <= STEP) {
            // walk, stepping up or down at most a slab
            double lo = Math.max(fu, fv);
            if (!Standing.passage(w, ux, uz, vx, vz, lo, lo + Standing.BODY)) return INF;
            return diag ? flat * Costs.SQRT2 : flat;
        }
        if (diag) {
            // diagonal jump up or drop: plausible if there is room above u; the physics decides
            if (!parkour || dh > JUMP_RISE || -dh > maxFall + STEP) return INF;
            if (!Standing.clear(w, ux + 0.2, uz + 0.2, ux + 0.8, uz + 0.8, fu, Math.max(fu, fv) + Standing.BODY + 0.5)) {
                return INF;
            }
            // only over loaded terrain: the sim can't judge unknown blocks
            for (int y = (int) Math.floor(Math.min(fu, fv)) - 1; y <= (int) Math.floor(Math.max(fu, fv)) + 2; y++) {
                if (w.get(vx, y, uz) == BlockKind.UNKNOWN || w.get(ux, y, vz) == BlockKind.UNKNOWN
                    || w.get(vx, y, vz) == BlockKind.UNKNOWN) {
                    return INF;
                }
            }
            return Math.max(Reach.minTicks(Costs.SQRT2 - 0.71), airTicks(dh, Math.max(0, dh) + 0.5)) + Costs.JUMP_PENALTY;
        }
        if (dh > 0) {
            // jump up: room to rise in place, then to move over onto v
            if (dh > JUMP_RISE) return INF;
            if (!Standing.clear(w, ux + 0.2, uz + 0.2, ux + 0.8, uz + 0.8, fu, fv + Standing.BODY)) return INF;
            if (!Standing.passage(w, ux, uz, vx, vz, fv, fv + Standing.BODY)) return INF;
            return flat + Costs.JUMP_PENALTY;
        }
        // drop: move over at u's height, then fall down v's column
        int blocks = (int) Math.ceil(-dh - 1e-6);
        if (blocks > maxFall) return INF;
        if (!Standing.passage(w, ux, uz, vx, vz, fu, fu + Standing.BODY)) return INF;
        if (!Standing.clear(w, vx + 0.2, vz + 0.2, vx + 0.8, vz + 0.8, fv, fu + Standing.BODY)) return INF;
        return flat + Costs.fallTicks(blocks);
    }

    private double jumpCost(long u, long v) {
        if (!parkour) return INF;
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        int dy = vy - uy;
        if (dy > 1 || dy < -maxFall) return INF;
        double dx = vx - ux, dz = vz - uz, d = Math.hypot(dx, dz);
        if (d > JUMP_RANGE + 1e-9) return INF;
        if (Double.isNaN(feet(u)) || Double.isNaN(feet(v))) return INF;
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
            boolean gap = gapColumn(bx, Math.min(uy, vy), bz);
            if (gap && !ends) {
                if (firstGap < 0) firstGap = t;
                lastGap = t;
            }
            long cell = Pos.pack(bx, 0, bz);
            if (!ends && cell != prevCell) {
                int low = gap ? Math.min(uy, vy) : uy;
                for (int y = low; y <= top; y++) {
                    if (!openCell(bx, y, bz)) return INF;
                }
            }
            prevCell = cell;
            for (int side = -1; side <= 1; side += 2) {
                int sx = (int) Math.floor(cx + px * side), sz = (int) Math.floor(cz + pz * side);
                if ((sx == ux && sz == uz) || (sx == vx && sz == vz)) continue;
                for (int y = uy; y <= top - 1; y++) {
                    if (!openCell(sx, y, sz)) return INF;
                }
            }
        }
        if (firstGap < 0) return INF; // nothing to jump over: walking edges cover it
        double gapLen = lastGap - firstGap + 0.25;
        if (gapLen > maxFlight(dy) + 2 * PLAYER_HALF_WIDTH) return INF;
        // the landing counts anywhere inside v, so the center travels at least d - 0.71; and it can't land before the
        // arc (or a fall, going down) gets there
        return Math.max(Reach.minTicks(d - 0.71), airTicks(dy, dy > 0 ? 2.2 : 1.2)) + Costs.JUMP_PENALTY + risk(d);
    }

    /** Ticks per block of jump beyond a 2-block gap: long jumps leave little margin, so a slower sure way can win. */
    static final double RISK_PER_BLOCK = 20;

    /** What a jump of center distance {@code d} costs for the chance of missing it. */
    static double risk(double d) {
        return RISK_PER_BLOCK * Math.max(0, d - 3);
    }

    /**
     * Fewest ticks from leaving the ground to landing {@code dh} higher, minus one for safety. Landing needs a downward
     * collision, so going up it comes after the jump's apex; going down, walking off and falling may be sooner. A
     * ceiling may cut the jump short at {@code ceiling} above the start.
     */
    static double airTicks(double dh, double ceiling) {
        int best = Integer.MAX_VALUE;
        if (dh <= 0) {
            double y = 0, vy = 0;
            int t = 0;
            while (y > dh && t < 400) {
                vy = (vy - 0.08) * 0.98;
                y += vy;
                t++;
            }
            // walking off first: the body leaves its block only once the center is 0.8 past the middle
            best = t + Reach.minTicks(0.8);
        }
        double y = 0, vy = 0.42;
        for (int t = 1; t < 400; t++) {
            double ny = Math.min(y + vy, ceiling);
            if (ny <= dh && vy < 0) {
                best = Math.min(best, t);
                break;
            }
            vy = ny == ceiling && vy > 0 ? 0 : vy;
            y = ny;
            vy = (vy - 0.08) * 0.98;
        }
        return Math.max(0, best - 1);
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
        double fu = feet(u), fv = feet(v);
        Edge e = new Edge(u, v);
        Double ticks = jumpTicks.get(e);
        if (ticks == null) {
            ticks = JumpEvaluator.ticks(w, u, fu, v, fv, sprint);
            jumpTicks.put(e, ticks);
        }
        double d = Math.hypot(Pos.x(v) - Pos.x(u), Pos.z(v) - Pos.z(u));
        return Math.max(opt, ticks + Costs.JUMP_PENALTY + risk(d));
    }

    @Override
    public void invalidate() {
        nearGap.clear();
        jumpTicks.clear();
        feetCache.clear();
        costCache.clear();
        dig.invalidate();
    }

    @Override
    public void invalidate(java.util.List<int[]> boxes) {
        nearGap.keySet().removeIf(p -> inAny(p, boxes));
        feetCache.keySet().removeIf(p -> inAny(p, boxes));
        jumpTicks.keySet().removeIf(e -> inAny(e.u(), boxes));
        costCache.keySet().removeIf(e -> inAny(e.u(), boxes));
        dig.invalidate(boxes);
    }

    static boolean inAny(long p, java.util.List<int[]> boxes) {
        int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
        for (int[] b : boxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) return true;
        }
        return false;
    }

    /** Some block within jump range around u's floor level is not floor, so jumps from u could matter. */
    private boolean nearGap(long u) {
        Boolean cached = nearGap.get(u);
        if (cached != null) return cached;
        int x = Pos.x(u), y = Pos.y(u) - 1, z = Pos.z(u);
        boolean found = false;
        for (int[] o : JUMP_OFFSETS) {
            if (gapColumn(x + o[0], y + 1, z + o[1])) {
                found = true;
                break;
            }
        }
        nearGap.put(u, found);
        return found;
    }

    /**
     * A column a jump at this level has to clear: nothing to stand on at it or one below. Shallower dips are walked
     * (down and back up), and the input planner hops them on its own when that's faster, so they need no jump edges;
     * without this, hills would sprout thousands of jump edges the physics then has to reject.
     */
    private boolean gapColumn(int x, int y, int z) {
        if (Standing.hasSupport(w, x, y, z)) return false;
        return !Standing.hasSupport(w, x, y - 1, z) || hazard(x, y - 1, z) || hazard(x, y - 2, z);
    }

    /** Lava, fire, water...: a dip holding one is never walked through. */
    private boolean hazard(int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        return k == BlockKind.DANGER || k == BlockKind.WATER;
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
            for (int dy = -maxFall - 1; dy <= 2; dy++) {
                long v = Pos.offset(u, d[0], dy, d[1]);
                if (isNode(v)) out.accept(v); // most of the column is solid ground or open air: no edge to price
            }
        }
        if (dig.enabled()) {
            for (int dy = -1; dy <= 1; dy += 2) {
                long v = Pos.offset(u, 0, dy, 0);
                if (isNode(v)) out.accept(v);
            }
        }
        if (!parkour || Double.isNaN(feet(u)) || !nearGap(u)) return;
        for (int[] o : JUMP_OFFSETS) {
            for (int dy = -maxFall; dy <= 1; dy++) {
                long v = Pos.offset(u, o[0], dy, o[1]);
                if (!Double.isNaN(feet(v))) out.accept(v);
            }
        }
    }

    @Override
    public void predecessors(long v, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -2; dy <= maxFall + 1; dy++) {
                long u = Pos.offset(v, d[0], dy, d[1]);
                if (isNode(u)) out.accept(u);
            }
        }
        if (dig.enabled()) {
            for (int dy = -1; dy <= 1; dy += 2) {
                long u = Pos.offset(v, 0, dy, 0);
                if (isNode(u)) out.accept(u);
            }
        }
        if (!parkour || Double.isNaN(feet(v))) return; // only real footing takes off or lands a jump
        for (int[] o : JUMP_OFFSETS) {
            for (int dy = -1; dy <= maxFall; dy++) {
                long u = Pos.offset(v, -o[0], dy, -o[1]);
                if (!Double.isNaN(feet(u)) && nearGap(u)) out.accept(u);
            }
        }
    }

    @Override
    public double heuristic(long a, long b) {
        int dx = Math.abs(Pos.x(a) - Pos.x(b)), dz = Math.abs(Pos.z(a) - Pos.z(b));
        double h = heuristicWeight * flat * (Math.max(dx, dz) + (Costs.SQRT2 - 1) * Math.min(dx, dz));
        // Searching backward, a node inside the ground or in mid-air is as cheap to leave as the surface; what makes
        // it expensive is getting into it. Counting that here keeps the search from flooding them around the player.
        if (dig.enabled() && a != b) h += dig.entryCost(Pos.x(b), Pos.y(b), Pos.z(b));
        return h;
    }

    @Override
    public int horizontalReach() {
        // digging checks the neighbours of the cells it breaks for liquids
        return parkour ? JUMP_RANGE + 1 : dig.enabled() ? 2 : 1;
    }

    @Override
    public int verticalReach() {
        return maxFall + 4;
    }
}
