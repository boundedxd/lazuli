package dev.lazuli.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
