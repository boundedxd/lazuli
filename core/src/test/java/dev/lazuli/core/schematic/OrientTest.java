package dev.lazuli.core.schematic;

import dev.lazuli.core.schematic.Orient.Dir;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrientTest {
    // yaws looking along each direction (vanilla: 0 = south, 90 = west, 180 = north, -90 = east)
    static final float SOUTH = 0, WEST = 90, NORTH = 180, EAST = -90;

    @Test
    void plainBlockHasNoRule() {
        assertNull(Orient.rule("minecraft:stone", Map.of()));
        assertNull(Orient.rule("minecraft:oak_leaves", Map.of("distance", "7", "persistent", "true")));
    }

    @Test
    void logAxisIsTheClickedFaceAxis() {
        Orient.Rule r = Orient.rule("minecraft:oak_log", Map.of("axis", "x"));
        assertTrue(r.ok(Dir.EAST, 0.5, NORTH, 0));
        assertTrue(r.ok(Dir.WEST, 0.5, SOUTH, 80));
        assertFalse(r.ok(Dir.UP, 0.5, EAST, 0));
        assertFalse(r.ok(Dir.NORTH, 0.5, EAST, 0));
        assertTrue(Orient.rule("minecraft:hay_block", Map.of("axis", "y")).ok(Dir.UP, 1, NORTH, 0));
    }

    @Test
    void stairsFacePlayerDirectionAndHalfByHit() {
        Orient.Rule bottom = Orient.rule("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom", "shape", "straight"));
        assertTrue(bottom.ok(Dir.UP, 1, EAST, 30));
        assertFalse(bottom.ok(Dir.UP, 1, WEST, 30));       // wrong way
        assertTrue(bottom.ok(Dir.EAST, 0.2, EAST, 30));    // side, low half
        assertFalse(bottom.ok(Dir.EAST, 0.8, EAST, 30));   // side, high half -> top
        assertFalse(bottom.ok(Dir.EAST, 0.5, EAST, 30));   // too close to the middle to trust
        assertFalse(bottom.ok(Dir.DOWN, 0, EAST, 30));     // under a block -> top
        assertFalse(bottom.ok(Dir.UP, 1, EAST + 44, 30));  // nearly south: too close to the edge

        Orient.Rule top = Orient.rule("minecraft:stone_brick_stairs", Map.of("facing", "north", "half", "top"));
        assertTrue(top.ok(Dir.DOWN, 0, NORTH, -10));
        assertTrue(top.ok(Dir.SOUTH, 0.8, NORTH, 0));
        assertFalse(top.ok(Dir.UP, 1, NORTH, 0));
    }

    @Test
    void slabsByHalfAndDoubleAnyway() {
        Orient.Rule top = Orient.rule("minecraft:oak_slab", Map.of("type", "top"));
        assertTrue(top.ok(Dir.DOWN, 0, EAST, 0));
        assertTrue(top.ok(Dir.NORTH, 0.9, EAST, 0));
        assertFalse(top.ok(Dir.UP, 1, EAST, 0));
        Orient.Rule bottom = Orient.rule("minecraft:oak_slab", Map.of("type", "bottom"));
        assertTrue(bottom.ok(Dir.UP, 1, EAST, 0));
        assertFalse(bottom.ok(Dir.NORTH, 0.9, EAST, 0));
        Orient.Rule dbl = Orient.rule("minecraft:oak_slab", Map.of("type", "double"));
        assertTrue(dbl.ok(Dir.NORTH, 0.5, EAST, 0));
        // a single slab where a double is wanted: place the other half on it, don't break it
        assertTrue(dbl.placeOnto(Map.of("type", "bottom")));
        assertFalse(dbl.fits(Map.of("type", "bottom")));
        assertTrue(dbl.fits(Map.of("type", "double")));
        assertFalse(top.placeOnto(Map.of("type", "bottom")));
    }

    @Test
    void trapdoorsHingeOnTheClickedSideOrFaceAwayFromPlayer() {
        Orient.Rule r = Orient.rule("minecraft:oak_trapdoor", Map.of("facing", "south", "half", "top", "open", "false"));
        assertTrue(r.ok(Dir.SOUTH, 0.8, EAST, 0));   // clicked a south face, high
        assertFalse(r.ok(Dir.SOUTH, 0.2, EAST, 0));  // low -> bottom
        assertFalse(r.ok(Dir.EAST, 0.8, EAST, 0));
        assertTrue(r.ok(Dir.DOWN, 0, NORTH, 0));     // under a block, looking north -> faces south
        assertFalse(r.ok(Dir.DOWN, 0, SOUTH, 0));
        assertFalse(r.ok(Dir.UP, 1, NORTH, 0));      // on top -> bottom
        // open isn't set by placing: not compared
        assertTrue(r.fits(Map.of("facing", "south", "half", "top", "open", "true")));
        assertFalse(r.fits(Map.of("facing", "south", "half", "bottom", "open", "false")));
    }

    @Test
    void horizontalBlocksFaceThePlayer() {
        Orient.Rule furnace = Orient.rule("minecraft:furnace", Map.of("facing", "south", "lit", "false"));
        assertTrue(furnace.ok(Dir.UP, 1, NORTH, 20));
        assertFalse(furnace.ok(Dir.UP, 1, SOUTH, 20));
        Orient.Rule door = Orient.rule("minecraft:oak_door", Map.of("facing", "west", "half", "lower", "hinge", "left"));
        assertTrue(door.ok(Dir.UP, 1, WEST, 20));
        assertTrue(door.fits(Map.of("facing", "west", "half", "lower", "hinge", "right")));
        assertFalse(door.fits(Map.of("facing", "east", "half", "lower", "hinge", "left")));
        assertTrue(Orient.rule("minecraft:anvil", Map.of("facing", "east")).ok(Dir.UP, 1, NORTH, 20));
    }

    @Test
    void sixWayByLookOrClickedFace() {
        Orient.Rule observer = Orient.rule("minecraft:observer", Map.of("facing", "down", "powered", "false"));
        assertTrue(observer.ok(Dir.EAST, 0.5, NORTH, 70));
        assertFalse(observer.ok(Dir.EAST, 0.5, NORTH, 20));
        Orient.Rule piston = Orient.rule("minecraft:piston", Map.of("facing", "up", "extended", "false"));
        assertTrue(piston.ok(Dir.UP, 1, NORTH, 70));      // looking down -> faces up
        Orient.Rule rod = Orient.rule("minecraft:end_rod", Map.of("facing", "west"));
        assertTrue(rod.ok(Dir.WEST, 0.5, EAST, 0));
        assertFalse(rod.ok(Dir.UP, 0.5, WEST, 0));
        Orient.Rule hopper = Orient.rule("minecraft:hopper", Map.of("facing", "east", "enabled", "true"));
        assertTrue(hopper.ok(Dir.WEST, 0.5, NORTH, 0));
        assertFalse(hopper.ok(Dir.UP, 1, NORTH, 0));
        assertTrue(Orient.rule("minecraft:hopper", Map.of("facing", "down")).ok(Dir.UP, 1, NORTH, 0));
    }

    @Test
    void standSideIsBehindTheWantedFacing() {
        assertEquals(Dir.WEST, Orient.rule("minecraft:oak_stairs", Map.of("facing", "east", "half", "bottom")).standSide());
        assertEquals(Dir.SOUTH, Orient.rule("minecraft:furnace", Map.of("facing", "south")).standSide());
        assertNull(Orient.rule("minecraft:oak_log", Map.of("axis", "x")).standSide());
    }
}
