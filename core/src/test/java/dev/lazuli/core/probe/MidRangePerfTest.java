package dev.lazuli.core.probe;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.physics.Trace;
import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.planner.PlanResult;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongConsumer;

class MidRangePerfTest {
    @Test
    void plan() throws Exception {
        Path f = Path.of("../mod/run/config/lazuli/traces/mid_range_test.trace");
        if (!Files.exists(f)) return;
        Trace t;
        try (var r = Files.newBufferedReader(f)) { t = Trace.read(r); }
        long start = Pos.pack(132, 113, 215);
        long[][] goals = {{140, 117, 234}, {151, 128, 272}, {154, 122, 301}};
        for (boolean dig : new boolean[]{false, true}) for (boolean parkour : new boolean[]{false, true}) for (long[] g : goals) {
            long goal = Pos.pack((int) g[0], (int) g[1], (int) g[2]);
            Interaction in = dig ? new Interaction(true, true, 64, p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4))) : Interaction.NONE;
            MoveGraph mg = new MoveGraph(t, 3, true, 1.2, parkour, in);
            long[] ct = new long[3];
            Graph cg = new Graph() {
                public boolean isNode(long u) { return mg.isNode(u); }
                public void successors(long u, LongConsumer o) { ct[2]++; mg.successors(u, o); }
                public void predecessors(long v, LongConsumer o) { ct[0]++; mg.predecessors(v, o); }
                public double optimisticCost(long u, long v) { ct[1]++; return mg.optimisticCost(u, v); }
                public double evaluate(long u, long v) { return mg.evaluate(u, v); }
                public void invalidate() { mg.invalidate(); }
                public double heuristic(long a, long b) { return mg.heuristic(a, b); }
                public int horizontalReach() { return mg.horizontalReach(); }
                public int verticalReach() { return mg.verticalReach(); }
            };
            LazyDStarLite p = new LazyDStarLite(cg, q -> q == goal, new long[]{goal}, start);
            long t0 = System.nanoTime();
            PlanResult r = p.plan(32, 2_000_000, 512, 30_000_000_000L);
            System.out.printf("PERF dig=%s parkour=%s goal=%s: %s %d ms path %d expansions %d recomputes %d costs %d%n", dig, parkour,
                Pos.toString(goal), r.status(), (System.nanoTime() - t0) / 1_000_000, r.path().size(), ct[0], ct[2], ct[1]);
        }
    }
}
