package dev.lazuli.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChestMemoryTest {
    @Test
    void findsTheNearestChestWithAnItem() {
        ChestMemory m = new ChestMemory(null);
        m.record("overworld", 100, 64, 0, Map.of("minecraft:diamond", 5));
        m.record("overworld", 10, 64, 0, Map.of("minecraft:diamond", 2, "minecraft:dirt", 64));
        m.record("the_nether", 1, 64, 0, Map.of("minecraft:diamond", 9));
        var found = m.find("minecraft:diamond", "overworld", 0, 64, 0);
        assertEquals(2, found.size());
        assertEquals(10, found.get(0).x());
        assertEquals(2, found.get(0).count());
        assertNull(m.nearest("minecraft:diamond", "overworld", 0, 64, 0, 5));
        assertEquals(10, m.nearest("minecraft:diamond", "overworld", 0, 64, 0, 20).x());
        assertEquals(7, m.totals("overworld").get("minecraft:diamond"));
    }

    @Test
    void reopeningAChestReplacesWhatItHeld() {
        ChestMemory m = new ChestMemory(null);
        m.record("overworld", 0, 64, 0, Map.of("minecraft:diamond", 5));
        m.record("overworld", 0, 64, 0, Map.of());
        assertTrue(m.find("minecraft:diamond", "overworld", 0, 0, 0).isEmpty());
        assertEquals(1, m.size());
        m.forget("overworld", 0, 64, 0);
        assertEquals(0, m.size());
    }

    @Test
    void survivesARestart(@TempDir Path dir) {
        Path f = dir.resolve("chests.txt");
        new ChestMemory(f).record("overworld", -3, 70, 12, Map.of("minecraft:iron_ingot", 30, "minecraft:coal", 4));
        ChestMemory again = new ChestMemory(f);
        assertEquals(30, again.find("minecraft:iron_ingot", "overworld", 0, 0, 0).get(0).count());
        assertEquals(-3, again.find("minecraft:coal", "overworld", 0, 0, 0).get(0).x());
    }
}
