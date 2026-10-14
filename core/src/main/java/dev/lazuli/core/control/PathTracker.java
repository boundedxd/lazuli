package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Finds which path node the player is at. Run-up and gap blocks of a jump aren't path nodes, so while the
 * player is on one the last matched node of the same path version is kept.
 */
public final class PathTracker {
    private static final int SEARCH = 12;

    private long version = -1;
    private int lastIdx = -1;

    public void reset() {
        version = -1;
        lastIdx = -1;
    }

    /** Node index for the player's block, or -1 if lost (new path version and not on any of its first nodes). */
    public int update(List<Long> path, long pathVersion, int bx, int by, int bz) {
        if (pathVersion != version) {
            version = pathVersion;
            lastIdx = -1;
        }
        int from = Math.max(0, lastIdx);
        int idx = find(path, from, bx, by, bz);
        if (idx < 0 && from > 0) idx = find(path, 0, bx, by, bz);
        if (idx >= 0) {
            lastIdx = idx;
            return idx;
        }
        return lastIdx >= 0 && lastIdx < path.size() - 1 ? lastIdx : -1;
    }

    /** True if the player's block is exactly a path node (the planner may be told about it). */
    public static boolean onNode(List<Long> path, int idx, int bx, int by, int bz) {
        return idx >= 0 && path.get(idx) == Pos.pack(bx, by, bz);
    }

    private static int find(List<Long> path, int from, int bx, int by, int bz) {
        int limit = Math.min(path.size(), from + SEARCH);
        for (int i = from; i < limit; i++) {
            long n = path.get(i);
            if (Pos.x(n) == bx && Pos.y(n) == by && Pos.z(n) == bz) return i;
        }
        for (int i = from; i < limit; i++) {
            long n = path.get(i);
            if (Pos.x(n) == bx && Pos.z(n) == bz) return i;
        }
        return -1;
    }
}
