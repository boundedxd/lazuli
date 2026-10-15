package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Breaking and placing for the planner. Besides nodes a player can stand in as the world is ({@link Kind#REAL}),
 * a node can be one whose body space must be dug out first ({@link Kind#DIG}) or whose floor must be placed
 * ({@link Kind#VIRTUAL}). An edge pays for clearing its destination's body (and whatever the move sweeps through);
 * the cells of the node it starts from count as already cleared, since entering that node cleared them.
 * Breaks happen on full-block floors only (feet at an integer height), cardinal moves only.
 */
public final class Digging {
    public enum Kind { NONE, REAL, DIG, VIRTUAL }

    /** Aiming, swinging and the client's 5-tick delay between consecutive blocks. */
    public static final double BREAK_OVERHEAD = 6;
    /** Sneak to the edge, turn, place. */
    public static final double BRIDGE = 15;
    /** Jump, place under, land: a jump's air time plus aiming. */
    public static final double PILLAR = 14;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double EPS = 1e-6;

    private final BlockView w;
    private final Interaction in;

    public Digging(BlockView w, Interaction in) {
        this.w = w;
        this.in = in;
    }

    public boolean enabled() {
        return in.allowBreak() || in.canPlace();
    }

    public boolean canPlace() {
        return in.canPlace();
    }

    private final dev.lazuli.core.util.LongMap<Kind> kinds = new dev.lazuli.core.util.LongMap<>(4096);

    /** Forgets cached kinds after the world changed. */
    public void invalidate() {
        kinds.clear();
    }

    /** Forgets cached kinds inside these boxes ({x0, y0, z0, x1, y1, z1} each). */
    public void invalidate(List<int[]> boxes) {
        kinds.removeIf(p -> MoveGraph.inAny(p, boxes));
    }

    /** What standing at u takes (cached: the search asks about each cell dozens of times). */
    public Kind kind(int x, int y, int z) {
        long key = Pos.pack(x, y, z);
        Kind k = kinds.get(key);
        if (k == null) {
            k = computeKind(x, y, z);
            kinds.put(key, k);
        }
        return k;
    }

    private Kind computeKind(int x, int y, int z) {
        if (Standing.standable(w, x, y, z)) return Kind.REAL;
        if (!enabled()) return Kind.NONE;
        if (clearCost(x, y + 1, z, false) == INF || clearCost(x, y, z, true) == INF) return Kind.NONE;
        if (fullFloor(x, y - 1, z)) return in.allowBreak() ? Kind.DIG : Kind.NONE;
        return placeable(x, y - 1, z) ? Kind.VIRTUAL : Kind.NONE;
    }

    /**
     * Least any path must pay to end up standing at a node of this kind: its body's solid cells broken, and for a
     * placed floor at least a pillar's placement. Standing nodes cost nothing to enter beyond moving.
     */
    public double entryCost(int x, int y, int z) {
        return switch (kind(x, y, z)) {
            case NONE, REAL -> 0;
            case DIG -> clearCost(x, y, z, true) + clearCost(x, y + 1, z, true);
            case VIRTUAL -> PILLAR + clearCost(x, y, z, true) + clearCost(x, y + 1, z, true);
        };
    }

    /** Whether edges that break or place can start or end here: a full-block floor (or one to be placed). */
    public boolean flatFloor(int x, int y, int z, Kind k) {
        return switch (k) {
            case NONE -> false;
            case DIG, VIRTUAL -> true;
            case REAL -> Math.abs(Standing.feet(w, x, y, z) - y) < EPS;
        };
    }

    /** A known block with a flat top at exactly its full height, safe to stand on. */
    public boolean fullFloor(int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.UNKNOWN) return true;
        if (k == BlockKind.AIR || k == BlockKind.WATER || k == BlockKind.DANGER) return false;
        return Math.abs(w.physics(x, y, z).top - 1) < EPS;
    }

    /** Air a throwaway block can be placed into. */
    public boolean placeable(int x, int y, int z) {
        return in.canPlace() && w.get(x, y, z) == BlockKind.AIR && w.physics(x, y, z).has(BlockPhysics.REPLACEABLE);
    }

    /** Nothing to break here: no collision, no liquid, no danger. Unknown blocks count as open. */
    public static boolean open(BlockView w, int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        return k == BlockKind.UNKNOWN || k == BlockKind.AIR;
    }

    /**
     * Ticks to clear this cell for the body: 0 if it is open, infinite if it can't be broken safely (unbreakable,
     * a liquid next to it, or a falling block above it that isn't cleared first).
     */
    public double clearCost(int x, int y, int z, boolean aboveCleared) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.UNKNOWN || k == BlockKind.AIR) return 0;
        if (k == BlockKind.WATER || k == BlockKind.DANGER || !in.allowBreak()) return INF;
        BlockPhysics p = w.physics(x, y, z);
        if (p.hardness < 0) return INF;
        if (!aboveCleared && w.get(x, y + 1, z) != BlockKind.AIR && w.physics(x, y + 1, z).has(BlockPhysics.FALLING)) {
            return INF;
        }
        if (fluid(x + 1, y, z) || fluid(x - 1, y, z) || fluid(x, y + 1, z) || fluid(x, y - 1, z)
            || fluid(x, y, z + 1) || fluid(x, y, z - 1)) {
            return INF;
        }
        double t = in.breakTime().ticks(p);
        return t == INF ? INF : BREAK_OVERHEAD + t;
    }

    private boolean fluid(int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        return k == BlockKind.WATER || (k == BlockKind.DANGER || k == BlockKind.OBSTACLE || k == BlockKind.SOLID)
            && w.physics(x, y, z).has(BlockPhysics.LAVA | BlockPhysics.WATER);
    }

    /**
     * Cost of the breaking/placing edge u->v, or infinity if there is none: cardinal walks (bridging onto a
     * placed floor, tunnelling), cardinal jumps up a block and drops (digging out the way), pillaring straight
     * up and mining straight down.
     */
    public double cost(long u, long v, double flat, int maxFall) {
        if (!enabled()) return INF;
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        int dx = vx - ux, dy = vy - uy, dz = vz - uz;
        // shape first: the planner asks about hundreds of jump-range neighbours per node
        boolean vertical = dx == 0 && dz == 0 && Math.abs(dy) == 1;
        if (!vertical && (Math.abs(dx) + Math.abs(dz) != 1 || dy > 1 || -dy > maxFall)) return INF;
        Kind ku = kind(ux, uy, uz);
        if (!flatFloor(ux, uy, uz, ku)) return INF;
        if (dx == 0 && dz == 0) {
            if (dy == 1) return pillarCost(ux, uy, uz, ku);
            if (dy == -1) return mineDownCost(ux, uy, uz);
            return INF;
        }
        if (Math.abs(dx) + Math.abs(dz) != 1 || dy > 1) return INF;
        Kind kv = kind(vx, vy, vz);
        if (!flatFloor(vx, vy, vz, kv)) return INF;
        if (dy == 0) {
            double c = flat + clearCost(vx, vy + 1, vz, false) + clearCost(vx, vy, vz, true);
            // a placed floor goes against the side of u's floor
            return kv == Kind.VIRTUAL ? c + BRIDGE : c;
        }
        if (kv == Kind.VIRTUAL) return INF;
        if (dy == 1) {
            return flat + Costs.JUMP_PENALTY + clearCost(ux, uy + 2, uz, false)
                + clearCost(vx, vy + 1, vz, false) + clearCost(vx, vy, vz, true);
        }
        if (-dy > maxFall) return INF;
        double c = flat + Costs.fallTicks(-dy);
        for (int y = uy + 1; y >= vy; y--) c += clearCost(vx, y, vz, y != uy + 1);
        return c;
    }

    private double pillarCost(int x, int y, int z, Kind ku) {
        if (!in.canPlace()) return INF;
        // the block goes into u's feet cell: open air now, or a block u's entry dug out
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.UNKNOWN || k == BlockKind.AIR && !w.physics(x, y, z).has(BlockPhysics.REPLACEABLE)) return INF;
        if (k != BlockKind.AIR && ku != Kind.DIG) return INF;
        return PILLAR + clearCost(x, y + 2, z, false);
    }

    private double mineDownCost(int x, int y, int z) {
        if (!in.allowBreak() || w.get(x, y - 2, z) == BlockKind.UNKNOWN || !fullFloor(x, y - 2, z)) return INF;
        if (open(w, x, y - 1, z)) return INF; // nothing to mine: that's a drop, and walking covers drops
        return clearCost(x, y - 1, z, true) + Costs.fallTicks(1);
    }

    /** One thing to do to the world: break {@code pos}, or place a block at {@code pos} against {@code against}. */
    public record Action(boolean place, long pos, long against) {}

    /**
     * What the edge u->v still needs done to the world as it is now, in order: breaks top-down, then the placement.
     * Empty for edges that change nothing (or are already done).
     */
    public static List<Action> actions(BlockView w, long u, long v) {
        List<Action> out = new ArrayList<>();
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        int dx = vx - ux, dy = vy - uy, dz = vz - uz;
        if (dx == 0 && dz == 0) {
            if (dy == 1) {
                breakIfSolid(w, out, ux, uy + 2, uz);
                breakIfSolid(w, out, ux, uy + 1, uz);
                if (open(w, ux, uy, uz) && w.get(ux, uy, uz) != BlockKind.UNKNOWN) {
                    out.add(new Action(true, Pos.pack(ux, uy, uz), Pos.pack(ux, uy - 1, uz)));
                }
            } else if (dy == -1) {
                breakIfSolid(w, out, ux, uy - 1, uz);
            }
            return out;
        }
        if (Math.abs(dx) + Math.abs(dz) != 1) return out;
        int top = dy >= 0 ? vy + 1 : uy + 1;
        if (dy == 1) breakIfSolid(w, out, ux, uy + 2, uz);
        for (int y = top; y >= vy; y--) breakIfSolid(w, out, vx, y, vz);
        if (dy == 0 && w.get(vx, vy - 1, vz) == BlockKind.AIR && !Standing.hasSupport(w, vx, vy, vz)) {
            out.add(new Action(true, Pos.pack(vx, vy - 1, vz), Pos.pack(ux, uy - 1, uz)));
        }
        return out;
    }

    private static void breakIfSolid(BlockView w, List<Action> out, int x, int y, int z) {
        if (!open(w, x, y, z)) out.add(new Action(false, Pos.pack(x, y, z), 0));
    }
}
