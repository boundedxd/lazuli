package dev.lazuli.core.probe;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalAny;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class DeepMineProbeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {8, 16, 24, 32, 48})
    void depth(int d) {
        GridWorld w = new GridWorld(BlockKind.SOLID).fill(-20, 64, -20, 20, 68, 20, BlockKind.AIR);
        Interaction in = new Interaction(true, true, 64, p -> 8);
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> in);
        long t0 = System.nanoTime();
        s.start(new GoalAdjacent(10, 64 - d, 5), false, Pos.pack(0, 64, 0), true);
        System.out.println("PROBE d=" + d + " " + s.snapshot().state() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, path "
            + s.snapshot().path().size() + " " + msgs);
    }

    @Test
    @org.junit.jupiter.api.Disabled
    void surfaceToDiamonds() {
        GridWorld w = new GridWorld(BlockKind.SOLID).fill(-50, 64, -50, 50, 68, 50, BlockKind.AIR);
        int[][] ores = {{20, -58, 10}, {-30, -55, 25}, {40, -59, -35}};
        List<Goal> gs = new ArrayList<>();
        for (int[] o : ores) gs.add(new GoalAdjacent(o[0], o[1], o[2]));
        Interaction in = new Interaction(true, true, 64, p -> 8);
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> in);
        long t0 = System.nanoTime();
        s.start(new GoalAny(gs, "diamonds"), false, Pos.pack(0, 64, 0), true);
        System.out.println("PROBE " + s.snapshot().state() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, path "
            + s.snapshot().path().size() + " " + msgs);
        List<Long> p = s.snapshot().path();
        if (!p.isEmpty()) System.out.println("PROBE end " + Pos.toString(p.get(p.size() - 1)));
    }
}
