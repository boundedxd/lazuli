package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MobDropsTest {
    static final String COW = """
        {"type": "minecraft:entity", "pools": [
          {"rolls": 1.0, "entries": [{"type": "minecraft:item", "name": "minecraft:leather", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 0.0, "max": 2.0}},
            {"function": "minecraft:enchanted_count_increase", "count": {"min": 0.0, "max": 1.0}, "enchantment": "minecraft:looting"}]}]},
          {"rolls": 1.0, "entries": [{"type": "minecraft:item", "name": "minecraft:beef", "functions": [
            {"function": "minecraft:set_count", "count": {"type": "minecraft:uniform", "min": 1.0, "max": 3.0}},
            {"function": "minecraft:furnace_smelt", "conditions": [{"condition": "minecraft:entity_properties", "entity": "this",
              "predicate": {"flags": {"is_on_fire": true}}}]}]}]}]}""";

    static final String WITCH_ISH = """
        {"type": "minecraft:entity", "pools": [
          {"rolls": 1.0, "conditions": [{"condition": "minecraft:killed_by_player"},
            {"condition": "minecraft:random_chance_with_enchanted_bonus", "unenchanted_chance": 0.5, "enchantment": "minecraft:looting"}],
           "entries": [{"type": "minecraft:item", "name": "minecraft:gunpowder"}]},
          {"rolls": 1.0, "conditions": [{"condition": "minecraft:damage_source_properties"}],
           "entries": [{"type": "minecraft:item", "name": "minecraft:music_disc_13"}]}]}""";

    @Test
    void readsCountsAndChances() {
        MobDrops d = MobDrops.load(Map.of("minecraft:cow", COW, "minecraft:witch", WITCH_ISH));
        assertEquals(List.of(new MobDrops.Source("minecraft:cow", 1.0)), d.sources("minecraft:leather"));
        assertEquals(2.0, d.sources("minecraft:beef").get(0).perKill(), 1e-9); // cooked only when on fire: still the raw item
        assertEquals(0.5, d.sources("minecraft:gunpowder").get(0).perKill(), 1e-9);
        assertTrue(d.sources("minecraft:music_disc_13").isEmpty()); // only killed by a skeleton's arrow
    }

    @Test
    void getItemHuntsForWhatOnlyMobsDrop() {
        MobDrops mobs = MobDrops.load(Map.of("minecraft:cow", COW));
        ItemPlanner off = ItemPlannerTest.planner();
        assertFalse(off.plan("minecraft:leather", 3, Map.of(), 0, true, true).ok());
        ItemPlanner on = ItemPlannerTest.planner().mobs(mobs);
        ItemPlanner.Plan p = on.plan("minecraft:leather", 3, Map.of(), 0, true, true);
        assertTrue(p.ok(), p.problem());
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.KILL, "minecraft:leather", 3, List.of("minecraft:cow"))), p.actions());
    }
}
