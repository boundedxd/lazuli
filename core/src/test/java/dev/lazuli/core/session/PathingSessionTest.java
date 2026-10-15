package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalAny;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathingSessionTest {
    private final List<String> msgs = new ArrayList<>();

    private PathingSession session(BlockView w) {
        return new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, m -> msgs.add(dev.lazuli.core.chat.Chat.strip(m)));
    }

    @Test
    void findsPathOnFlatGround() {
        PathingSession s = session(GridWorld.flatFloor(0, 0, 20, 20));
        s.start(new GoalBlock(15, 1, 3), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        List<Long> path = s.snapshot().path();
        assertEquals(Pos.pack(15, 1, 3), path.get(path.size() - 1));
    }

    @Test
    void goalInUnknownTerrainIsPlanned() {
        GridWorld w = new GridWorld(BlockKind.UNKNOWN)
            .fill(-2, 0, -2, 2, 0, 2, BlockKind.SOLID).fill(-2, 1, -2, 2, 3, 2, BlockKind.AIR);
        PathingSession s = session(w);
        s.start(new GoalBlock(200, 1, 0), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
    }

    @Test
    void blockOnPathTriggersRepairAroundIt() {
        GridWorld w = GridWorld.flatFloor(0, -5, 10, 5);
        PathingSession s = session(w);
        s.start(new GoalBlock(10, 1, 0), false, Pos.pack(0, 1, 0));
        w.fill(5, 1, -5, 5, 2, 4, BlockKind.SOLID);
        s.regionChanged(5, 1, -5, 5, 2, 4);
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        assertTrue(s.snapshot().path().contains(Pos.pack(5, 1, 5)),
            s.snapshot().path().stream().map(Pos::toString).toList().toString());
    }

    @Test
    void enclosedPlayerUsesFallback() {
        GridWorld w = GridWorld.flatFloor(0, 0, 4, 4)
            .fill(-1, 1, -1, 5, 3, -1, BlockKind.SOLID).fill(-1, 1, 5, 5, 3, 5, BlockKind.SOLID)
            .fill(-1, 1, -1, -1, 3, 5, BlockKind.SOLID).fill(5, 1, -1, 5, 3, 5, BlockKind.SOLID)
            .fill(-1, 4, -1, 5, 4, 5, BlockKind.SOLID);
        PathingSession s = session(w);
        s.start(new GoalBlock(30, 1, 2), true, Pos.pack(0, 1, 0));
        assertEquals(new GoalBlock(4, 1, 2), s.snapshot().goal());
        assertTrue(msgs.stream().anyMatch(m -> m.contains("nearest safe spot")));
    }

    @Test
    void unreachableWithoutFallbackFails() {
        PathingSession s = session(GridWorld.flatFloor(0, 0, 4, 4));
        s.start(new GoalBlock(30, 1, 2), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.FAILED, s.snapshot().state());
        assertTrue(msgs.contains("Goal unreachable"));
    }

    @Test
    void arrivesWhenPlayerReachesGoal() {
        PathingSession s = session(GridWorld.flatFloor(0, 0, 5, 5));
        s.start(new GoalBlock(2, 1, 0), false, Pos.pack(0, 1, 0));
        s.playerMoved(Pos.pack(1, 1, 0));
        s.playerMoved(Pos.pack(2, 1, 0));
        assertEquals(PathingSession.State.ARRIVED, s.snapshot().state());
    }

    /** A far goal is planned one segment at a time; walking the segments gets there. */
    @Test
    void farGoalIsReachedInSegments() {
        GridWorld w = GridWorld.flatFloor(-10, -40, 400, 40).fill(150, 1, -40, 150, 3, 10, BlockKind.SOLID);
        PathingSession s = session(w);
        s.start(new GoalBlock(380, 1, 5), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
        List<Long> first = s.snapshot().path();
        assertTrue(Pos.x(first.get(first.size() - 1)) <= PathingSession.SEGMENT + 5, "first segment ends near the player");
        for (int step = 0; step < 200 && s.snapshot().state() == PathingSession.State.PATHING; step++) {
            List<Long> path = s.snapshot().path();
            s.playerMoved(path.get(Math.min(path.size() - 1, 8)));
        }
        assertEquals(PathingSession.State.ARRIVED, s.snapshot().state(), msgs.toString());
    }

    @Test
    void quietMiningTripEndsNextToTheNearerOre() {
        // ores in a wall at x=10..12: one in its face, one buried 2 deep; no breaking allowed
        GridWorld w = GridWorld.flatFloor(0, 0, 20, 20).fill(10, 1, 0, 12, 3, 20, BlockKind.SOLID);
        PathingSession s = session(w);
        GoalAny g = new GoalAny(List.of(new GoalAdjacent(12, 1, 5), new GoalAdjacent(10, 2, 15)), "ore");
        s.start(g, false, Pos.pack(2, 1, 2), true);
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
        long end = s.snapshot().path().get(s.snapshot().path().size() - 1);
        assertEquals(Pos.pack(9, 1, 15), end, Pos.toString(end));
        assertTrue(msgs.isEmpty(), msgs.toString());
    }

    @Test
    void deepGoalUnderSolidGroundIsDugToInSegments() {
        // diamonds from the surface: 120 blocks of stone straight down; searched for whole, that floods the rock
        GridWorld w = new GridWorld(BlockKind.SOLID).fill(-20, 64, -20, 20, 68, 20, BlockKind.AIR);
        dev.lazuli.core.move.Interaction in = new dev.lazuli.core.move.Interaction(true, true, 64, p -> 8);
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> in);
        long t0 = System.nanoTime();
        s.start(new GoalAny(List.of(new GoalAdjacent(10, -56, 5), new GoalAdjacent(-20, -58, 30)), "diamonds"), false,
            Pos.pack(0, 64, 0), true);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
        assertTrue(ms < 2000, "took " + ms + " ms");
        long end = s.snapshot().path().get(s.snapshot().path().size() - 1);
        assertTrue(Pos.y(end) < 50, "heads down: " + Pos.toString(end));
    }

    @Test
    void killRecoversFromADeadWorker() {
        // a worker that silently lost its tasks (a crash, a hang): the session would wait for them forever
        GridWorld w = GridWorld.flatFloor(-10, -10, 20, 10);
        boolean[] dead = {true};
        java.util.concurrent.Executor worker = r -> { if (!dead[0]) r.run(); };
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), worker, msgs::add);
        s.start(new GoalBlock(10, 1, 0), false, Pos.pack(0, 1, 0));
        s.regionChanged(5, 1, 0, 5, 1, 0);
        assertTrue(s.planning());
        dead[0] = false; // the mod swaps in a fresh thread, then kills
        s.kill();
        assertFalse(s.planning());
        assertEquals(PathingSession.State.IDLE, s.snapshot().state());
        s.start(new GoalBlock(10, 1, 0), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        // block changes are picked up again (the lost batch had left them "already queued")
        w.fill(5, 1, -10, 5, 3, 10, BlockKind.SOLID);
        s.regionChanged(5, 1, -10, 5, 3, 10);
        assertNotEquals(PathingSession.State.PATHING, s.snapshot().state(), "replanned around the new wall");
    }

    @Test
    void stopIsImmediateEvenWhileTheWorkerIsSearching() throws Exception {
        // a huge open world and a goal walled off: the search runs until its time budget
        GridWorld w = GridWorld.flatFloor(-300, -300, 300, 300).fill(250, 1, 250, 252, 4, 252, BlockKind.SOLID);
        var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), pool, msgs::add);
            s.start(new GoalBlock(251, 1, 251), false, Pos.pack(-250, 1, -250));
            Thread.sleep(300); // well into the search
            long t0 = System.nanoTime();
            s.stop();
            assertEquals(PathingSession.State.IDLE, s.snapshot().state());
            pool.submit(() -> {}).get(); // the worker is free again
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 500, "worker busy for " + ms + " ms after stop");
            Thread.sleep(100);
            assertEquals(PathingSession.State.IDLE, s.snapshot().state(), "nothing from the old trip is published");
        } finally {
            pool.shutdownNow();
        }
    }
}
