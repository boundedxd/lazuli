package dev.lazuli.core.util;

import java.util.function.LongFunction;
import java.util.function.LongPredicate;

/**
 * A hash map from long keys (packed positions) to non-null values, with open addressing and a well-mixed hash. Plain
 * {@code HashMap<Long, V>} folds a long's halves together, and packed block positions collide badly that way.
 */
public final class LongMap<V> {
    private long[] keys;
    private Object[] vals;
    private int size, mask;

    public LongMap() {
        this(16);
    }

    public LongMap(int expected) {
        int cap = Integer.highestOneBit(Math.max(4, expected * 2 - 1)) << 1;
        keys = new long[cap];
        vals = new Object[cap];
        mask = cap - 1;
    }

    static int hash(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k;
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @SuppressWarnings("unchecked")
    public V get(long k) {
        for (int i = hash(k) & mask; ; i = (i + 1) & mask) {
            Object v = vals[i];
            if (v == null) return null;
            if (keys[i] == k) return (V) v;
        }
    }

    public boolean containsKey(long k) {
        return get(k) != null;
    }

    /** @return the previous value, or null */
    @SuppressWarnings("unchecked")
    public V put(long k, V v) {
        if (v == null) throw new NullPointerException("null value");
        int i = hash(k) & mask;
        for (; vals[i] != null; i = (i + 1) & mask) {
            if (keys[i] == k) {
                V old = (V) vals[i];
                vals[i] = v;
                return old;
            }
        }
        keys[i] = k;
        vals[i] = v;
        if (++size * 2 > keys.length) grow();
        return null;
    }

    public V computeIfAbsent(long k, LongFunction<V> f) {
        V v = get(k);
        if (v == null) {
            v = f.apply(k);
            put(k, v);
        }
        return v;
    }

    /** @return the removed value, or null */
    @SuppressWarnings("unchecked")
    public V remove(long k) {
        int i = hash(k) & mask;
        for (; vals[i] != null; i = (i + 1) & mask) {
            if (keys[i] == k) break;
        }
        if (vals[i] == null) return null;
        V old = (V) vals[i];
        // backward-shift deletion: pull later entries of the probe run into the hole
        int hole = i;
        for (int j = (i + 1) & mask; vals[j] != null; j = (j + 1) & mask) {
            int home = hash(keys[j]) & mask;
            if (((j - home) & mask) >= ((j - hole) & mask)) {
                keys[hole] = keys[j];
                vals[hole] = vals[j];
                hole = j;
            }
        }
        vals[hole] = null;
        size--;
        return old;
    }

    public void removeIf(LongPredicate p) {
        long[] k = keys;
        Object[] v = vals;
        keys = new long[k.length];
        vals = new Object[k.length];
        size = 0;
        for (int i = 0; i < k.length; i++) {
            if (v[i] != null && !p.test(k[i])) reinsert(k[i], v[i]);
        }
    }

    public void clear() {
        java.util.Arrays.fill(vals, null);
        size = 0;
    }

    public interface Entry<V> {
        void accept(long k, V v);
    }

    @SuppressWarnings("unchecked")
    public void forEach(Entry<V> e) {
        for (int i = 0; i < keys.length; i++) if (vals[i] != null) e.accept(keys[i], (V) vals[i]);
    }

    private void grow() {
        long[] k = keys;
        Object[] v = vals;
        keys = new long[k.length * 2];
        vals = new Object[k.length * 2];
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < k.length; i++) if (v[i] != null) reinsert(k[i], v[i]);
    }

    private void reinsert(long k, Object v) {
        int i = hash(k) & mask;
        while (vals[i] != null) i = (i + 1) & mask;
        keys[i] = k;
        vals[i] = v;
        size++;
    }
}
