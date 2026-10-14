package dev.lazuli.core.testutil;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.HashMap;
import java.util.Map;

public final class GridWorld implements BlockView {
    private final Map<Long, BlockKind> blocks = new HashMap<>();
    private final Map<Long, BlockPhysics> physics = new HashMap<>();
    private final BlockKind fallback;

    public GridWorld(BlockKind fallback) {
        this.fallback = fallback;
    }

    public static GridWorld flatFloor(int minX, int minZ, int maxX, int maxZ) {
        return new GridWorld(BlockKind.AIR).fill(minX, 0, minZ, maxX, 0, maxZ, BlockKind.SOLID);
    }

    public GridWorld set(int x, int y, int z, BlockKind k) {
        blocks.put(Pos.pack(x, y, z), k);
        physics.remove(Pos.pack(x, y, z));
        return this;
    }

    public GridWorld fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockKind k) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, k);
        return this;
    }

    /** A block with explicit physics; its kind follows from the shape and flags. */
    public GridWorld setPhysics(int x, int y, int z, BlockPhysics p) {
        BlockKind k = p.has(BlockPhysics.WATER) ? BlockKind.WATER
            : p.empty() ? BlockKind.AIR
            : java.util.Arrays.equals(p.boxes, BlockPhysics.FULL.boxes) ? BlockKind.SOLID : BlockKind.OBSTACLE;
        if (p.has(BlockPhysics.LAVA) || p.has(BlockPhysics.STICKY)) k = BlockKind.DANGER;
        blocks.put(Pos.pack(x, y, z), k);
        physics.put(Pos.pack(x, y, z), p);
        return this;
    }

    @Override
    public BlockPhysics physics(int x, int y, int z) {
        BlockPhysics p = physics.get(Pos.pack(x, y, z));
        return p != null ? p : BlockPhysics.forKind(get(x, y, z));
    }

    @Override
    public BlockKind get(int x, int y, int z) {
        return blocks.getOrDefault(Pos.pack(x, y, z), fallback);
    }
}
