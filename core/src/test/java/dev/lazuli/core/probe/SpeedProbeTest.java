package dev.lazuli.core.probe;

import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.planner.PlanResult;
import dev.lazuli.core.testutil.AnvilWorld;
import dev.lazuli.core.world.Pos;
import dev.lazuli.core.world.WorldCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.function.LongConsumer;

@Tag("benchmark")
class SpeedProbeTest {
    @Test
    void speed() throws Exception {
        Path world = Path.of("../mod/run/saves/New World");
        double[] pp = AnvilWorld.playerPos(world);
        int pcx = (int) Math.floor(pp[0]) >> 4, pcz = (int) Math.floor(pp[2]) >> 4;
        WorldCache w = AnvilWorld.load(world, pcx - 12, pcx + 12, pcz - 12, pcz + 12);
        long start = Standing.nodeUnder(w, pp[0], pp[1], pp[2]);
        int[][] offs = {{40, 30}, {-60, 20}, {25, -70}, {90, 90}, {-100, -40}};
        for (int round = 0; round < 3; round++) {
            long total = 0;
            for (int[] o : offs) {
                int gx = Pos.x(start) + o[0], gz = Pos.z(start) + o[1], gy = 319;
                while (gy > -60 && !Standing.standable(w, gx, gy, gz)) gy--;
                long goal = Pos.pack(gx, gy, gz);
                Interaction in = new Interaction(true, true, 64, p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4)));
                MoveGraph mg = new MoveGraph(w, 3, true, 1.2, true, in);
                long[] ns = new long[5];
                long[] ct = new long[5];
                Graph cg = new Graph() {
                    public boolean isNode(long u) { long t = System.nanoTime(); try { return mg.isNode(u); } finally { ns[4] += System.nanoTime() - t; } }
                    public void successors(long u, LongConsumer o) { ct[2]++; long t = System.nanoTime(); mg.successors(u, o); ns[2] += System.nanoTime() - t; }
                    public void predecessors(long v, LongConsumer o) { ct[0]++; long t = System.nanoTime(); mg.predecessors(v, o); ns[0] += System.nanoTime() - t; }
                    public double optimisticCost(long u, long v) { ct[1]++; long t = System.nanoTime(); try { return mg.optimisticCost(u, v); } finally { ns[1] += System.nanoTime() - t; } }
                    public double evaluate(long u, long v) { ct[3]++; long t = System.nanoTime(); try { return mg.evaluate(u, v); } finally { ns[3] += System.nanoTime() - t; } }
                    public void invalidate() { mg.invalidate(); }
                    public double heuristic(long a, long b) { return mg.heuristic(a, b); }
                    public int horizontalReach() { return mg.horizontalReach(); }
                    public int verticalReach() { return mg.verticalReach(); }
                };
                LazyDStarLite p = new LazyDStarLite(cg, q -> q == goal, new long[]{goal}, start);
                long t0 = System.nanoTime();
                PlanResult r = p.plan(32, 2_000_000, 512, 20_000_000_000L);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                total += ms;
                if (round == 2) System.out.printf("SPEED %s -> %s %s %d ms path %d | pred %d (%d ms) succ %d (%d ms) cost %d (%d ms) eval %d (%d ms) isNode %d ms%n",
                    Pos.toString(start), Pos.toString(goal), r.status(), ms, r.path().size(), ct[0], ns[0] / 1_000_000, ct[2], ns[2] / 1_000_000,
                    ct[1], ns[1] / 1_000_000, ct[3], ns[3] / 1_000_000, ns[4] / 1_000_000);
            }
            System.out.println("SPEED round " + round + " total " + total + " ms");
        }
    }
}
