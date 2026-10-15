package dev.lazuli.core.move;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void routeTakesTheWideWayRoundOverATightGap() {
        // water 0..14 x 0..10; a wall at x 7 with a 1 wide gap at z 1 and a 3 wide one at z 6..8
        GridWorld g = pool(15, 11);
        g.fill(7, 0, 0, 7, 1, 10, BlockKind.SOLID);
        g.set(7, 0, 1, BlockKind.WATER).set(7, 1, 1, BlockKind.AIR);
        g.fill(7, 0, 6, 7, 0, 8, BlockKind.WATER).fill(7, 1, 6, 7, 1, 8, BlockKind.AIR);
        List<Long> r = Boating.route(g, 2, 2, 0, 13, 1, 5000);
        assertNotNull(r);
        long last = r.get(r.size() - 1);
        assertTrue(Math.max(Math.abs(Pos.x(last) - 13), Math.abs(Pos.z(last) - 1)) <= 1, "ends by the landing");
        boolean wide = r.stream().anyMatch(p -> Pos.x(p) == 7 && Pos.z(p) == 7);
        assertTrue(wide, "goes through the middle of the wide gap: " + r.stream().map(Pos::toString).toList());
    }

    @Test
    void noWaterWayIsNull() {
        GridWorld g = pool(15, 5);
        g.fill(7, 0, 0, 7, 1, 4, BlockKind.SOLID);
        assertNull(Boating.route(g, 2, 2, 0, 13, 2, 5000));
    }

    @Test
    void steeringBrakesTheSpinBeforeItOvershoots() {
        // wants to turn right (yaw up) 40 degrees
        assertEquals(-1, Boating.steer(40, 0), "starts turning right (D)");
        assertEquals(1, Boating.steer(40, 9), "spinning 9/tick it'd overshoot: counter with A");
        assertEquals(0, Boating.steer(1, 0));
        assertEquals(1, Boating.steer(-30, 0), "left (A)");
    }
}
