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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

class HoleProbeTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 5})
    void fromHole(int depth) {
        GridWorld w = new GridWorld(BlockKind.SOLID).fill(-20, 64, -20, 20, 68, 20, BlockKind.AIR);
        if (depth > 0) w.fill(0, 64 - depth, 0, 0, 63, 0, BlockKind.AIR);
        int[][] ores = {{12, 58, 5}, {-9, 55, 14}, {15, 52, -11}, {6, 60, -15}};
        List<Goal> gs = new ArrayList<>();
        for (int[] o : ores) gs.add(new GoalAdjacent(o[0], o[1], o[2]));
        Interaction in = new Interaction(true, true, 64, p -> 8);
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> in);
        long t0 = System.nanoTime();
        s.start(new GoalAny(gs, "dia"), false, Pos.pack(0, 64 - depth, 0), true);
        System.out.println("PROBE depth=" + depth + " " + s.snapshot().state() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms, path "
            + s.snapshot().path().size() + " " + msgs);
    }
}
