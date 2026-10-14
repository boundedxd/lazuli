package dev.lazuli.core.planner;

import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

/**
 * D* Lite (Koenig and Likhachev 2002, optimized version) searching backward from a virtual goal node,
 * with lazy edge evaluation: edges use optimistic costs until they lie within the first
 * {@code evalHorizon} edges of the current best path.
 */
public final class LazyDStarLite {
    /** Virtual goal node; every real goal node has a 0-cost edge to it. */
    public static final long GOAL = Long.MIN_VALUE;
    public static final double INF = Double.POSITIVE_INFINITY;

    private static final class Node {
        double g = INF;
        double rhs = INF;
        Key key;
    }

    private record Key(double k1, double k2, long id) implements Comparable<Key> {
        int compareKeys(Key o) {
            int c = Double.compare(k1, o.k1);
            return c != 0 ? c : Double.compare(k2, o.k2);
        }

        @Override
        public int compareTo(Key o) {
            int c = compareKeys(o);
            return c != 0 ? c : Long.compare(id, o.id);
        }
    }

    private final Graph graph;
    private final LongPredicate isGoal;
    private final long[] goalCandidates;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final Map<Long, List<Long>> nodesByChunk = new HashMap<>();
    private final TreeSet<Key> open = new TreeSet<>();
    private final Map<Long, Map<Long, Double>> evaluated = new HashMap<>();
    private long start;
    private double km;
    private long expansions;
    /** System.nanoTime() after which the current plan() gives up. */
    private long deadline = Long.MAX_VALUE;

    public LazyDStarLite(Graph graph, LongPredicate isGoal, long[] goalCandidates, long start) {
        this.graph = graph;
        this.isGoal = isGoal;
        this.goalCandidates = goalCandidates.clone();
        this.start = start;
        Node goal = node(GOAL);
        goal.rhs = 0;
        updateVertex(GOAL, goal);
    }

    public long start() {
        return start;
    }

    public double g(long u) {
        Node n = nodes.get(u);
        return n == null ? INF : n.g;
    }

    /** The player moved: offset future keys by the heuristic distance travelled (the paper's k_m). */
    public void moveStart(long newStart) {
        km += h(start, newStart);
        start = newStart;
    }

    /** The player couldn't make it along u->v: treat that edge as impassable until the blocks around it change. */
    public void edgeFailed(long u, long v) {
        evaluated.computeIfAbsent(u, k -> new HashMap<>()).put(v, INF);
        recompute(u);
    }

