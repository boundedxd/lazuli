package dev.lazuli.core.probe;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RockProbeTest {
    private static final Interaction DIG = new Interaction(true, true, 64, p -> 4);

    private static void run(String name, Goal g, int sy) {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-20, -40, -20, 140, 60, 20, BlockKind.SOLID)
            .fill(-2, sy, -2, 2, sy + 2, 2, BlockKind.AIR);
        w.fill(120, 61, 0, 120, 64, 0, BlockKind.SOLID);
        w.fill(0, 61, 0, 0, 64, 0, BlockKind.SOLID);
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> DIG);
        long t0 = System.nanoTime();
        s.start(g, false, Pos.pack(0, sy, 0), false);
        System.out.println("PROBE " + name + " " + s.snapshot().state() + " " + (System.nanoTime() - t0) / 1_000_000 + " ms len "
            + s.snapshot().path().size() + " " + msgs);
    }

    @Test
    @org.junit.jupiter.api.Tag("benchmark")
    void probe() {
        run("side120-same-y", new GoalBlock(120, -30, 0), -30);
        run("up30-block", new GoalBlock(0, 0, 0), -30);
        run("log-above-near", new GoalAdjacent(0, 62, 0), 30);
        run("log-above-far", new GoalAdjacent(0, 62, 0), -30);
        run("side40-up40", new GoalBlock(40, 10, 0), -30);
        run("trees-far", new GoalAdjacent(120, 62, 0), -30);
    }
}
