package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Hazards;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The planner "freezing" until #kill: block and mob updates queued repairs on the one planner thread faster than it
 * could do them (each was seconds: every predecessor of every touched vertex recomputed from all its ~700 parkour
 * successors), so the next trip waited behind them for good.
 */
class PlannerBacklogTest {
    /** Runs tasks only when asked, like a busy planner thread would. */
    private static final class Queue implements java.util.concurrent.Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        public void execute(Runnable r) {
            tasks.add(r);
        }

        void drain() {
            for (Runnable r; (r = tasks.poll()) != null; ) r.run();
        }
    }

    private static final Interaction DIG = new Interaction(true, true, 64, p -> 4);

    @Test
    void mobUpdatesNeverPileUpOnThePlanner() {
        Queue q = new Queue();
        GridWorld w = GridWorld.flatFloor(0, 0, 40, 40);
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), q, m -> {});
        s.start(new GoalBlock(35, 1, 35), false, Pos.pack(0, 1, 0), true);
        q.drain();
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        // a zombie walking along beside the path, a new position every 10 ticks, the planner busy meanwhile
        for (int i = 0; i < 30; i++) s.setHazards(Hazards.of(List.of(new Hazards.Hazard(10 + i % 5, 1, 12, 6, 40))));
        assertTrue(q.tasks.size() <= 1, q.tasks.size() + " updates queued for one zombie");
        q.drain();
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
    }

    /** The quick mob repair finds the same cost of path as planning from scratch with the mobs where they are now. */
    @Test
    void mobRepairsMatchAFreshPlan() {
        GridWorld w = GridWorld.flatFloor(0, -15, 40, 15);
        Settings st = new Settings();
        st.heuristicWeight.set(1.0); // optimal paths, so the two can be compared
        java.util.Random r = new java.util.Random(7);
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, st, Runnable::run, m -> {});
        s.start(new GoalBlock(40, 1, 0), false, Pos.pack(0, 1, 0), true);
        for (int step = 0; step < 25; step++) {
            List<Hazards.Hazard> mobs = new java.util.ArrayList<>();
            for (int k = r.nextInt(3); k >= 0; k--) mobs.add(new Hazards.Hazard(5 + r.nextInt(30), 1, -6 + r.nextInt(13), 3 + r.nextInt(5), 20 + r.nextInt(60)));
            Hazards h = Hazards.of(mobs);
            s.setHazards(h);
            PathingSession fresh = new PathingSession(w, () -> -64, () -> 319, st, Runnable::run, m -> {});
            fresh.setHazards(h);
            fresh.start(new GoalBlock(40, 1, 0), false, Pos.pack(0, 1, 0), true);
            assertEquals(cost(w, h, fresh.snapshot().path()), cost(w, h, s.snapshot().path()), 1e-6, "step " + step);
        }
    }

    private static double cost(GridWorld w, Hazards h, List<Long> path) {
        var g = new dev.lazuli.core.move.MoveGraph(w, 3, true, 1.0, true).withHazards(h, 6);
        double c = 0;
        for (int i = 0; i + 1 < path.size(); i++) c += g.evaluate(path.get(i), path.get(i + 1));
        return c;
    }

    @Test
    void repairingAMinedVeinCostsLessThanPlanningTheTrip() {
        // digging through stone (every cell a node) with parkour on: the case that froze after a vein
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-10, -20, -10, 90, 0, 30, BlockKind.SOLID);
        Settings settings = new Settings();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, settings, Runnable::run, m -> {}, () -> DIG);
        long t0 = System.nanoTime();
        s.start(new GoalBlock(70, -12, 10), false, Pos.pack(0, 1, 10), true);
        long plan = System.nanoTime() - t0;
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        // eight ore blocks mined next to the path, one update each
        t0 = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            int x = 20 + i % 3, y = -5 - i / 3;
            w.fill(x, y, 12, x, y, 12, BlockKind.AIR);
            s.regionChanged(x, y, 12, x, y, 12);
        }
        long repair = System.nanoTime() - t0;
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        // it was ~12x the plan; a few times is fine on a loaded machine
        assertTrue(repair < 3 * Math.max(plan, 100_000_000L), "a vein's repairs took " + repair / 1_000_000 + " ms, the whole plan "
            + plan / 1_000_000 + " ms");
    }
}
