package dev.lazuli.core.control;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import static dev.lazuli.core.control.InteractionCourseTest.Run;
import static org.junit.jupiter.api.Assertions.*;

/** Swimming up through water columns: still shafts, waterfalls, and the limits (air, lava). */
class WaterRiseCourseTest {
    private static final Interaction NONE = new Interaction(false, false, 0, p -> Double.POSITIVE_INFINITY);
    /** Ground at y=0 (stand at y=1), a pool at x 3..8, z -2..6, 4 deep, and a solid tower x 8..12 of the given height. */
    private static GridWorld world(int height) {
        return GridWorld.flatFloor(-5, -5, 14, 8)
            .fill(3, -4, -2, 8, -1, 6, BlockKind.SOLID)
            .fill(3, -3, -2, 8, 0, 6, BlockKind.WATER)
            .fill(8, 1, -2, 12, height, 6, BlockKind.SOLID);
    }

    private static Run go(GridWorld w, int height, int gx, int gz, int ticks) {
        return InteractionCourseTest.drive(w, NONE, 0, 1, 2, gx, height + 1, gz, ticks);
    }

    @Test
    void swimsUpAStillShaftToALedge() {
        GridWorld w = world(8).fill(8, 1, 0, 8, 8, 0, BlockKind.WATER).fill(7, 2, 0, 7, 8, 0, BlockKind.SOLID);
        Run r = go(w, 8, 10, 0, 1500);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.lowestY() > -3.5, "sank to " + r.lowestY() + System.lineSeparator() + r.log());
    }

    @Test
    void swimsUpAWaterfallDownACliffFace() {
        // open on the pool's side: the water is a column against the cliff, air in front of it
        GridWorld w = world(8).fill(8, 1, 0, 8, 8, 0, BlockKind.WATER).fill(8, 1, -1, 8, 8, -1, BlockKind.SOLID)
            .fill(8, 1, 1, 8, 8, 1, BlockKind.SOLID).fill(9, 1, 0, 12, 8, 0, BlockKind.SOLID);
        Run r = go(w, 8, 10, 1, 1500);
        assertTrue(r.arrived(), r.log());
    }

    @Test
    void refusesAColumnDeeperThanItHasAir() {
        GridWorld w = world(30).fill(8, 1, 0, 8, 30, 0, BlockKind.WATER);
        Run r = go(w, 30, 10, 0, 600);
        assertFalse(r.arrived(), "swam up 30 blocks of water");
        assertTrue(r.lowestY() > -3.5);
    }

    @Test
    void doesNotSwimUpWhenSwitchedOff() {
        GridWorld w = world(8).fill(8, 1, 0, 8, 8, 0, BlockKind.WATER);
        MoveGraph g = new MoveGraph(w, 3, true, 1, false);
        assertFalse(g.deep(Pos.pack(8, 3, 0)), "off by default in the bare graph");
        MoveGraph on = new MoveGraph(w, 3, true, 1, false).withSwimUp(20);
        assertTrue(on.deep(Pos.pack(8, 3, 0)));
        assertFalse(new MoveGraph(w, 3, true, 1, false).withSwimUp(4).deep(Pos.pack(8, 3, 0)), "6 deep with 4 allowed");
    }

    @Test
    void prefersTheShaftWithoutLavaBesideIt() {
        // two shafts, the same distance away: one has lava in its wall
        GridWorld w = world(8).fill(8, 1, 0, 8, 8, 0, BlockKind.WATER).fill(8, 1, 4, 8, 8, 4, BlockKind.WATER)
            .fill(8, 1, -1, 8, 8, -1, BlockKind.DANGER);
        MoveGraph g = new MoveGraph(w, 3, true, 1, false).withSwimUp(20)
            .withHazards(dev.lazuli.core.move.Hazards.NONE, 6);
        double lava = g.optimisticCost(Pos.pack(8, 3, 0), Pos.pack(8, 4, 0));
        double clean = g.optimisticCost(Pos.pack(8, 3, 4), Pos.pack(8, 4, 4));
        assertTrue(lava > clean, lava + " vs " + clean);
        Run r = InteractionCourseTest.drive(w, NONE, 0, 1, 2, 10, 9, 2, 2500);
        assertTrue(r.arrived(), r.log());
    }
}
