package dev.lazuli.core.control;

import dev.lazuli.core.move.Standing;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.testutil.Shapes;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import static dev.lazuli.core.control.CourseTest.assertArrives;
import static dev.lazuli.core.control.CourseTest.drive;
import static org.junit.jupiter.api.Assertions.*;

/** Real-world terrain: slabs, stairs, carpets, soul sand, fences. Closed loop through planner, tracker and MPC. */
class PartialBlockCourseTest {
    @Test
    void standingHeights() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5);
        w.setPhysics(1, 1, 0, Shapes.BOTTOM_SLAB).setPhysics(2, 1, 0, Shapes.CARPET).setPhysics(3, 1, 0, Shapes.SOUL_SAND)
            .setPhysics(4, 0, 0, Shapes.FENCE_X);
        assertEquals(1.0, Standing.feet(w, 0, 1, 0), 1e-9);
        assertEquals(1.5, Standing.feet(w, 1, 1, 0), 1e-9);
        assertEquals(1.0625, Standing.feet(w, 2, 1, 0), 1e-9);
        // taller than half a block: stood on from the cell above, like a full block
        assertEquals(1.875, Standing.feet(w, 3, 2, 0), 1e-9);
        assertTrue(Double.isNaN(Standing.feet(w, 3, 1, 0)));
        assertEquals(1.5, Standing.feet(w, 4, 1, 0), 1e-9, "on top of a fence");
        assertFalse(Standing.standable(w, 1, 2, 0), "not floating above the slab");
    }

    @Test
    void walksUpASlabStaircase() {
        // floor at y=0; slab, block, slab on block, block on block, ... rising by half a block per step
        GridWorld w = GridWorld.flatFloor(-3, -2, 20, 2);
        for (int i = 0; i < 8; i++) {
            int x = 2 + i, full = i / 2;
            if (full > 0) w.fill(x, 1, -2, x, full, 2, BlockKind.SOLID);
            if (i % 2 == 0) for (int z = -2; z <= 2; z++) w.setPhysics(x, 1 + full, z, Shapes.BOTTOM_SLAB);
            else w.fill(x, 1 + full, -2, x, 1 + full, 2, BlockKind.SOLID);
        }
        w.fill(10, 1, -2, 14, 4, 2, BlockKind.SOLID);
        CourseTest.Run r = drive(w, 0, 1, 0, 13, 5, 0, 400);
        assertArrives(r, 120); // it may sprint-jump up the steps, but walking them never requires a jump
    }

    @Test
    void climbsStairsAndCrossesCarpetAndSoulSand() {
        GridWorld w = GridWorld.flatFloor(-3, -2, 24, 2);
        for (int z = -2; z <= 2; z++) {
            w.setPhysics(3, 1, z, Shapes.STAIRS_EAST);
            for (int x = 6; x <= 9; x++) w.setPhysics(x, 2, z, Shapes.CARPET);
            for (int x = 10; x <= 14; x++) w.setPhysics(x, 1, z, Shapes.SOUL_SAND);
        }
        w.fill(4, 1, -2, 9, 1, 2, BlockKind.SOLID); // the top of the stairs leads onto a raised carpeted floor
        CourseTest.Run r = drive(w, 0, 1, 0, 20, 1, 0, 600);
        assertArrives(r, 200);
    }

    @Test
    void goesAroundAFenceThroughItsGap() {
        GridWorld w = GridWorld.flatFloor(-3, -8, 12, 8);
        for (int z = -8; z <= 8; z++) if (z != 6) w.setPhysics(5, 1, z, Shapes.FENCE_Z);
        CourseTest.Run r = drive(w, 0, 1, 0, 10, 1, 0, 600);
        assertArrives(r, 200);
    }
}
