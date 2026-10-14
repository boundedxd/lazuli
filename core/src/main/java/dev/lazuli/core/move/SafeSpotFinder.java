package dev.lazuli.core.move;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import java.util.PriorityQueue;

/** Fallback target: the reachable, proven-safe standing spot nearest to the goal. */
public final class SafeSpotFinder {
    private SafeSpotFinder() {}

    public static boolean isSafe(BlockView w, int x, int y, int z) {
        if (!MoveGraph.isStandable(w, x, y, z)) return false;
        for (int dy = -1; dy <= 1; dy++) {
            if (w.get(x, y + dy, z) == BlockKind.UNKNOWN) return false;
        }
        for (int ox = -1; ox <= 1; ox++)
            for (int oy = -1; oy <= 2; oy++)
                for (int oz = -1; oz <= 1; oz++)
                    if (w.get(x + ox, y + oy, z + oz) == BlockKind.DANGER) return false;
        return true;
    }

    /** Forward Dijkstra from start over known terrain; min distance to goal, ties broken by path cost. */
    public static OptionalLong find(MoveGraph graph, BlockView w, long start, Goal goal, int maxNodes) {
        Map<Long, Double> dist = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        dist.put(start, 0.0);
        pq.add(new double[]{0.0, start});
        long[] best = {0};
        double[] bestDist = {Double.POSITIVE_INFINITY};
        double[] bestCost = {Double.POSITIVE_INFINITY};
        boolean[] found = {false};
        int popped = 0;
        while (!pq.isEmpty() && popped++ < maxNodes) {
            double[] top = pq.poll();
            double d = top[0];
            long u = (long) top[1];
            if (d > dist.get(u)) continue;
            int x = Pos.x(u), y = Pos.y(u), z = Pos.z(u);
            if (isSafe(w, x, y, z)) {
                double gd = goal.distance(x, y, z);
                if (gd < bestDist[0] || (gd == bestDist[0] && d < bestCost[0])) {
                    best[0] = u;
                    bestDist[0] = gd;
                    bestCost[0] = d;
                    found[0] = true;
                }
            }
            graph.successors(u, v -> {
                if (w.get(Pos.x(v), Pos.y(v) - 1, Pos.z(v)) == BlockKind.UNKNOWN) return;
                double c = graph.optimisticCost(u, v);
                if (c == Double.POSITIVE_INFINITY) return;
                double nd = d + c;
                if (nd < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) {
                    dist.put(v, nd);
                    pq.add(new double[]{nd, v});
                }
            });
        }
        return found[0] ? OptionalLong.of(best[0]) : OptionalLong.empty();
    }
}
