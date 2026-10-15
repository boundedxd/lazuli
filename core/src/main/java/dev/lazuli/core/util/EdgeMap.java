package dev.lazuli.core.util;

import java.util.function.LongPredicate;

/** A hash map from (u, v) pairs of longs (graph edges) to doubles, open addressing, no boxing. */
public final class EdgeMap {
    private long[] us, vs;
    private double[] vals;
    private boolean[] used;
    private int size, mask;

    public EdgeMap() {
        this(64);
    }

    public EdgeMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        alloc(cap);
    }

    private void alloc(int cap) {
        us = new long[cap];
        vs = new long[cap];
        vals = new double[cap];
        used = new boolean[cap];
        mask = cap - 1;
        size = 0;
    }

    private static int hash(long u, long v) {
        return LongMap.hash(u * 0x9E3779B97F4A7C15L + v);
    }

    public int size() {
        return size;
    }

    /** The value for u->v, or {@code missing} if there is none. */
    public double get(long u, long v, double missing) {
        for (int i = hash(u, v) & mask; used[i]; i = (i + 1) & mask) {
            if (us[i] == u && vs[i] == v) return vals[i];
        }
        return missing;
    }

    public boolean contains(long u, long v) {
        for (int i = hash(u, v) & mask; used[i]; i = (i + 1) & mask) {
            if (us[i] == u && vs[i] == v) return true;
        }
        return false;
    }

    public void put(long u, long v, double val) {
        int i = hash(u, v) & mask;
        for (; used[i]; i = (i + 1) & mask) {
            if (us[i] == u && vs[i] == v) {
                vals[i] = val;
                return;
            }
        }
        us[i] = u;
        vs[i] = v;
        vals[i] = val;
        used[i] = true;
        if (++size * 2 > us.length) rehash(us.length * 2, null);
    }

    /** Drops every edge whose start matches. */
    public void removeIfFrom(LongPredicate fromU) {
        rehash(us.length, fromU);
    }

    public void clear() {
        java.util.Arrays.fill(used, false);
        size = 0;
    }

    private void rehash(int cap, LongPredicate drop) {
        long[] ou = us, ov = vs;
        double[] oval = vals;
        boolean[] oused = used;
        alloc(cap);
        for (int i = 0; i < ou.length; i++) {
            if (!oused[i] || drop != null && drop.test(ou[i])) continue;
            int j = hash(ou[i], ov[i]) & mask;
            while (used[j]) j = (j + 1) & mask;
            us[j] = ou[i];
            vs[j] = ov[i];
            vals[j] = oval[i];
            used[j] = true;
            size++;
        }
    }
}
