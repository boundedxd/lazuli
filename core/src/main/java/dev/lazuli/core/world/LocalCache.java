package dev.lazuli.core.world;

import java.util.Arrays;

/**
 * Dense, lazily filled snapshot of a box of blocks around a point, for hot loops (the input planner simulates
 * thousands of ticks per game tick against the same few hundred blocks). Not thread-safe; {@link #recenter}
 * once per use.
 */
public final class LocalCache implements BlockView {
    private static final int RX = 24, RY = 16, RZ = 24;
    private static final int SX = 2 * RX, SY = 2 * RY, SZ = 2 * RZ;

    private final BlockView backing;
    private final BlockKind[] kinds = new BlockKind[SX * SY * SZ];
    private final BlockPhysics[] physics = new BlockPhysics[SX * SY * SZ];
    private int ox, oy, oz;

    public LocalCache(BlockView backing) {
        this.backing = backing;
    }

    /** Forgets everything and centers the cached box on (x, y, z). */
    public LocalCache recenter(int x, int y, int z) {
        Arrays.fill(kinds, null);
        Arrays.fill(physics, null);
        ox = x - RX;
        oy = y - RY;
        oz = z - RZ;
        return this;
    }

    private int index(int x, int y, int z) {
        int lx = x - ox, ly = y - oy, lz = z - oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= SX || ly >= SY || lz >= SZ) return -1;
        return (ly * SZ + lz) * SX + lx;
    }

    @Override
    public BlockKind get(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) return backing.get(x, y, z);
        BlockKind k = kinds[i];
        if (k == null) kinds[i] = k = backing.get(x, y, z);
        return k;
    }

    @Override
    public BlockPhysics physics(int x, int y, int z) {
        int i = index(x, y, z);
        if (i < 0) return backing.physics(x, y, z);
        BlockPhysics p = physics[i];
        if (p == null) physics[i] = p = backing.physics(x, y, z);
        return p;
    }
}
