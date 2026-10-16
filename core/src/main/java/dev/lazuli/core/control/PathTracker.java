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

    /** Node index for the player's block (or the nearest node within ~2 blocks), or -1 if lost. */
    public int update(List<Long> path, long pathVersion, int bx, int by, int bz) {
        if (pathVersion != version) {
            version = pathVersion;
            lastIdx = -1;
        }
        int from = Math.max(0, lastIdx);
        // the exact block anywhere nearby (also behind: back down a pillar's cell after a jump) before any column match
        int idx = exact(path, from, bx, by, bz);
        if (idx < 0 && from > 0) idx = exact(path, Math.max(0, from - SEARCH), bx, by, bz);
        if (idx < 0) idx = column(path, from, bx, bz);
        if (idx < 0 && from > 0) idx = column(path, 0, bx, bz);
        // far from where it was (a boat ride, a long clutch): the exact block anywhere on the path, rather than
        // sticking to the old spot and heading back there
        if (idx < 0) {
            for (int i = 0; i < path.size() && idx < 0; i++) if (path.get(i) == Pos.pack(bx, by, bz)) idx = i;
        }
        if (idx >= 0) {
            lastIdx = idx;
            return idx;
        }
        if (lastIdx >= 0) return lastIdx;
        // lost (e.g. a fresh path while standing beside it): take the nearest of its first nodes
        int best = -1;
        long bestD = Long.MAX_VALUE;
        for (int i = 0; i < Math.min(path.size(), SEARCH); i++) {
            long n = path.get(i);
            long dx = Pos.x(n) - bx, dy = Pos.y(n) - by, dz = Pos.z(n) - bz;
            long d = dx * dx + 4 * dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return bestD <= 8 ? best : -1;
    }

    /** True if the player's block is exactly a path node (the planner may be told about it). */
    public static boolean onNode(List<Long> path, int idx, int bx, int by, int bz) {
        return idx >= 0 && path.get(idx) == Pos.pack(bx, by, bz);
    }

    private static int exact(List<Long> path, int from, int bx, int by, int bz) {
        int limit = Math.min(path.size(), from + 2 * SEARCH);
        for (int i = from; i < limit; i++) {
            long n = path.get(i);
            if (Pos.x(n) == bx && Pos.y(n) == by && Pos.z(n) == bz) return i;
        }
        return -1;
    }

    private static int column(List<Long> path, int from, int bx, int bz) {
        int limit = Math.min(path.size(), from + SEARCH);
        for (int i = from; i < limit; i++) {
            long n = path.get(i);
            if (Pos.x(n) == bx && Pos.z(n) == bz) return i;
        }
        return -1;
    }
}
