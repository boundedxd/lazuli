package dev.lazuli.mod.world;

import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.WorldCache;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkScanner {
    private ChunkScanner() {}

    /** A chunk as the cache stores it: block kinds and physics ids per section. */
    public record Scan(byte[][] kinds, short[][] physics) {}

    /**
     * Converts a loaded chunk into the cache's section arrays; all-air sections stay null, as do physics arrays of
     * sections whose blocks are all plain (full cubes, air, water).
     */
    public static Scan scan(LevelChunk chunk, int sectionCount) {
        LevelChunkSection[] sections = chunk.getSections();
        byte[][] out = new byte[sectionCount][];
        short[][] physics = new short[sectionCount][];
        for (int i = 0; i < Math.min(sectionCount, sections.length); i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            byte[] kinds = new byte[4096];
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        BlockState state = sec.getBlockState(x, y, z);
                        int idx = WorldCache.index(x, y, z);
                        kinds[idx] = (byte) BlockClassifier.classify(state).ordinal();
                        short id = BlockClassifier.physicsId(state);
                        if (id != BlockPhysics.DERIVED) {
                            if (physics[i] == null) physics[i] = new short[4096];
                            physics[i][idx] = id;
                        }
                    }
            out[i] = kinds;
        }
        return new Scan(out, physics);
    }
}
