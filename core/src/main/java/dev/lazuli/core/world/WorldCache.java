package dev.lazuli.core.world;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chunk block kinds and physics palette ids. Each 16x16x16 section stores one byte per block indexing a small
 * per-section palette of (kind, physics id) pairs, so detailed physics costs nothing over the kinds alone. Written by
 * the client thread, read by the planner thread. Single-block writes race benignly; each is followed by a change
 * notification.
 */
public final class WorldCache implements BlockView {
    private record Chunk(Section[] sections) {}

    /** One section's blocks as palette indices. Entry 0 of the palette is plain air. */
    static final class Section {
        private volatile byte[] small = new byte[4096];
        private volatile short[] large;
        private volatile byte[] kinds = new byte[16];
        private volatile short[] phys = new short[16];
        private int size = 1;

        Section() {
            kinds[0] = (byte) BlockKind.AIR.ordinal();
        }

        private int entry(int i) {
            short[] l = large;
            return l != null ? l[i] & 0xFFFF : small[i] & 0xFF;
        }

        // readers don't lock: an index may briefly run ahead of the palette array it reads (the change notification
        // that follows every write repairs whatever was planned from it)
        BlockKind kind(int i) {
            int e = entry(i);
            byte[] k = kinds;
            return e < k.length ? BlockKind.byId(k[e]) : BlockKind.UNKNOWN;
        }

        short physics(int i) {
            int e = entry(i);
            short[] p = phys;
            return e < p.length ? p[e] : BlockPhysics.DERIVED;
        }

        /** A section from whole arrays, mapping each distinct (kind, physics) pair once. */
        static Section of(byte[] k, short[] p) {
            Section sec = new Section();
            java.util.HashMap<Integer, Integer> entries = new java.util.HashMap<>();
            int air = BlockKind.AIR.ordinal() << 16;
            entries.put(air, 0);
            int lastKey = air, lastEntry = 0;
            for (int i = 0; i < 4096; i++) {
                int key = (k[i] & 0xFF) << 16 | (p == null ? 0 : p[i] & 0xFFFF);
                if (key != lastKey) {
                    Integer e = entries.get(key);
                    if (e == null) {
                        sec.set(i, BlockKind.byId(k[i]), p == null ? BlockPhysics.DERIVED : p[i]);
                        entries.put(key, sec.entry(i));
                        lastKey = key;
                        lastEntry = sec.entry(i);
                        continue;
                    }
                    lastKey = key;
                    lastEntry = e;
                }
                if (sec.large != null) sec.large[i] = (short) lastEntry;
                else sec.small[i] = (byte) lastEntry;
            }
            return sec;
        }

        synchronized void set(int i, BlockKind kind, short physicsId) {
            int e = find(kind, physicsId);
            if (e < 0) {
                if (size == phys.length) grow();
                e = size;
                // fill the entry before any block points at it
                kinds[e] = (byte) kind.ordinal();
                phys[e] = physicsId;
                kinds = kinds;
                phys = phys;
                size++;
                if (e >= 256 && large == null) {
                    short[] l = new short[4096];
                    byte[] sm = small;
                    for (int j = 0; j < 4096; j++) l[j] = (short) (sm[j] & 0xFF);
                    large = l;
                }
            }
            if (large != null) large[i] = (short) e;
            else small[i] = (byte) e;
        }

        private int find(BlockKind kind, short physicsId) {
            byte k = (byte) kind.ordinal();
            for (int e = 0; e < size; e++) if (kinds[e] == k && phys[e] == physicsId) return e;
            return -1;
        }

        private void grow() {
            if (size >= 4096) compact();
            if (size < phys.length) return;
            int n = phys.length * 2;
            byte[] k = Arrays.copyOf(kinds, n);
            short[] p = Arrays.copyOf(phys, n);
            kinds = k;
            phys = p;
        }

