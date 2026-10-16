package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Hazards;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HazardsTest {
    private static PathingSession session(GridWorld w, Settings s) {
        return new PathingSession(w, () -> -64, () -> 319, s, Runnable::run, m -> {});
    }

    private static double nearest(List<Long> path, int x, int z) {
        double best = Double.MAX_VALUE;
        for (long p : path) best = Math.min(best, Math.hypot(Pos.x(p) - x, Pos.z(p) - z));
        return best;
    }

    @Test
    void costIsHighestAtTheMobAndZeroOutsideItsRadius() {
        Hazards h = Hazards.of(List.of(new Hazards.Hazard(0, 1, 0, 5, 40)));
        assertEquals(40, h.cost(0, 1, 0), 1e-9);
        assertTrue(h.cost(3, 1, 0) > 0 && h.cost(3, 1, 0) < 40);
        assertEquals(0, h.cost(6, 1, 0));
        assertEquals(0, Hazards.NONE.cost(0, 1, 0));
    }

    @Test
    void pathGoesAroundAMob() {
        GridWorld w = GridWorld.flatFloor(0, -15, 30, 15);
        PathingSession s = session(w, new Settings());
        s.setHazards(Hazards.of(List.of(new Hazards.Hazard(15, 1, 0, 6, 60))));
        s.start(new GoalBlock(30, 1, 0), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        assertTrue(nearest(s.snapshot().path(), 15, 0) >= 4, "walked right past the mob");
    }

    @Test
    void aMobShowingUpMidTripReroutes() {
        GridWorld w = GridWorld.flatFloor(0, -15, 30, 15);
        PathingSession s = session(w, new Settings());
        s.start(new GoalBlock(30, 1, 0), false, Pos.pack(0, 1, 0));
        assertTrue(nearest(s.snapshot().path(), 15, 0) < 2);
        s.setHazards(Hazards.of(List.of(new Hazards.Hazard(15, 1, 0, 6, 60))));
        assertTrue(nearest(s.snapshot().path(), 15, 0) >= 4, "didn't reroute");
        s.setHazards(Hazards.NONE);
        assertTrue(nearest(s.snapshot().path(), 15, 0) < 2, "kept the detour after it left");
    }

    @Test
    void offWhenAvoidMobsIsOff() {
        GridWorld w = GridWorld.flatFloor(0, -15, 30, 15);
        Settings st = new Settings();
        st.avoidMobs.set(false);
        PathingSession s = session(w, st);
        s.setHazards(Hazards.of(List.of(new Hazards.Hazard(15, 1, 0, 6, 60))));
        s.start(new GoalBlock(30, 1, 0), false, Pos.pack(0, 1, 0));
        assertTrue(nearest(s.snapshot().path(), 15, 0) < 2);
    }

    @Test
    void keepsAStepAwayFromLava() {
        // lava along z=1 for most of the way; walking right beside it at z=0 is avoided
        GridWorld w = GridWorld.flatFloor(0, -5, 30, 5).fill(3, 0, 1, 27, 0, 1, BlockKind.DANGER);
        PathingSession s = session(w, new Settings());
        s.start(new GoalBlock(30, 1, 0), false, Pos.pack(0, 1, 0));
        for (long p : s.snapshot().path()) {
            if (Pos.x(p) >= 4 && Pos.x(p) <= 26) assertNotEquals(0, Pos.z(p), "walked along the lava at " + Pos.x(p));
        }
    }
}
