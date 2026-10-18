package dev.lazuli.core.session;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalAny;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #mine / #getitem trips (any of several blocks) far off: planned in segments like a far #goto, not in one search
 * that floods the rock until it gives up (which got the targets blacklisted and the job "searching" for good).
 */
class FarTargetTest {
    private static final Interaction DIG = new Interaction(true, true, 64, p -> 4);

    @Test
    void treesAtTheSurfaceFromADeepCave() {
        // solid ground from y -40 up to 60, a cave pocket at -30, trees (logs) on the surface well off to the side
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-20, -40, -20, 140, 60, 20, BlockKind.SOLID)
            .fill(-2, -30, -2, 2, -28, 2, BlockKind.AIR);
        List<Goal> logs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            w.fill(120 + i * 3, 61, 0, 120 + i * 3, 64, 0, BlockKind.SOLID);
            logs.add(new GoalAdjacent(120 + i * 3, 62, 0));
        }
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> DIG);
        long t0 = System.nanoTime();
        s.start(new GoalAny(logs, "log"), false, Pos.pack(0, -30, 0), true);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs + " after " + ms + " ms");
        assertTrue(ms < 6000, "took " + ms + " ms to get a first path");
    }

    @Test
    void anyOfFarBlocksOnOpenGroundIsPlannedInSegments() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 400, 10);
        List<Goal> ores = List.of(new GoalAdjacent(380, 1, 0), new GoalAdjacent(385, 1, 3));
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, m -> {});
        s.start(new GoalAny(ores, "ore"), false, Pos.pack(0, 1, 0), true);
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        long end = s.snapshot().path().get(s.snapshot().path().size() - 1);
        assertTrue(Pos.x(end) <= PathingSession.SEGMENT + PathingSession.HANDOFF, "planned the whole way at once: ends at " + Pos.toString(end));
    }
}
