package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmithingTest {
    static final String TEMPLATE = "minecraft:netherite_upgrade_smithing_template", INGOT = "minecraft:netherite_ingot",
        DPICK = "minecraft:diamond_pickaxe", NPICK = "minecraft:netherite_pickaxe";

    static Recipes recipes() {
        Map<String, String> r = new HashMap<>(), tags = new HashMap<>();
        r.put("minecraft:netherite_pickaxe_smithing", """
            {"type": "minecraft:smithing_transform", "addition": "minecraft:netherite_ingot", "base": "minecraft:diamond_pickaxe",
             "template": "minecraft:netherite_upgrade_smithing_template", "result": {"count": 1, "id": "minecraft:netherite_pickaxe"}}""");
        r.put("minecraft:smithing_table", """
            {"type": "minecraft:crafting_shaped", "key": {"#": "#minecraft:planks", "@": "minecraft:iron_ingot"}, "pattern": ["@@", "##", "##"], "result": {"id": "minecraft:smithing_table"}}""");
        r.put("minecraft:oak_planks", """
            {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:oak_log"], "result": {"count": 4, "id": "minecraft:oak_planks"}}""");
        r.put("minecraft:crafting_table", """
            {"type": "minecraft:crafting_shaped", "key": {"#": "#minecraft:planks"}, "pattern": ["##", "##"], "result": {"id": "minecraft:crafting_table"}}""");
        r.put("minecraft:blue_shulker_box", """
            {"type": "minecraft:crafting_transmute", "category": "misc", "input": "#minecraft:shulker_boxes", "material": "minecraft:blue_dye",
             "result": {"count": 1, "id": "minecraft:blue_shulker_box"}}""");
        tags.put("minecraft:planks", "{\"values\": [\"minecraft:oak_planks\"]}");
        tags.put("minecraft:shulker_boxes", "{\"values\": [\"minecraft:shulker_box\", \"minecraft:red_shulker_box\"]}");
        return Recipes.load(r, tags);
    }

    static ItemPlanner planner(ItemPlanner.Rules rules) {
        return new ItemPlanner(recipes(), ItemPlannerTest.drops(), b -> 0, f -> 0, List.of(), rules);
    }

    @Test
    void readsSmithingTransform() {
        List<SmithRecipe> s = recipes().smithing(NPICK);
        assertEquals(1, s.size());
        assertEquals(java.util.Set.of(TEMPLATE), s.get(0).template());
        assertEquals(java.util.Set.of(DPICK), s.get(0).base());
        assertEquals(java.util.Set.of(INGOT), s.get(0).addition());
    }

    @Test
    void readsCraftingTransmute() {
        List<Recipe> r = recipes().producing("minecraft:blue_shulker_box");
        assertEquals(1, r.size());
        assertEquals(List.of(java.util.Set.of("minecraft:shulker_box", "minecraft:red_shulker_box"), java.util.Set.of("minecraft:blue_dye")),
            r.get(0).cells());
    }

    @Test
    void netheritePickaxeAtANearbyTable() {
        ItemPlanner planner = planner(ItemPlanner.Rules.ALL).smithingTable(true);
        ItemPlanner.Plan p = planner.plan(NPICK, 1, Map.of(DPICK, 1, TEMPLATE, 1, INGOT, 1), 4, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.SMITH, NPICK, 1, List.of())), p.actions());
    }

    @Test
    void makesAndPlacesASmithingTableWhenNoneIsNear() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).plan(NPICK, 1,
            Map.of(DPICK, 1, TEMPLATE, 1, INGOT, 1, "minecraft:iron_ingot", 2, "minecraft:oak_planks", 4), 4, true, true);
        assertTrue(p.ok(), p.toString());
        List<String> steps = p.actions().stream().map(a -> a.kind() + " " + a.item().replace("minecraft:", "")).toList();
        assertEquals(List.of("CRAFT smithing_table", "SMITH netherite_pickaxe"), steps);
    }

    @Test
    void smithingOffMeansNoWay() {
        ItemPlanner.Rules off = new ItemPlanner.Rules(true, true, true, true, false);
        ItemPlanner.Plan p = planner(off).smithingTable(true).plan(NPICK, 1, Map.of(DPICK, 1, TEMPLATE, 1, INGOT, 1), 4, true, true);
        assertFalse(p.ok(), p.toString());
    }

    @Test
    void noTemplateNoUpgrade() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).smithingTable(true).plan(NPICK, 1, Map.of(DPICK, 1, INGOT, 1), 4, true, true);
        assertFalse(p.ok(), p.toString());
        assertTrue(p.problem().contains("template"), p.problem());
    }
}
