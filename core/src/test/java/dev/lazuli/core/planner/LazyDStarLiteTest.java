package dev.lazuli.core.planner;

import dev.lazuli.core.testutil.GridGraph;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LazyDStarLiteTest {
    private static final int ALL = Integer.MAX_VALUE;

    private static double pathCost(GridGraph g, List<Long> p) {
        double c = 0;
        for (int i = 0; i + 1 < p.size(); i++) c += g.trueCost(p.get(i), p.get(i + 1));
        return c;
    }

    private static LazyDStarLite planner(GridGraph g, long start, long goal) {
        return new LazyDStarLite(g, u -> u == goal, new long[]{goal}, start);
    }

    @Test
    void findsOptimalPathOnOpenGrid() {
        GridGraph g = new GridGraph(20, 1, 0.0);
        long s = Pos.pack(0, 0, 0), t = Pos.pack(19, 0, 19);
        PlanResult r = planner(g, s, t).plan(ALL, 1_000_000, ALL);
        assertEquals(PlanResult.Status.FOUND, r.status());
        assertEquals(s, r.path().get(0));
        assertEquals(t, r.path().get(r.path().size() - 1));
        assertEquals(g.dijkstra(s, t), pathCost(g, r.path()), 1e-9);
    }

    @Test
    void reportsNoPathWhenGoalIsWalledOff() {
        GridGraph g = new GridGraph(10, 2, 0.0);
        long t = Pos.pack(5, 0, 5);
        for (long n : g.neighbors(t)) g.setCost(n, t, Double.POSITIVE_INFINITY);
        assertEquals(PlanResult.Status.NO_PATH, planner(g, Pos.pack(0, 0, 0), t).plan(ALL, 1_000_000, ALL).status());
    }

    @Test
    void budgetExceededInsteadOfHanging() {
        GridGraph g = new GridGraph(60, 3, 0.0);
        assertEquals(PlanResult.Status.BUDGET_EXCEEDED,
            planner(g, Pos.pack(0, 0, 0), Pos.pack(59, 0, 59)).plan(ALL, 10, ALL).status());
    }

    @Test
    void repairsMatchDijkstraUnderRandomChanges() {
        for (int trial = 0; trial < 25; trial++) {
            Random r = new Random(100 + trial);
            GridGraph g = new GridGraph(25, trial, 0.2);
            long start = Pos.pack(0, 0, 0), goal = Pos.pack(24, 0, 24);
            g.neighbors(start).forEach(n -> g.setCost(start, n, 1));
            g.neighbors(goal).forEach(n -> g.setCost(n, goal, 1));
            LazyDStarLite p = planner(g, start, goal);
            long cur = start;
            for (int step = 0; step < 15; step++) {
                PlanResult res = p.plan(ALL, 10_000_000, ALL);
                double best = g.dijkstra(cur, goal);
                if (best == Double.POSITIVE_INFINITY) {
                    assertEquals(PlanResult.Status.NO_PATH, res.status());
                    break;
                }
                assertEquals(PlanResult.Status.FOUND, res.status());
                assertEquals(best, pathCost(g, res.path()), 1e-9, "trial " + trial + " step " + step);
                if (res.path().size() > 1) {
                    cur = res.path().get(1);
                    p.moveStart(cur);
                }
                for (int k = 0; k < 8; k++) {
                    long u = Pos.pack(r.nextInt(25), 0, r.nextInt(25));
                    for (long v : g.neighbors(u)) {
                        g.setCost(u, v, r.nextDouble() < 0.25 ? Double.POSITIVE_INFINITY : 1 + r.nextInt(5));
                        p.regionChanged(Math.min(Pos.x(u), Pos.x(v)), 0, Math.min(Pos.z(u), Pos.z(v)),
                            Math.max(Pos.x(u), Pos.x(v)), 0, Math.max(Pos.z(u), Pos.z(v)));
                    }
                }
            }
        }
    }

    @Test
    void evaluatedCostBelowOptimisticIsRejected() {
        GridGraph g = new GridGraph(5, 4, 0.0) {
            @Override
            public double evaluate(long u, long v) {
                return 0.5;
            }
        };
        assertThrows(IllegalStateException.class,
            () -> planner(g, Pos.pack(0, 0, 0), Pos.pack(4, 0, 4)).plan(ALL, 1_000_000, ALL));
    }

    @Test
    void moveStartKeepsPathsOptimal() {
        GridGraph g = new GridGraph(15, 5, 0.1);
        long goal = Pos.pack(14, 0, 14), s = Pos.pack(0, 0, 0);
        g.neighbors(goal).forEach(n -> g.setCost(n, goal, 1));
        g.neighbors(s).forEach(n -> g.setCost(s, n, 1));
        LazyDStarLite p = planner(g, s, goal);
        List<Long> path = p.plan(ALL, 1_000_000, ALL).path();
        for (int i = 1; i < path.size(); i++) {
            p.moveStart(path.get(i));
            PlanResult r = p.plan(ALL, 1_000_000, ALL);
            assertEquals(g.dijkstra(path.get(i), goal), pathCost(g, r.path()), 1e-9);
        }
    }
}