    /** Blocks inside the box changed: un-evaluate and repair every vertex whose edges may depend on them. */
    public void regionChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        regionsChanged(List.of(new int[]{minX, minY, minZ, maxX, maxY, maxZ}));
    }

    /**
     * Blocks inside these boxes changed: un-evaluate and repair every vertex whose edges may depend on them.
     *
     * @return how many vertices the search had touched there (0: the current plan can't have changed)
     */
    public int regionsChanged(List<int[]> boxes) {
        int hr = graph.horizontalReach() + 1;
        int vr = graph.verticalReach() + 1;
        List<int[]> wide = new ArrayList<>();
        for (int[] b : boxes) wide.add(new int[]{b[0] - hr, b[1] - vr, b[2] - hr, b[3] + hr, b[4] + vr, b[5] + hr});
        graph.invalidate(wide);
        List<Long> affected = new ArrayList<>();
        for (int[] b : wide) {
            for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) {
                for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) {
                    List<Long> inChunk = nodesByChunk.get(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                    if (inChunk == null) continue;
                    for (long v : inChunk) {
                        int x = Pos.x(v), y = Pos.y(v), z = Pos.z(v);
                        if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) affected.add(v);
                    }
                }
            }
        }
        for (long v : affected) {
            evaluated.remove(v);
            recompute(v);
            graph.predecessors(v, u -> {
                evaluated.remove(u);
                recompute(u);
            });
        }
        return affected.size();
    }

    /**
     * Searches with current costs, evaluates the first {@code evalHorizon} edges of the best path,
     * repairs if any changed, and repeats until those edges are exact.
     */
    public PlanResult plan(int evalHorizon, long maxExpansions, int maxPathLength) {
        return plan(evalHorizon, maxExpansions, maxPathLength, Long.MAX_VALUE);
    }

    /** @param maxNanos wall-clock budget; past it the search stops as if out of expansions */
    public PlanResult plan(int evalHorizon, long maxExpansions, int maxPathLength, long maxNanos) {
        long now = System.nanoTime();
        deadline = maxNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + maxNanos;
        expansions = 0;
        while (true) {
            List<Long> path;
            while (true) {
                if (!computeShortestPath(start, maxExpansions)) return PlanResult.budgetExceeded();
                if (g(start) == INF) return PlanResult.noPath();
                path = extractPath(maxPathLength);
                // With an inflated heuristic the search can stop while nodes beyond the start are still
                // inconsistent; settle every node on the path so g strictly decreases along it.
                long bad = firstInconsistent(path);
                if (bad == GOAL) break;
                if (!computeShortestPath(bad, maxExpansions)) return PlanResult.budgetExceeded();
            }
            boolean changed = false;
            for (int i = 0; i + 1 < path.size() && i < evalHorizon; i++) {
                long u = path.get(i), v = path.get(i + 1);
                Map<Long, Double> fromU = evaluated.computeIfAbsent(u, k -> new HashMap<>());
                if (fromU.containsKey(v)) continue;
                double opt = graph.optimisticCost(u, v);
                double exact = graph.evaluate(u, v);
                if (exact < opt) {
                    throw new IllegalStateException("evaluated cost " + exact + " below optimistic " + opt
                        + " for " + Pos.toString(u) + " -> " + Pos.toString(v));
                }
                fromU.put(v, exact);
                if (exact != opt) {
                    recompute(u);
                    changed = true;
                }
            }
            if (!changed) return PlanResult.found(path);
        }
    }

    /** Returns the first path node whose g and rhs disagree, or GOAL if all are consistent. */
    private long firstInconsistent(List<Long> path) {
        for (long v : path) {
            Node n = nodes.get(v);
            if (n == null || n.g != n.rhs) return v;
        }
        return GOAL;
    }

    /** Expands until {@code target} is consistent and no queued key is smaller than its key. */
    private boolean computeShortestPath(long target, long maxExpansions) {
        while (!open.isEmpty()) {
            Key top = open.first();
            Node s = node(target);
            if (top.compareKeys(key(target, s)) >= 0 && s.rhs == s.g) return true;
            if (expansions++ >= maxExpansions || (expansions & 255) == 0 && System.nanoTime() > deadline) return false;
            long u = top.id();
            Node n = nodes.get(u);
            Key fresh = key(u, n);
            if (top.compareKeys(fresh) < 0) {
                open.remove(top);
                n.key = fresh;
                open.add(fresh);
            } else if (n.g > n.rhs) {
                n.g = n.rhs;
                open.remove(top);
                n.key = null;
                double gu = n.g;
                forEachPred(u, p -> {
                    if (p == GOAL) return;
                    double c = cost(p, u);
                    if (c == INF) return;
                    Node pn = node(p);
                    if (c + gu < pn.rhs) {
                        pn.rhs = c + gu;
                        updateVertex(p, pn);
                    }
                });
            } else {
                double gOld = n.g;
                n.g = INF;
                forEachPred(u, p -> {
                    Node pn = nodes.get(p);
                    if (pn != null && p != GOAL && pn.rhs == cost(p, u) + gOld) recompute(p);
                });
                recompute(u);
            }
        }
        return true;
    }

    private List<Long> extractPath(int maxLength) {
        List<Long> path = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        long u = start;
        path.add(u);
        seen.add(u);
        while (path.size() < maxLength) {
            long[] best = {0};
            double[] bestCost = {INF};
            long cur = u;
            forEachSucc(cur, s -> {
                double c = cost(cur, s) + g(s);
                if (c < bestCost[0]) {
                    bestCost[0] = c;
                    best[0] = s;
                }
            });
            if (bestCost[0] == INF || best[0] == GOAL || !seen.add(best[0])) break;
            u = best[0];
            path.add(u);
        }
        return path;
    }

    private void recompute(long u) {
        if (u == GOAL) return;
        double best = minSucc(u);
        Node n = nodes.get(u);
        if (n == null) {
            if (best == INF) return;
            n = node(u);
        }
        n.rhs = best;
        updateVertex(u, n);
    }

    private double minSucc(long u) {
        double[] best = {INF};
        forEachSucc(u, s -> {
            double c = cost(u, s);
            if (c != INF) best[0] = Math.min(best[0], c + g(s));
        });
        return best[0];
    }

    private void updateVertex(long u, Node n) {
        if (n.key != null) {
            open.remove(n.key);
            n.key = null;
        }
        if (n.g != n.rhs) {
            n.key = key(u, n);
            open.add(n.key);
        }
    }

    private Key key(long u, Node n) {
        double m = Math.min(n.g, n.rhs);
        return new Key(m + h(start, u) + km, m, u);
    }

    private double cost(long u, long v) {
        if (v == GOAL) return isGoal.test(u) && graph.isNode(u) ? 0 : INF;
        Map<Long, Double> fromU = evaluated.get(u);
        if (fromU != null) {
            Double c = fromU.get(v);
            if (c != null) return c;
        }
        return graph.optimisticCost(u, v);
    }

    private double h(long a, long b) {
        return a == GOAL || b == GOAL ? 0 : graph.heuristic(a, b);
    }

    private void forEachSucc(long u, LongConsumer out) {
        if (u == GOAL) return;
        graph.successors(u, out);
        if (isGoal.test(u)) out.accept(GOAL);
    }

    private void forEachPred(long v, LongConsumer out) {
        if (v == GOAL) {
            for (long c : goalCandidates) out.accept(c);
        } else {
            graph.predecessors(v, out);
        }
    }

    private Node node(long u) {
        Node n = nodes.get(u);
        if (n == null) {
            n = new Node();
            nodes.put(u, n);
            if (u != GOAL) {
                nodesByChunk.computeIfAbsent(Pos.chunkKey(Pos.x(u), Pos.z(u)), k -> new ArrayList<>()).add(u);
            }
        }
        return n;
    }
}
