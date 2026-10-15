package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ItemPlannerTest {
    static final String SILK = """
        {"condition": "minecraft:match_tool", "predicate": {"predicates": {"minecraft:enchantments": [{"enchantments": "minecraft:silk_touch"}]}}}""";

    /** A block that drops itself. */
    static String self(String id) {
        return "{\"type\": \"minecraft:block\", \"pools\": [{\"rolls\": 1.0, \"entries\": [{\"type\": \"minecraft:item\", \"name\": \"" + id + "\"}]}]}";
    }

    /** A block that drops itself with silk touch, else {@code drop}. */
    static String ore(String id, String drop) {
        return "{\"type\": \"minecraft:block\", \"pools\": [{\"rolls\": 1.0, \"entries\": [{\"type\": \"minecraft:alternatives\", \"children\": ["
            + "{\"type\": \"minecraft:item\", \"conditions\": [" + SILK + "], \"name\": \"" + id + "\"},"
            + "{\"type\": \"minecraft:item\", \"name\": \"" + drop + "\"}]}]}]}";
    }

    static Drops drops() {
        Map<String, String> loot = new HashMap<>();
        loot.put("minecraft:oak_log", self("minecraft:oak_log"));
        loot.put("minecraft:stone", ore("minecraft:stone", "minecraft:cobblestone"));
        loot.put("minecraft:cobblestone", self("minecraft:cobblestone"));
        loot.put("minecraft:iron_ore", ore("minecraft:iron_ore", "minecraft:raw_iron"));
        loot.put("minecraft:deepslate_iron_ore", ore("minecraft:deepslate_iron_ore", "minecraft:raw_iron"));
        loot.put("minecraft:coal_ore", ore("minecraft:coal_ore", "minecraft:coal"));
        loot.put("minecraft:glass", "{\"type\": \"minecraft:block\", \"pools\": [{\"rolls\": 1.0, \"entries\": [{\"type\": \"minecraft:item\", \"conditions\": ["
            + SILK + "], \"name\": \"minecraft:glass\"}]}]}");
        loot.put("minecraft:dead_bush", "{\"type\": \"minecraft:block\", \"pools\": [{\"rolls\": 1.0, \"entries\": [{\"type\": \"minecraft:item\", \"name\": \"minecraft:stick\"}]}]}");
        return Drops.load(loot);
    }

    static Recipes recipes() {
        Map<String, String> r = new HashMap<>(), tags = new HashMap<>();
        r.putAll(Map.of(
            "minecraft:oak_planks", """
                {"type": "minecraft:crafting_shapeless", "ingredients": ["#minecraft:oak_logs"], "result": {"count": 4, "id": "minecraft:oak_planks"}}""",
            "minecraft:stick", """
                {"type": "minecraft:crafting_shaped", "key": {"#": "#minecraft:planks"}, "pattern": ["#", "#"], "result": {"count": 4, "id": "minecraft:stick"}}""",
            "minecraft:crafting_table", """
                {"type": "minecraft:crafting_shaped", "key": {"#": "#minecraft:planks"}, "pattern": ["##", "##"], "result": {"id": "minecraft:crafting_table"}}""",
            "minecraft:wooden_pickaxe", """
                {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:stick", "X": "#minecraft:planks"}, "pattern": ["XXX", " # ", " # "], "result": {"id": "minecraft:wooden_pickaxe"}}""",
            "minecraft:stone_pickaxe", """
                {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:stick", "X": "minecraft:cobblestone"}, "pattern": ["XXX", " # ", " # "], "result": {"id": "minecraft:stone_pickaxe"}}""",
            "minecraft:iron_pickaxe", """
                {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:stick", "X": "minecraft:iron_ingot"}, "pattern": ["XXX", " # ", " # "], "result": {"id": "minecraft:iron_pickaxe"}}""",
            "minecraft:furnace", """
                {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:cobblestone"}, "pattern": ["###", "# #", "###"], "result": {"id": "minecraft:furnace"}}""",
            "minecraft:iron_ingot_from_smelting_raw_iron", """
                {"type": "minecraft:smelting", "cookingtime": 200, "ingredient": "minecraft:raw_iron", "result": {"id": "minecraft:iron_ingot"}}""",
            "minecraft:glass", """
                {"type": "minecraft:smelting", "cookingtime": 200, "ingredient": "minecraft:sand", "result": {"id": "minecraft:glass"}}"""));
        tags.put("minecraft:planks", "{\"values\": [\"minecraft:oak_planks\"]}");
        tags.put("minecraft:oak_logs", "{\"values\": [\"minecraft:oak_log\"]}");
        return Recipes.load(r, tags);
    }

    static ItemPlanner planner() {
        Map<String, Integer> tier = Map.of("minecraft:stone", 1, "minecraft:cobblestone", 1, "minecraft:coal_ore", 1,
            "minecraft:iron_ore", 2, "minecraft:deepslate_iron_ore", 2);
        Map<String, Integer> burn = Map.of("minecraft:coal", 1600, "minecraft:oak_planks", 300);
        return new ItemPlanner(recipes(), drops(), b -> tier.getOrDefault(b, 0), f -> burn.getOrDefault(f, 0),
            List.of("minecraft:coal", "minecraft:oak_planks"), ItemPlanner.Rules.ALL);
    }

    @Test
    void readsDropsWithoutSilkTouch() {
        Drops d = drops();
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), d.blocksDropping("minecraft:raw_iron"));
        assertTrue(d.blocksDropping("minecraft:iron_ore").isEmpty(), "silk touch only");
        assertTrue(d.blocksDropping("minecraft:glass").isEmpty());
        assertEquals(List.of("minecraft:cobblestone", "minecraft:stone"), d.blocksDropping("minecraft:cobblestone"));
    }

    @Test
    void woodenPickaxeFromNothing() {
        ItemPlanner.Plan p = planner().plan("minecraft:wooden_pickaxe", 1, Map.of(), 0, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(ItemPlanner.Kind.MINE, p.actions().get(0).kind());
        assertEquals("minecraft:oak_log", p.actions().get(0).item());
        assertEquals(2, p.actions().get(0).count(), p.toString()); // 3 planks + 2 for the sticks: 2 logs, mined in one go
        assertEquals(new ItemPlanner.Action(ItemPlanner.Kind.CRAFT, "minecraft:wooden_pickaxe", 1, List.of()),
            p.actions().get(p.actions().size() - 1));
    }

    @Test
    void ironPickaxeClimbsTheTiers() {
        ItemPlanner.Plan p = planner().plan("minecraft:iron_pickaxe", 1, Map.of(), 0, false, false);
        assertTrue(p.ok(), p.toString());
        List<String> steps = p.actions().stream().map(a -> a.kind() + " " + a.item().replace("minecraft:", "")).toList();
        int wooden = steps.indexOf("CRAFT wooden_pickaxe"), stone = steps.indexOf("CRAFT stone_pickaxe");
        int cobble = steps.indexOf("MINE cobblestone"), iron = steps.indexOf("MINE raw_iron"), smelt = steps.indexOf("SMELT iron_ingot");
        assertTrue(wooden >= 0 && wooden < cobble && cobble < stone && stone < iron && iron < smelt, steps.toString());
        assertTrue(steps.contains("CRAFT furnace"), steps.toString());
        assertTrue(steps.indexOf("CRAFT furnace") < smelt, steps.toString());
        assertEquals("CRAFT iron_pickaxe", steps.get(steps.size() - 1));
        ItemPlanner.Action mineIron = p.actions().get(iron);
        assertEquals(3, mineIron.count());
        assertEquals(List.of("minecraft:deepslate_iron_ore", "minecraft:iron_ore"), mineIron.blocks());
    }

    @Test
    void usesWhatItHasAndTheToolTierItHas() {
        Map<String, Integer> have = Map.of("minecraft:raw_iron", 3, "minecraft:stick", 2, "minecraft:coal", 1);
        ItemPlanner.Plan p = planner().plan("minecraft:iron_pickaxe", 1, have, 2, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.SMELT, "minecraft:iron_ingot", 3, List.of()),
            new ItemPlanner.Action(ItemPlanner.Kind.CRAFT, "minecraft:iron_pickaxe", 1, List.of())), p.actions());
    }

    @Test
    void prefersCraftingSticksOverDeadBushes() {
        ItemPlanner.Plan p = planner().plan("minecraft:stick", 4, Map.of("minecraft:oak_planks", 2), 0, true, true);
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.CRAFT, "minecraft:stick", 4, List.of())), p.actions());
    }

    @Test
    void saysWhyItCant() {
        ItemPlanner.Plan p = planner().plan("minecraft:glass", 1, Map.of(), 0, true, true);
        assertFalse(p.ok());
        assertTrue(p.problem().contains("sand"), p.problem());
        ItemPlanner noMining = new ItemPlanner(recipes(), drops(), b -> 0, f -> 0, List.of(), new ItemPlanner.Rules(false, true, true, true));
        assertFalse(noMining.plan("minecraft:oak_log", 1, Map.of(), 0, true, true).ok());
        // no crafting table near and placing off: no 3x3 recipes
        ItemPlanner noPlacing = new ItemPlanner(recipes(), drops(), b -> 0, f -> 0, List.of(), new ItemPlanner.Rules(true, true, false, false));
        ItemPlanner.Plan pick = noPlacing.plan("minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_log", 5), 0, false, false);
        assertFalse(pick.ok());
        assertTrue(pick.problem().contains("crafting table"), pick.problem());
    }
}
