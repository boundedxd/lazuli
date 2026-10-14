package dev.lazuli.core.planner;

import java.util.function.LongConsumer;

/**
 * Directed graph over packed block positions (see {@code Pos}). Costs are in ticks.
 * Every edge u->v has an admissible optimistic cost and an exact evaluated cost >= it.
 */
public interface Graph {
    /** True if a player can stand at u. */
    boolean isNode(long u);

    /** Emits every v that might have an edge u->v; emitted v may still cost infinity. */
    void successors(long u, LongConsumer out);

    /** Emits every u that might have an edge u->v; emitted u may still cost infinity. */
    void predecessors(long v, LongConsumer out);

    /** Cheap lower bound on the edge cost; infinity if the edge certainly doesn't exist. */
    double optimisticCost(long u, long v);

    /** Exact (possibly expensive) cost. Must be >= {@link #optimisticCost}. */
    default double evaluate(long u, long v) {
        return optimisticCost(u, v);
    }

    /** The world changed: drop anything cached about it. */
    default void invalidate() {}

    /**
     * Blocks changed inside these boxes ({x0, y0, z0, x1, y1, z1} each): drop what's cached about nodes and edges
     * starting inside them. Callers widen the boxes by the reach of an edge first.
     */
    default void invalidate(java.util.List<int[]> boxes) {
        invalidate();
    }

    /** Consistent, symmetric lower bound on the cost between any two positions. */
    double heuristic(long a, long b);

    /** An edge u->v only depends on blocks within this many blocks of u horizontally. */
    int horizontalReach();

    /** An edge u->v only depends on blocks within this many blocks of u vertically. */
    int verticalReach();
}
