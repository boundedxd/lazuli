package dev.lazuli.core.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Where to put down a block (a crafting table, a furnace) around a player, and where to stand when there's nowhere. */
public final class PlaceSpots {
    private static final int[][] FACES = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};

    private PlaceSpots() {}

    public interface Probe {
        /** Air or something a block can go in (grass, snow...). */
        boolean free(int x, int y, int z);

        /** Solid to place against, and doesn't open when right-clicked. */
        boolean support(int x, int y, int z);
    }

    /** Put a block at x y z by clicking the face of the against block that faces it. */
    public record Spot(int x, int y, int z, int againstX, int againstY, int againstZ) {}

    /** Spots around a player standing at feet block fx fy fz, best first (on the ground and close). */
    public static List<Spot> around(Probe w, int fx, int fy, int fz) {
        List<Spot> out = new ArrayList<>();
        for (int dy = -1; dy <= 2; dy++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    int x = fx + dx, y = fy + dy, z = fz + dz;
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1) || !w.free(x, y, z)) continue;
                    for (int[] f : FACES) {
                        if (w.support(x + f[0], y + f[1], z + f[2])) {
                            out.add(new Spot(x, y, z, x + f[0], y + f[1], z + f[2]));
                            break; // FACES lists the ground first
                        }
                    }
                }
        out.sort(Comparator.comparingDouble(s -> score(s, fx, fy, fz)));
        return out;
    }

    /** Places within r blocks to stand at (not here) with a spot around them, nearest first: {x, y, z} feet blocks. */
    public static List<int[]> standsNear(Probe w, int fx, int fy, int fz, int r) {
        List<int[]> out = new ArrayList<>();
        for (int dy = -3; dy <= 3; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    int x = fx + dx, y = fy + dy, z = fz + dz;
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    if (!w.free(x, y, z) || !w.free(x, y + 1, z) || !w.support(x, y - 1, z)) continue;
                    if (around(w, x, y, z).isEmpty()) continue;
                    out.add(new int[] {x, y, z});
                }
        out.sort(Comparator.comparingInt(s -> sq(s[0] - fx) + sq(s[1] - fy) + sq(s[2] - fz)));
        return out;
    }

    private static double score(Spot s, int fx, int fy, int fz) {
        double d = Math.sqrt(sq(s.x() - fx) + sq(s.y() - fy) + sq(s.z() - fz));
        return d + (s.againstY() < s.y() ? 0 : 0.5);
    }

    private static int sq(int v) {
        return v * v;
    }
}
