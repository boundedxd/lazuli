package dev.lazuli.core.goal;

import java.util.function.LongConsumer;

/** A set of block positions the player's feet may end at. */
public interface Goal {
    boolean isGoal(int x, int y, int z);

    /** Emits every packed position that could satisfy the goal; unbounded goals emit only near the player within [minY, maxY]. */
    void forEachCandidate(int playerX, int playerY, int playerZ, int minY, int maxY, LongConsumer out);

    /** Euclidean distance in blocks to the nearest goal position; 0 inside the goal. */
    double distance(int x, int y, int z);

    String describe();
}