        /** Drops palette entries no block uses any more (only after thousands of distinct writes). */
        private void compact() {
            int[] remap = new int[size];
            Arrays.fill(remap, -1);
            byte[] k = new byte[phys.length];
            short[] p = new short[phys.length];
            short[] l = new short[4096];
            k[0] = kinds[0];
            p[0] = phys[0];
            remap[0] = 0;
            int n = 1;
            for (int j = 0; j < 4096; j++) {
                int e = entry(j);
                if (remap[e] < 0) {
                    remap[e] = n;
                    k[n] = kinds[e];
                    p[n] = phys[e];
                    n++;
                }
                l[j] = (short) remap[e];
            }
            // readers may briefly pair new indices with old palettes; the change notification that follows repairs it
            kinds = k;
            phys = p;
            large = l;
            size = n;
        }
    }

    private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
    private volatile int minY;
    private volatile int height;

    public WorldCache(int minY, int height) {
        this.minY = minY;
        this.height = height;
    }

    public void reset(int minY, int height) {
        chunks.clear();
        this.minY = minY;
        this.height = height;
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return minY + height - 1;
    }

    public int sectionCount() {
        return height >> 4;
    }

    public void putChunk(int cx, int cz, byte[][] sections) {
        putChunk(cx, cz, sections, new short[sections.length][]);
    }

    /**
     * @param kinds   per section, null for all air
     * @param physics per section, null where every block's physics follows from its kind
     */
    public void putChunk(int cx, int cz, byte[][] kinds, short[][] physics) {
        if (kinds.length != sectionCount() || physics.length != sectionCount()) {
            throw new IllegalArgumentException("expected " + sectionCount() + " sections, got " + kinds.length);
        }
        Section[] out = new Section[kinds.length];
        for (int s = 0; s < kinds.length; s++) {
            if (kinds[s] != null) out[s] = Section.of(kinds[s], physics[s]);
        }
        chunks.put(key(cx, cz), new Chunk(out));
    }

    public void removeChunk(int cx, int cz) {
        chunks.remove(key(cx, cz));
    }

    public boolean isLoaded(int cx, int cz) {
        return chunks.containsKey(key(cx, cz));
    }

    public boolean set(int x, int y, int z, BlockKind kind) {
        return set(x, y, z, kind, BlockPhysics.DERIVED);
    }

    /** @return whether the block's kind or physics actually changed (most block updates don't matter here) */
    public boolean set(int x, int y, int z, BlockKind kind, short physicsId) {
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null || y < minY || y > maxY()) {
            return false;
        }
        int s = (y - minY) >> 4;
        Section sec = c.sections[s];
        if (sec == null) {
            if (kind == BlockKind.AIR && physicsId == BlockPhysics.DERIVED) {
                return false;
            }
            sec = new Section();
            c.sections[s] = sec;
        }
        int i = index(x, y, z);
        if (sec.kind(i) == kind && sec.physics(i) == physicsId) return false;
        sec.set(i, kind, physicsId);
        return true;
    }

    @Override
    public BlockKind get(int x, int y, int z) {
        if (y < minY) {
            return BlockKind.DANGER;
        }
        if (y > maxY()) {
            return BlockKind.AIR;
        }
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null) {
            return BlockKind.UNKNOWN;
        }
        Section section = c.sections[(y - minY) >> 4];
        return section == null ? BlockKind.AIR : section.kind(index(x, y, z));
    }

    @Override
    public BlockPhysics physics(int x, int y, int z) {
        if (y >= minY && y <= maxY()) {
            Chunk c = chunks.get(key(x >> 4, z >> 4));
            if (c != null) {
                Section sec = c.sections[(y - minY) >> 4];
                if (sec != null) {
                    int i = index(x, y, z);
                    BlockPhysics p = BlockPhysics.byId(sec.physics(i));
                    return p != null ? p : BlockPhysics.forKind(sec.kind(i));
                }
            }
        }
        return BlockPhysics.forKind(get(x, y, z));
    }

    public static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
