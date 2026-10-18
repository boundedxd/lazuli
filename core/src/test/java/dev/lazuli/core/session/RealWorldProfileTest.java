package dev.lazuli.core.session;

import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.planner.PlanResult;
import dev.lazuli.core.testutil.AnvilWorld;
import dev.lazuli.core.world.Pos;
import dev.lazuli.core.world.WorldCache;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.function.LongConsumer;

/** One direct plan on the saved world with counters, for profiling. */
@Tag("benchmark")
class RealWorldProfileTest {
    @Test
    void profile() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(RealWorldBenchTest.WORLD.resolve("region")));
        double[] pp = AnvilWorld.playerPos(RealWorldBenchTest.WORLD);
        int pcx = (int) Math.floor(pp[0]) >> 4, pcz = (int) Math.floor(pp[2]) >> 4;
        WorldCache w = AnvilWorld.load(RealWorldBenchTest.WORLD, pcx - 12, pcx + 12, pcz - 12, pcz + 12);
        long start = Standing.nodeUnder(w, pp[0], pp[1], pp[2]);
        int gx = Pos.x(start) + 40, gz = Pos.z(start) + 30, gy = 319;
        while (!Standing.standable(w, gx, gy, gz)) gy--;
        long goal = Pos.pack(gx, gy, gz);
        for (boolean build : new boolean[]{false, true}) for (double hw : new double[]{1.2, 2.0}) {
            Interaction in = build ? new Interaction(true, true, 64, p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4))) : Interaction.NONE;
            MoveGraph mg = new MoveGraph(w, 3, true, hw, true, in);
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
            PlanResult r = p.plan(32, 2_000_000, 512, 20_000_000_000L);
            System.out.printf("PROF build=%s hw=%s %s -> %s: %s %d ms path %d expansions %d recomputes %d costs %d%n", build, hw,
                Pos.toString(start), Pos.toString(goal), r.status(), (System.nanoTime() - t0) / 1_000_000, r.path().size(), ct[0], ct[2], ct[1]);
        }
    }
}
