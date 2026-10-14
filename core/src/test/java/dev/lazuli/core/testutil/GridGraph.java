package dev.lazuli.core.testutil;

import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.function.LongConsumer;

/** 4-connected grid at y=0 with random true costs; optimistic cost is always 1. */
public class GridGraph implements Graph {
    public final int size;
    private final Map<String, Double> cost = new HashMap<>();

    public GridGraph(int size, long seed, double blockedFraction) {
        this.size = size;
        Random r = new Random(seed);
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) {
                long u = Pos.pack(x, 0, z);
                for (long v : neighbors(u))
                    cost.put(key(u, v), r.nextDouble() < blockedFraction ? Double.POSITIVE_INFINITY : 1 + r.nextInt(5));
            }
    }

    public List<Long> neighbors(long u) {
        List<Long> out = new ArrayList<>();
        int x = Pos.x(u), z = Pos.z(u);
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], nz = z + o[1];
            if (nx >= 0 && nz >= 0 && nx < size && nz < size) out.add(Pos.pack(nx, 0, nz));
        }
        return out;
    }

    private static String key(long u, long v) {
        return u + ">" + v;
    }

    public double trueCost(long u, long v) {
        return cost.getOrDefault(key(u, v), Double.POSITIVE_INFINITY);
    }

    public void setCost(long u, long v, double c) {
        cost.put(key(u, v), c);
    }

    @Override
    public boolean isNode(long u) {
        int x = Pos.x(u), z = Pos.z(u);
        return Pos.y(u) == 0 && x >= 0 && z >= 0 && x < size && z < size;
    }

    @Override
    public void successors(long u, LongConsumer out) {
        neighbors(u).forEach(out::accept);
    }

    @Override
    public void predecessors(long v, LongConsumer out) {
        neighbors(v).forEach(out::accept);
    }

    @Override
    public double optimisticCost(long u, long v) {
        return trueCost(u, v) == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : 1;
    }

    @Override
    public double evaluate(long u, long v) {
        return trueCost(u, v);
    }

    @Override
    public double heuristic(long a, long b) {
        return Math.abs(Pos.x(a) - Pos.x(b)) + Math.abs(Pos.z(a) - Pos.z(b));
    }

    @Override
    public int horizontalReach() {
        return 1;
    }

    @Override
    public int verticalReach() {
        return 0;
    }

    public double dijkstra(long start, long goal) {
        Map<Long, Double> dist = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[0]));
        dist.put(start, 0.0);
        pq.add(new double[]{0, start});
        while (!pq.isEmpty()) {
            double[] top = pq.poll();
            long u = (long) top[1];
            if (top[0] > dist.get(u)) continue;
            if (u == goal) return top[0];
            for (long v : neighbors(u)) {
                double nd = top[0] + trueCost(u, v);
                if (nd < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) {
                    dist.put(v, nd);
                    pq.add(new double[]{nd, v});
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
