package dev.lazuli.core.control;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import org.junit.jupiter.api.Test;

import static dev.lazuli.core.control.CourseTest.assertArrives;
import static dev.lazuli.core.control.CourseTest.drive;

/** Ladders and vines, closed loop (see {@link CourseTest}). */
class LadderCourseTest {
    /** A ladder on the north face of a wall at z = 1 (its thin box against the wall, on the cell's +z side). */
    private static final BlockPhysics LADDER = BlockPhysics.intern(new double[]{0, 0, 0.8125, 1, 1, 1}, 0.6f, 1, 1,
        BlockPhysics.CLIMBABLE);
    private static final BlockPhysics VINE = BlockPhysics.intern(new double[0], 0.6f, 1, 1,
        BlockPhysics.CLIMBABLE | BlockPhysics.REPLACEABLE);

    /** Floor at y = 0; a wall from z = 1 on, {@code height} high, with a ladder (or vine) up its face at x = 0. */
    private static GridWorld wall(int height, BlockPhysics climb) {
        GridWorld w = GridWorld.flatFloor(-6, -8, 6, 6);
        w.fill(-6, 1, 1, 6, height, 6, BlockKind.SOLID);
        for (int y = 1; y <= height; y++) w.setPhysics(0, y, 0, climb);
        return w;
    }

    @Test
    void climbsALadderUpAWall() {
        assertArrives(drive(wall(6, LADDER), 0, 1, -5, 0, 7, 3, 400), 400);
    }

    @Test
    void climbsDownALadder() {
        assertArrives(drive(wall(6, LADDER), 0, 7, 3, 0, 1, -5, 400), 400);
    }

    @Test
    void climbsUpThroughAHoleInTheCeiling() {
        // a room 4 high with a 1x1 hole in its ceiling, ladder up the wall below the hole
        GridWorld w = GridWorld.flatFloor(-6, -6, 6, 6);
        w.fill(-6, 5, -6, 6, 5, 6, BlockKind.SOLID).set(0, 5, 0, BlockKind.AIR);
        w.fill(-6, 1, 1, 6, 4, 1, BlockKind.SOLID);
        for (int y = 1; y <= 5; y++) w.setPhysics(0, y, 0, LADDER);
        assertArrives(drive(w, 0, 1, -4, 3, 6, 3, 400), 400);
        assertArrives(drive(w, 3, 6, 3, 0, 1, -4, 400), 400);
    }

    @Test
    void climbsAVine() {
        assertArrives(drive(wall(5, VINE), 2, 1, -4, 0, 6, 3, 400), 400);
    }
}
