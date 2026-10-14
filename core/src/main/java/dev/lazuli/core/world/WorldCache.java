package dev.lazuli.core.world;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-chunk block kinds plus optional physics palette ids. Written by the client thread, read by the planner
 * thread. Single-block writes race benignly; each is followed by a change notification.
 */
public final class WorldCache implements BlockView {
    private record Chunk(byte[][] kinds, short[][] physics) {}

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

    /** @param physics per section, null where every block's physics follows from its kind */
    public void putChunk(int cx, int cz, byte[][] sections, short[][] physics) {
        if (sections.length != sectionCount() || physics.length != sectionCount()) {
            throw new IllegalArgumentException("expected " + sectionCount() + " sections, got " + sections.length);
        }
        chunks.put(key(cx, cz), new Chunk(sections, physics));
    }

    public void removeChunk(int cx, int cz) {
        chunks.remove(key(cx, cz));
    }

    public boolean isLoaded(int cx, int cz) {
        return chunks.containsKey(key(cx, cz));
    }

    public void set(int x, int y, int z, BlockKind kind) {
        set(x, y, z, kind, BlockPhysics.DERIVED);
    }

    public void set(int x, int y, int z, BlockKind kind, short physicsId) {
        Chunk c = chunks.get(key(x >> 4, z >> 4));
        if (c == null || y < minY || y > maxY()) {
            return;
        }
        int s = (y - minY) >> 4;
        if (c.kinds[s] == null) {
            if (kind == BlockKind.AIR && physicsId == BlockPhysics.DERIVED) {
                return;
            }
            byte[] fresh = new byte[4096];
            Arrays.fill(fresh, (byte) BlockKind.AIR.ordinal());
            c.kinds[s] = fresh;
        }
        c.kinds[s][index(x, y, z)] = (byte) kind.ordinal();
        if (c.physics[s] == null && physicsId != BlockPhysics.DERIVED) c.physics[s] = new short[4096];
        if (c.physics[s] != null) c.physics[s][index(x, y, z)] = physicsId;
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
        byte[] section = c.kinds[(y - minY) >> 4];
        return section == null ? BlockKind.AIR : BlockKind.byId(section[index(x, y, z)]);
    }

    @Override
    public BlockPhysics physics(int x, int y, int z) {
        if (y >= minY && y <= maxY()) {
            Chunk c = chunks.get(key(x >> 4, z >> 4));
            if (c != null) {
                int s = (y - minY) >> 4;
                short[] ids = c.physics[s];
                if (ids != null) {
                    BlockPhysics p = BlockPhysics.byId(ids[index(x, y, z)]);
                    if (p != null) return p;
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
