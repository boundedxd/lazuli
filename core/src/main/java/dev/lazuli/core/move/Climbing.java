package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

/**
 * Ladders, vines and the like. A cell the player's feet can be in on something climbable is a node even in mid-air:
 * holding jump (or walking into the ladder) climbs at about 0.12 blocks a tick, letting go slides down at 0.15, and
 * sneaking holds on. Getting on from beside or above, and off sideways or over the top, are edges like any walk.
 */
public final class Climbing {
    /** Ticks per block climbed: 0.2 a tick less a tick of gravity and drag. */
    public static final double UP_TICKS = 8.5;
    /** Ticks per block slid down, at 0.15 a tick. */
    public static final double DOWN_TICKS = 6.7;
    private static final double LO = 0.2, HI = 0.8, BODY = 1.8;

    private Climbing() {}

    public static boolean climbable(BlockView w, int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        return k != BlockKind.UNKNOWN && w.physics(x, y, z).has(BlockPhysics.CLIMBABLE); // vines have no collision: air
    }

    /** The feet can be in (x, y, z) on a climbable: it is one, and the body fits. */
    public static boolean node(BlockView w, int x, int y, int z) {
        return climbable(w, x, y, z) && Standing.clear(w, x + LO, z + LO, x + HI, z + HI, y, y + BODY);
    }

    public static boolean node(BlockView w, long u) {
        return node(w, Pos.x(u), Pos.y(u), Pos.z(u));
    }

    /**
     * Cost of u -> v if it involves climbing (one end a climb node without footing, or straight up or down a
     * climbable), {@code NaN} if it doesn't, infinity if it can't be done.
     */
    static double cost(BlockView w, long u, long v, double feetU, double feetV, double flat) {
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u), vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        boolean cu = node(w, ux, uy, uz), cv = node(w, vx, vy, vz);
        if (!cu && !cv) return Double.NaN;
        int dx = vx - ux, dz = vz - uz, dy = vy - uy;
        if (dx == 0 && dz == 0) {
            // up: from the ladder, or jumping into one that starts a block up; down: sliding, maybe onto the floor
            if (dy == 1) return cv && (cu || !Double.isNaN(feetU)) ? UP_TICKS : Double.POSITIVE_INFINITY;
            if (dy == -1) return cu && (cv || !Double.isNaN(feetV)) ? DOWN_TICKS : Double.POSITIVE_INFINITY;
            return Double.POSITIVE_INFINITY;
        }
        boolean footU = !Double.isNaN(feetU), footV = !Double.isNaN(feetV);
        if (footU && footV) return Double.NaN; // both have footing: an ordinary walk (a ladder's bottom cell)
        if (Math.abs(dx) + Math.abs(dz) != 1) return Double.POSITIVE_INFINITY; // on and off square to the ladder
        if (cu && !footU) {
            if (footV) {
                // off sideways, or over the top onto the block it's on
                double rise = feetV - uy;
                if (rise < -0.5 || rise > 1 + 1e-6) return Double.POSITIVE_INFINITY;
                if (!Standing.passage(w, ux, uz, vx, vz, Math.max(uy, feetV), Math.max(uy, feetV) + BODY)) {
                    return Double.POSITIVE_INFINITY;
                }
                return flat + Math.max(0, rise) * UP_TICKS;
            }
            if (cv && dy == 0) {
                return Standing.passage(w, ux, uz, vx, vz, uy, uy + BODY) ? flat : Double.POSITIVE_INFINITY;
            }
            return Double.POSITIVE_INFINITY;
        }
        // on: from footing beside it (same level) or above it (stepping off onto the ladder below)
        if (!footU || !cv || dy > 0 || dy < -1) return Double.POSITIVE_INFINITY;
        if (!Standing.passage(w, ux, uz, vx, vz, feetU, feetU + BODY)) return Double.POSITIVE_INFINITY;
        if (!Standing.clear(w, vx + LO, vz + LO, vx + HI, vz + HI, vy, feetU + BODY)) return Double.POSITIVE_INFINITY;
        return flat + (dy < 0 ? DOWN_TICKS : 0);
    }

    /** Whether path edge u -> v is climbing (see {@link #cost}): the executor drives it, not the input planner. */
    public static boolean edge(BlockView w, long u, long v) {
        double c = cost(w, u, v, Standing.feet(w, Pos.x(u), Pos.y(u), Pos.z(u)), Standing.feet(w, Pos.x(v), Pos.y(v), Pos.z(v)), 1);
        return !Double.isNaN(c);
    }

    /**
     * Which way the ladder at (x, y, z) is against a wall, as {dx, dz} toward the wall: from its collision box (a
     * ladder is a thin slab on the wall's side), else the first solid neighbour (vines); {0, 0} if there's none.
     */
    public static int[] wall(BlockView w, int x, int y, int z) {
        double[] b = w.physics(x, y, z).boxes;
        if (b.length >= 6) {
            double cx = (b[0] + b[3]) / 2 - 0.5, cz = (b[2] + b[5]) / 2 - 0.5;
            if (Math.abs(cx) > 0.2) return new int[]{(int) Math.signum(cx), 0};
            if (Math.abs(cz) > 0.2) return new int[]{0, (int) Math.signum(cz)};
        }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            if (w.get(x + d[0], y, z + d[1]) == BlockKind.SOLID) return d;
        }
        return new int[]{0, 0};
    }
}
