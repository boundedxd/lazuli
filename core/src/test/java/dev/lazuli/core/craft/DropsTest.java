package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DropsTest {
    private static final String REDSTONE = """
        {"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:alternatives","children":[
          {"type":"minecraft:item","name":"minecraft:redstone_ore","conditions":[{"condition":"minecraft:match_tool"}]},
          {"type":"minecraft:item","name":"minecraft:redstone","functions":[
            {"function":"minecraft:set_count","count":{"type":"minecraft:uniform","min":4.0,"max":5.0}},
            {"function":"minecraft:apply_bonus"}]}]}]}]}
        """;
    private static final String GRAVEL = """
        {"type":"minecraft:block","pools":[{"rolls":1,"entries":[{"type":"minecraft:alternatives","children":[
          {"type":"minecraft:item","name":"minecraft:flint","conditions":[{"condition":"minecraft:table_bonus","chances":[0.1,0.14,0.25,1.0]}]},
          {"type":"minecraft:item","name":"minecraft:gravel"}]}]}]}
        """;
    private static final String LAPIS = """
        {"pools":[{"rolls":1,"entries":[{"type":"minecraft:item","name":"minecraft:lapis_lazuli","functions":[
          {"function":"minecraft:set_count","count":{"type":"minecraft:uniform","min":4,"max":9}}]}]}]}
        """;

    @Test
    void yieldsPerBlockCountCountsAndChances() {
        Drops d = Drops.load(Map.of("minecraft:redstone_ore", REDSTONE, "minecraft:gravel", GRAVEL, "minecraft:lapis_ore", LAPIS));
        assertEquals(4.5, d.perBlock("minecraft:redstone_ore", "minecraft:redstone"), 1e-9);
        assertEquals(0.1, d.perBlock("minecraft:gravel", "minecraft:flint"), 1e-9);
        assertEquals(1.0, d.perBlock("minecraft:gravel", "minecraft:gravel"), 1e-9);
        assertEquals(6.5, d.perBlock("minecraft:lapis_ore", "minecraft:lapis_lazuli"), 1e-9);
        assertEquals(1.0, d.perBlock("minecraft:stone", "minecraft:cobblestone"), 1e-9, "unknown: one each");
    }

    @Test
    void blocksToMineForACount() {
        Drops d = Drops.load(Map.of("minecraft:redstone_ore", REDSTONE, "minecraft:gravel", GRAVEL));
        assertEquals(15, d.blocksFor("minecraft:redstone", 64, java.util.List.of("minecraft:redstone_ore")));
        assertEquals(50, d.blocksFor("minecraft:flint", 5, java.util.List.of("minecraft:gravel")));
        assertEquals(3, d.blocksFor("minecraft:dirt", 3, java.util.List.of("minecraft:dirt")));
    }
}
