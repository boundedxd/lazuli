package dev.lazuli.core.util;

import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Boxes ({x0, y0, z0, x1, y1, z1}) bucketed by the chunk columns they cover, so asking whether a position is in any of
 * them only looks at the few boxes in its own chunk: invalidating caches after many changes (chunks loading, blocks
 * mined) stays one cheap check per cached entry, not one per entry per box.
 */
public final class BoxIndex {
    private final LongMap<List<int[]>> byChunk = new LongMap<>();
    private final List<int[]> huge = new ArrayList<>();

    /** Boxes spanning more chunks than this are checked for every position instead of being bucketed. */
    private static final int MAX_CHUNKS = 64;

    public BoxIndex(List<int[]> boxes) {
        for (int[] b : boxes) {
            int cx0 = b[0] >> 4, cx1 = b[3] >> 4, cz0 = b[2] >> 4, cz1 = b[5] >> 4;
            if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > MAX_CHUNKS) {
                huge.add(b);
                continue;
            }
            for (int cx = cx0; cx <= cx1; cx++)
                for (int cz = cz0; cz <= cz1; cz++) byChunk.computeIfAbsent(key(cx, cz), k -> new ArrayList<>()).add(b);
        }
    }

    public boolean contains(long p) {
        int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
        List<int[]> in = byChunk.get(key(x >> 4, z >> 4));
        if (in != null) for (int[] b : in) if (inside(b, x, y, z)) return true;
        for (int[] b : huge) if (inside(b, x, y, z)) return true;
        return false;
    }

    private static boolean inside(int[] b, int x, int y, int z) {
        return x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
