package dev.lazuli.core.mine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OreDepthTest {
    @Test
    void bestLevels() {
        assertEquals(-58, OreDepth.bestY("minecraft:diamond_ore", "overworld", -64, 319));
        assertEquals(-58, OreDepth.bestY("minecraft:deepslate_diamond_ore", "overworld", -64, 319));
        assertEquals(16, OreDepth.bestY("iron_ore", "overworld", -64, 319));
        assertEquals(15, OreDepth.bestY("minecraft:ancient_debris", "the_nether", 0, 255));
        // an old world: never into the bedrock
        assertEquals(5, OreDepth.bestY("minecraft:diamond_ore", "overworld", 0, 255));
        assertNull(OreDepth.bestY("minecraft:oak_log", "overworld", -64, 319));
        assertNull(OreDepth.bestY("minecraft:ancient_debris", "overworld", -64, 319), "only in the nether");
    }

    @Test
    void whatCountsAsAnOre() {
        assertTrue(OreDepth.isOre("minecraft:deepslate_iron_ore"));
        assertTrue(OreDepth.isOre("minecraft:ancient_debris"));
        assertTrue(OreDepth.isOre("nether_quartz_ore"));
        assertFalse(OreDepth.isOre("minecraft:stone"));
        assertFalse(OreDepth.isOre("minecraft:raw_iron_block"));
    }

    @Test
    void stripMinePattern() {
        // legs: long, over, long back, over...; turning right then left so it sweeps an area, tunnels 4 apart
        StripPattern s = new StripPattern(0, 24);
        assertEquals(new StripPattern.Leg(0, 24), s.leg(0));
        assertEquals(new StripPattern.Leg(1, 4), s.leg(1));
        assertEquals(new StripPattern.Leg(2, 24), s.leg(2));
        assertEquals(new StripPattern.Leg(1, 4), s.leg(3));
        assertEquals(new StripPattern.Leg(0, 24), s.leg(4));
        // facing south (2): the legs go south, west, north, west, south...
        StripPattern t = new StripPattern(2, 10);
        assertEquals(2, t.leg(0).dir());
        assertEquals(3, t.leg(1).dir());
        assertEquals(0, t.leg(2).dir());
    }
}
