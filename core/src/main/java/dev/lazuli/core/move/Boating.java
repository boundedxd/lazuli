package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Boats: where one fits, a way across the water wide enough for it, and steering it. A boat is 1.375 wide and sits on
 * the water's surface, so it sticks out a little into every block around its own (and the one above those): placing
 * it, or going through, needs water or air on all sides.
 */
public final class Boating {
    /** Extra cost of a cell the boat would scrape (a block beside it): it gets stuck there. */
    private static final double TIGHT = 5;
    /** Keeps turning: the boat's spin carries on, times 0.9 each tick (9x the last tick's turn in all). */
    private static final double COAST = 9;
    /** Degrees off the wanted heading it doesn't bother turning for. */
    private static final double DEADBAND = 3;

    private Boating() {}

    /** Water surface at (x, y, z) with water or air all around it and only air over all of those. */
    public static boolean fits(BlockView w, int x, int y, int z) {
        if (!Standing.swimmable(w, x, y, z)) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockKind k = w.get(x + dx, y, z + dz);
                if (k != BlockKind.WATER && k != BlockKind.AIR) return false;
                if (w.get(x + dx, y + 1, z + dz) != BlockKind.AIR) return false;
            }
        }
        return true;
    }

    /**
     * Water surface cells (level y) from (x, z) to one beside the landing block at (lx, lz), keeping where the boat
     * fits when it can; null if the water doesn't get there within {@code limit} cells looked at.
     */
    public static List<Long> route(BlockView w, int x, int z, int y, int lx, int lz, int limit) {
        if (!Standing.swimmable(w, x, y, z)) return null;
        Map<Long, Double> cost = new HashMap<>();
        Map<Long, Long> from = new HashMap<>();
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        long s = Pos.pack(x, y, z);
        cost.put(s, 0.0);
        open.add(new double[]{h(x, z, lx, lz), s});
        int seen = 0;
        while (!open.isEmpty() && seen++ < limit) {
            double[] top = open.poll();
            long c = (long) top[1];
            int cx = Pos.x(c), cz = Pos.z(c);
            double g = cost.get(c);
            if (top[0] > g + h(cx, cz, lx, lz) + 1e-9) continue; // stale
            if (Math.max(Math.abs(cx - lx), Math.abs(cz - lz)) <= 1) {
                List<Long> out = new ArrayList<>();
                for (Long p = c; p != null; p = from.get(p)) out.add(p);
                Collections.reverse(out);
                return out;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = cx + dx, nz = cz + dz;
                    if (!Standing.swimmable(w, nx, y, nz)) continue;
                    // no cutting corners: both sides of a diagonal step are water too
                    if (dx != 0 && dz != 0 && (!Standing.swimmable(w, cx + dx, y, cz) || !Standing.swimmable(w, cx, y, cz + dz))) continue;
                    double ng = g + (dx != 0 && dz != 0 ? Math.sqrt(2) : 1) + (fits(w, nx, y, nz) ? 0 : TIGHT);
                    long n = Pos.pack(nx, y, nz);
                    Double old = cost.get(n);
                    if (old != null && old <= ng) continue;
                    cost.put(n, ng);
                    from.put(n, c);
                    open.add(new double[]{ng + h(nx, nz, lx, lz), n});
                }
            }
        }
        return null;
    }

    private static double h(int x, int z, int lx, int lz) {
        double dx = Math.max(0, Math.abs(x - lx) - 1), dz = Math.max(0, Math.abs(z - lz) - 1);
        return Math.max(dx, dz) + (Math.sqrt(2) - 1) * Math.min(dx, dz);
    }

    /**
     * A (1) or D (-1) or neither (0), to turn the boat {@code diff} degrees (positive: its yaw up, to the right) when
     * it's already turning {@code rate} degrees a tick: it lets go, or counter-steers, before the spin overshoots.
     */
    public static int steer(double diff, double rate) {
        double left = diff - COAST * rate; // still to turn once the spin dies down by itself
        return left > DEADBAND ? -1 : left < -DEADBAND ? 1 : 0;
    }
}
