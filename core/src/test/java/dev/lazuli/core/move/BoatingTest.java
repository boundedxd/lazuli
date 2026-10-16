package dev.lazuli.core.move;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoatingTest {
    /** Water at y 0 over x 0..w-1, z 0..d-1, stone around and under, air above. */
    private static GridWorld pool(int w, int d) {
        GridWorld g = new GridWorld(BlockKind.AIR);
        g.fill(-1, -1, -1, w, 0, d, BlockKind.SOLID);
        g.fill(0, 0, 0, w - 1, 0, d - 1, BlockKind.WATER);
        return g;
    }

    @Test
    void aBoatOnlyFitsWithWaterAllAround() {
        GridWorld g = pool(5, 5);
        assertFalse(Boating.fits(g, 0, 0, 2), "next to the shore the boat sticks into it");
        assertFalse(Boating.fits(g, 1, 0, 0));
        assertTrue(Boating.fits(g, 1, 0, 1));
        assertTrue(Boating.fits(g, 2, 0, 2));
        g.set(2, 1, 3, BlockKind.SOLID); // something over the water beside it
        assertFalse(Boating.fits(g, 2, 0, 2));
    }
}
