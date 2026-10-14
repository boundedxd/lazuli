package dev.lazuli.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldCacheTest {
    @Test
    void unloadedChunksAreUnknown() {
        assertEquals(BlockKind.UNKNOWN, new WorldCache(-64, 384).get(0, 64, 0));
    }

    @Test
    void belowWorldIsDangerAndAboveIsAir() {
        WorldCache c = new WorldCache(-64, 384);
        c.putChunk(0, 0, new byte[24][]);
        assertEquals(BlockKind.DANGER, c.get(0, -65, 0));
        assertEquals(BlockKind.AIR, c.get(0, 320, 0));
    }

    @Test
    void nullSectionsReadAsAirAndSetAllocatesThem() {
        WorldCache c = new WorldCache(-64, 384);
        c.putChunk(-1, 2, new byte[24][]);
        assertEquals(BlockKind.AIR, c.get(-5, 70, 40));
        c.set(-5, 70, 40, BlockKind.SOLID);
        assertEquals(BlockKind.SOLID, c.get(-5, 70, 40));
        assertEquals(BlockKind.AIR, c.get(-5, 71, 40));
    }

    @Test
    void setOnUnloadedChunkIsIgnoredAndRemoveForgets() {
        WorldCache c = new WorldCache(-64, 384);
        c.set(100, 64, 100, BlockKind.SOLID);
        assertEquals(BlockKind.UNKNOWN, c.get(100, 64, 100));
        c.putChunk(0, 0, new byte[24][]);
        c.removeChunk(0, 0);
        assertEquals(BlockKind.UNKNOWN, c.get(1, 64, 1));
    }

    @Test
    void sectionPaletteHoldsThousandsOfDistinctBlocksAndSurvivesRewrites() {
        WorldCache c = new WorldCache(-64, 384);
        c.putChunk(0, 0, new byte[24][]);
        BlockPhysics[] ps = new BlockPhysics[600];
        for (int n = 0; n < ps.length; n++) {
            ps[n] = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.6f, 1, 1, 0, 1 + n, 100_000 + n);
        }
        // many rounds of distinct writes force growth past a byte and palette compaction
        for (int round = 0; round < 12; round++) {
            for (int n = 0; n < ps.length; n++) {
                BlockPhysics p = ps[(n + round) % ps.length];
                c.set(n & 15, 64 + (n >> 8), (n >> 4) & 15, BlockKind.SOLID, p.id);
            }
        }
        for (int n = 0; n < ps.length; n++) {
            assertSame(ps[(n + 11) % ps.length], c.physics(n & 15, 64 + (n >> 8), (n >> 4) & 15));
            assertEquals(BlockKind.SOLID, c.get(n & 15, 64 + (n >> 8), (n >> 4) & 15));
        }
        assertEquals(BlockKind.AIR, c.get(15, 79, 15));
        assertSame(BlockPhysics.EMPTY, c.physics(15, 79, 15));
    }
}
