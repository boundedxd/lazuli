package dev.lazuli.mod.world;

import dev.lazuli.core.world.WorldCache;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

public final class ChunkScanner {
    private ChunkScanner() {}

    /** Converts a loaded chunk into the cache's section arrays; all-air sections stay null. */
    public static byte[][] scan(LevelChunk chunk, int sectionCount) {
        LevelChunkSection[] sections = chunk.getSections();
        byte[][] out = new byte[sectionCount][];
        for (int i = 0; i < Math.min(sectionCount, sections.length); i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir()) continue;
            byte[] kinds = new byte[4096];
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        kinds[WorldCache.index(x, y, z)] = (byte) BlockClassifier.classify(sec.getBlockState(x, y, z)).ordinal();
            out[i] = kinds;
        }
        return out;
    }
}
