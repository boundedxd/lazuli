package dev.lazuli.core.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScanMapTest {
    @Test
    void oneLetterPerBlockTypeInFirstSeenOrder() {
        Map<String, String> world = Map.of(
            "0,0,0", "minecraft:stone", "1,0,0", "minecraft:stone", "2,0,0", "minecraft:dirt");
        ScanMap.Layer layer = ScanMap.layer(0, 0, 2, 0, 0, (x, y, z) -> world.get(x + "," + y + "," + z));
        assertEquals(0, layer.y());
        assertEquals(java.util.List.of("AAB"), layer.rows());
        assertEquals(Map.of('A', "minecraft:stone", 'B', "minecraft:dirt"), layer.legend());
        assertFalse(layer.unloaded());
    }

    @Test
    void rowsRunNorthToSouthAndCharactersWestToEast() {
        Map<String, String> world = Map.of(
            "0,5,0", "minecraft:stone", "0,5,1", "minecraft:dirt");
        ScanMap.Layer layer = ScanMap.layer(5, 0, 0, 0, 1, (x, y, z) -> world.get(x + "," + y + "," + z));
        assertEquals(java.util.List.of("A", "B"), layer.rows());
    }

    @Test
    void unloadedChunksAreMarkedAndDoNotBreakTheRow() {
        ScanMap.Layer layer = ScanMap.layer(64, 0, 3, 0, 0, (x, y, z) -> x == 1 ? null : "minecraft:stone");
        assertEquals(java.util.List.of("A?AA"), layer.rows());
        assertTrue(layer.unloaded());
        assertEquals(Map.of('A', "minecraft:stone"), layer.legend());
    }

    @Test
    void palettesBeyondFiftyTwoTypesReuseOneMarker() {
        ScanMap.Layer layer = ScanMap.layer(0, 0, 60, 0, 0, (x, y, z) -> "minecraft:b" + x);
        assertEquals(53, layer.legend().size()); // A-Z, a-z, then '#' for everything beyond
        assertEquals("#", String.valueOf(layer.rows().get(0).charAt(52)));
        String row = layer.rows().get(0);
        assertEquals(61, row.length());
        assertEquals('A', row.charAt(0));
        assertEquals('Z', row.charAt(25));
        assertEquals('a', row.charAt(26));
        assertEquals('z', row.charAt(51));
        assertEquals('#', row.charAt(52));
    }
}
