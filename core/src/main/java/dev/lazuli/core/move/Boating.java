package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;

/**
 * Boats: where one fits. A boat is 1.375 wide and sits on the water's surface, so it sticks out a little into every
 * block around its own (and the one above those): placing it needs water or air on all sides. Driving one across is
 * {@code race/IceRacer} over a water-only {@code race/TrackField}.
 */
public final class Boating {
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
}
