package dev.lazuli.core.session;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The game thread stops and starts trips while the planner thread is in the middle of something. The worker here is a
 * queue run on the test thread, and the "game thread" acts from inside the world reads of a running task, which is
 * exactly where the real one can cut in.
 */
class PathingSessionRaceTest {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean draining;
    private Runnable onRead = () -> {};

    private void drain() {
        if (draining) return;
        draining = true;
        try {
            for (Runnable r; (r = queue.poll()) != null; ) {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    throw e;
                }
            }
        } finally {
            draining = false;
        }
    }

    private BlockView watched(GridWorld w) {
        return new BlockView() {
            @Override
            public BlockKind get(int x, int y, int z) {
                onRead.run();
                return w.get(x, y, z);
            }

            @Override
            public BlockPhysics physics(int x, int y, int z) {
                onRead.run();
                return w.physics(x, y, z);
            }
        };
    }

    private static Goal goal(Random r) {
        return switch (r.nextInt(4)) {
            case 0 -> new GoalBlock(10 + r.nextInt(20), 1, r.nextInt(20)); // near
            case 1 -> new GoalBlock(150 + r.nextInt(50), 1, r.nextInt(20)); // far: planned in segments
            case 2 -> new GoalBlock(60 + r.nextInt(30), 1, r.nextInt(20));
            default -> new GoalBlock(r.nextInt(30), 1, r.nextInt(30));
        };
    }

    @Test
    void aStopOrStartThatCutsInMidSearchLeavesTheSessionConsistent() {
        for (int seed = 0; seed < 150; seed++) {
            queue.clear();
            Random r = new Random(seed);
            GridWorld w = GridWorld.flatFloor(-20, -20, 260, 60).fill(35, 1, 35, 45, 3, 45, BlockKind.SOLID)
                .fill(38, 1, 38, 42, 3, 42, BlockKind.AIR).fill(38, 1, 38, 42, 3, 42, BlockKind.SOLID);
            PathingSession s = new PathingSession(watched(w), () -> -64, () -> 319, new Settings(), q -> queue.add(q), m -> {});
            long lastTrip = -1;
            boolean lastWasStart = false;
            int steps = 1 + r.nextInt(4);
            for (int i = 0; i < steps; i++) {
                int preemptAt = 1 + r.nextInt(3000);
                int[] reads = {0};
                int action = r.nextInt(5);
                long[] tripBox = {lastTrip};
                boolean[] startBox = {lastWasStart};
                onRead = () -> {
                    if (++reads[0] != preemptAt) return;
                    switch (action) {
                        case 0 -> { s.stop(true); startBox[0] = false; }
                        case 1 -> { tripBox[0] = s.start(goal(r), r.nextBoolean(), Pos.pack(r.nextInt(10), 1, r.nextInt(10)), true); startBox[0] = true; }
                        case 2 -> s.playerMoved(Pos.pack(r.nextInt(20), 1, r.nextInt(20)));
                        case 3 -> s.regionChanged(r.nextInt(20), 0, r.nextInt(20), 20 + r.nextInt(20), 3, 20 + r.nextInt(20));
                        default -> s.edgeFailed(Pos.pack(r.nextInt(20), 1, r.nextInt(20)), Pos.pack(r.nextInt(20), 1, r.nextInt(20)));
                    }
                };
                // something is running when the cut comes
                tripBox[0] = s.start(goal(r), r.nextBoolean(), Pos.pack(0, 1, 0), true);
                startBox[0] = true;
                drain();
                onRead = () -> {};
                lastTrip = tripBox[0];
                lastWasStart = startBox[0];
            }
            // the last word is a stop or a start; then everything settles
            long finalTrip = -1;
            boolean stop = r.nextBoolean();
            if (stop) s.stop(true);
            else finalTrip = s.start(goal(r), false, Pos.pack(0, 1, 0), true);
            drain();
            String why = "seed " + seed + (stop ? " stop" : " start") + " state " + s.snapshot().state();
            assertFalse(s.planning(), "still 'planning' after everything settled: " + why);
            if (stop) {
                assertEquals(PathingSession.State.IDLE, s.snapshot().state(), why);
            } else {
                assertNotEquals(PathingSession.State.IDLE, s.snapshot().state(), why);
                assertEquals(finalTrip, s.snapshot().trip(), why);
            }
        }
    }
}
