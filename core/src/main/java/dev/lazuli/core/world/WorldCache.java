package dev.lazuli.core.world;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Written by the client thread, read by the planner thread. Single-block writes race benignly; each is followed by a change notification. */
public final class WorldCache implements BlockView {
    private final ConcurrentHashMap<Long, byte[][]> chunks = new ConcurrentHashMap<>();
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
        if (sections.length != sectionCount()) {
            throw new IllegalArgumentException("expected " + sectionCount() + " sections, got " + sections.length);
        }
        chunks.put(key(cx, cz), sections);
    }

    public void removeChunk(int cx, int cz) {
        chunks.remove(key(cx, cz));
    }

    public boolean isLoaded(int cx, int cz) {
        return chunks.containsKey(key(cx, cz));
    }

    public void set(int x, int y, int z, BlockKind kind) {
        byte[][] sections = chunks.get(key(x >> 4, z >> 4));
        if (sections == null || y < minY || y > maxY()) {
            return;
        }
        int s = (y - minY) >> 4;
        if (sections[s] == null) {
            if (kind == BlockKind.AIR) {
                return;
            }
            byte[] fresh = new byte[4096];
            Arrays.fill(fresh, (byte) BlockKind.AIR.ordinal());
            sections[s] = fresh;
        }
        sections[s][index(x, y, z)] = (byte) kind.ordinal();
    }

    @Override
    public BlockKind get(int x, int y, int z) {
        if (y < minY) {
            return BlockKind.DANGER;
        }
        if (y > maxY()) {
            return BlockKind.AIR;
        }
        byte[][] sections = chunks.get(key(x >> 4, z >> 4));
        if (sections == null) {
            return BlockKind.UNKNOWN;
        }
        byte[] section = sections[(y - minY) >> 4];
        return section == null ? BlockKind.AIR : BlockKind.byId(section[index(x, y, z)]);
    }

    public static int index(int x, int y, int z) {
        return ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
