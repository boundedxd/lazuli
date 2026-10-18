package dev.lazuli.core.session;

import dev.lazuli.core.control.InteractionCourseTest;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Digging down to a far target below and to the side: the sideways part is walked on the surface, never tunnelled at depth. */
class DigDownStraightTest {
    private static GridWorld rock() {
        return GridWorld.flatFloor(-20, -20, 40, 40).fill(-20, -80, -20, 40, 0, 40, BlockKind.SOLID);
    }

    @Test
    void sidewaysIsWalkedAtTheSurfaceNotDugAtDepth() {
        var r = InteractionCourseTest.drive(rock(), new Interaction(true, false, 0, p -> 4), 0, false, 0, 1, 0,
            new GoalAdjacent(10, -36, 6), 10, 6, 8000);
        assertTrue(r.arrived(), r.log());
        // 37 down is the least; a tunnel of ten blocks at the bottom would add twice that many breaks
        assertTrue(r.breaks() <= 40, "breaks " + r.breaks());
    }

    @Test
    void sidewaysForALongWayDown() {
        var r = InteractionCourseTest.drive(rock(), new Interaction(true, false, 0, p -> 4), 0, false, 0, 1, 0,
            new GoalAdjacent(18, -36, -12), 18, -12, 12000);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.breaks() <= 40, "breaks " + r.breaks());
    }
}
