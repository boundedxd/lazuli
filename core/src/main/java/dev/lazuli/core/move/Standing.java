package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

/**
 * Where a player can stand, from real collision shapes. A node is the cell (x, y, z) containing the player's
 * feet when standing centered in it: on a full block below (feet at y), on a slab, carpet, soul sand or path
 * block inside the cell (feet at y + its top), or on a fence or wall below (feet at y + 0.5). Unloaded blocks
 * follow the freespace assumption: a full floor with open air above.
 */
public final class Standing {
    /** The body's footprint when centered in a block. */
    private static final double LO = 0.2, HI = 0.8;
    public static final double BODY = 1.8;
    private static final double EPS = 1e-6;

    private Standing() {}

    /** Blocks up to this high are stood on in their own cell; taller ones from the cell above. */
    private static final double HALF = 0.5 + 1e-6;

    /** Feet height when standing centered in (x, y, z), or NaN if a player can't stand there. */
    public static double feet(BlockView w, int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.DANGER || k == BlockKind.WATER) return Double.NaN;
        double support;
        double here = k == BlockKind.UNKNOWN ? 0 : top(w.physics(x, y, z), LO, HI, LO, HI);
        // one node per place to stand: a block up to half high (slab, carpet) is stood on in its own cell, a taller
        // one (dirt path, farmland, soul sand, a chest) from the cell above, like a full block
        if (here > HALF) return Double.NaN;
        if (here > EPS) {
            support = y + here;
        } else {
            BlockKind below = w.get(x, y - 1, z);
            if (below == BlockKind.DANGER || below == BlockKind.WATER) return Double.NaN;
            double t = below == BlockKind.UNKNOWN ? 1 : top(w.physics(x, y - 1, z), LO, HI, LO, HI);
            if (t <= HALF) return Double.NaN;
            support = y - 1 + t;
            if (support >= y + 1 - EPS) return Double.NaN;
        }
        return clear(w, x + LO, z + LO, x + HI, z + HI, support, support + BODY) ? support : Double.NaN;
    }

    /** Water at the surface, where a swimmer floats: water with open air (or unloaded terrain) above. */
    public static boolean swimmable(BlockView w, int x, int y, int z) {
        if (w.get(x, y, z) != BlockKind.WATER) return false;
        BlockKind above = w.get(x, y + 1, z);
        return above == BlockKind.AIR || above == BlockKind.UNKNOWN;
    }

    /** The water surface cell a player swimming at (x, y, z) floats in, or {@link Long#MIN_VALUE} if not swimming. */
    public static long swimNode(BlockView w, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
        if (swimmable(w, bx, by - 1, bz)) return Pos.pack(bx, by - 1, bz);
        // under the surface (sunk after a drop): the surface above, through nothing but water
        for (int yy = by; yy <= by + SWIM_DEPTH; yy++) {
            if (w.get(bx, yy, bz) != BlockKind.WATER) break;
            if (swimmable(w, bx, yy, bz)) return Pos.pack(bx, yy, bz);
        }
        return Long.MIN_VALUE;
    }

    /** How far under the surface a swimmer still counts as at its surface node (a big drop sinks deep). */
    public static final int SWIM_DEPTH = 6;

    /**
     * Whether a player can stand in column (x, z) at about level {@code y}, on anything from a slab to a full block:
     * feet within half a block of y (a dirt path's 15/16 counts as y, so does a bottom slab in the block below).
     */
    public static boolean walkableAt(BlockView w, int x, int y, int z) {
        double f = feet(w, x, y, z);
        if (!Double.isNaN(f) && Math.abs(f - y) <= 0.5) return true;
        f = feet(w, x, y - 1, z);
        return !Double.isNaN(f) && Math.abs(f - y) <= 0.5;
    }

    /**
     * Something to stand on at about this level: a partial block in the cell or a full-height top below it
     * (no clearance check; cheap, for spotting gaps).
     */
    public static boolean hasSupport(BlockView w, int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.UNKNOWN) return true;
        if (k != BlockKind.AIR && k != BlockKind.WATER) {
            double here = top(w.physics(x, y, z), LO, HI, LO, HI);
            if (here > EPS && here <= HALF) return true;
        }
        BlockKind below = w.get(x, y - 1, z);
        if (below == BlockKind.UNKNOWN) return true;
        return below != BlockKind.AIR && below != BlockKind.WATER && below != BlockKind.DANGER
            && top(w.physics(x, y - 1, z), LO, HI, LO, HI) > HALF;
    }

    /**
     * The node a player at (x, y, z) stands on: their own block, or, when it overhangs an edge, the standable block
     * under the rest of their footprint nearest their center. Their own block if none is standable.
     */
    public static long nodeUnder(BlockView w, double x, double y, double z) {
        int by = (int) Math.floor(y + EPS);
        int ox = (int) Math.floor(x), oz = (int) Math.floor(z);
        if (standable(w, ox, by, oz)) return dev.lazuli.core.world.Pos.pack(ox, by, oz);
        long best = dev.lazuli.core.world.Pos.pack(ox, by, oz);
        double bestD = Double.POSITIVE_INFINITY;
        for (int bx = (int) Math.floor(x - 0.3 + EPS); bx <= (int) Math.floor(x + 0.3 - EPS); bx++) {
            for (int bz = (int) Math.floor(z - 0.3 + EPS); bz <= (int) Math.floor(z + 0.3 - EPS); bz++) {
                if ((bx == ox && bz == oz) || !standable(w, bx, by, bz)) continue;
                double d = Math.hypot(bx + 0.5 - x, bz + 0.5 - z);
                if (d < bestD) {
                    bestD = d;
                    best = dev.lazuli.core.world.Pos.pack(bx, by, bz);
                }
            }
        }
        return best;
    }

    public static boolean standable(BlockView w, int x, int y, int z) {
        return !Double.isNaN(feet(w, x, y, z));
    }

    /**
     * No collision box (unknown blocks count as empty) and no danger intersects the box
     * [x0, x1] x (lo, hi) x [z0, z1].
     */
    public static boolean clear(BlockView w, double x0, double z0, double x1, double z1, double lo, double hi) {
        return clear(w, x0, z0, x1, z1, lo, hi, false);
    }

    /** @param waterOk water in the way is fine (swimming); lava and other danger still isn't */
    public static boolean clear(BlockView w, double x0, double z0, double x1, double z1, double lo, double hi, boolean waterOk) {
        int bx0 = (int) Math.floor(x0 + EPS), bx1 = (int) Math.floor(x1 - EPS);
        int bz0 = (int) Math.floor(z0 + EPS), bz1 = (int) Math.floor(z1 - EPS);
        int by0 = (int) Math.floor(lo + EPS) - 1, by1 = (int) Math.floor(hi - EPS);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int by = by0; by <= by1; by++) {
                    BlockKind k = w.get(bx, by, bz);
                    if (k == BlockKind.UNKNOWN || k == BlockKind.AIR) continue;
                    if ((k == BlockKind.DANGER || k == BlockKind.WATER && !waterOk) && by >= (int) Math.floor(lo + EPS)) return false;
                    double[] b = w.physics(bx, by, bz).boxes;
                    for (int i = 0; i < b.length; i += 6) {
                        if (bx + b[i + 3] > x0 + EPS && bx + b[i] < x1 - EPS
                            && bz + b[i + 5] > z0 + EPS && bz + b[i + 2] < z1 - EPS
                            && by + b[i + 4] > lo + EPS && by + b[i + 1] < hi - EPS) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Room for the body to move from standing in u's cell to v's (adjacent, including diagonal), with feet at
     * {@code lo} or higher: the box spanning both footprints, from lo to lo + body height, is clear.
     */
    public static boolean passage(BlockView w, int ux, int uz, int vx, int vz, double lo, double hi) {
        return passage(w, ux, uz, vx, vz, lo, hi, false);
    }

    public static boolean passage(BlockView w, int ux, int uz, int vx, int vz, double lo, double hi, boolean waterOk) {
        return clear(w, Math.min(ux, vx) + LO, Math.min(uz, vz) + LO, Math.max(ux, vx) + HI, Math.max(uz, vz) + HI, lo, hi,
            waterOk);
    }

    /** Highest collision top among boxes overlapping the given horizontal footprint (block-local), 0 if none. */
    private static double top(BlockPhysics p, double x0, double x1, double z0, double z1) {
        double t = 0;
        double[] b = p.boxes;
        for (int i = 0; i < b.length; i += 6) {
            if (b[i + 3] > x0 && b[i] < x1 && b[i + 5] > z0 && b[i + 2] < z1) t = Math.max(t, b[i + 4]);
        }
        return t;
    }
}
