package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The planner takes what remembered chests hold before mining or crafting it. */
class StoredPlanTest {
    private static final String BUCKET = "minecraft:bucket", INGOT = "minecraft:iron_ingot", DPICK = "minecraft:diamond_pickaxe",
        OBSIDIAN = "minecraft:obsidian";

    private static ItemPlanner planner() {
        Map<String, String> r = new HashMap<>();
        r.put(BUCKET, """
            {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:iron_ingot"}, "pattern": ["# #", " # "], "result": {"id": "minecraft:bucket"}}""");
        Map<String, Integer> tier = Map.of("minecraft:iron_ore", 2, "minecraft:deepslate_iron_ore", 2);
        return new ItemPlanner(Recipes.load(r, Map.of()), ItemPlannerTest.drops(), b -> tier.getOrDefault(b, 0), f -> 0, List.of(),
            ItemPlanner.Rules.ALL);
    }

    private static List<String> steps(ItemPlanner.Plan p) {
        return p.actions().stream().map(a -> a.kind() + " " + a.count() + " " + a.item().replace("minecraft:", "")).toList();
    }

    @Test
    void ingredientsComeOutOfTheChest() {
        ItemPlanner.Plan p = planner().stored(Map.of(INGOT, 5)).plan(BUCKET, 1, Map.of(), 0, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of("TAKE 3 iron_ingot", "CRAFT 1 bucket"), steps(p));
    }

    @Test
    void theFinishedItemIsTakenWhole() {
        ItemPlanner.Plan p = planner().stored(Map.of(BUCKET, 2)).plan(BUCKET, 2, Map.of(), 0, true, true);
        assertEquals(List.of("TAKE 2 bucket"), steps(p));
    }

    @Test
    void theNeededPickaxeIsTakenInsteadOfMade() {
        ItemPlanner.Plan p = planner().stored(Map.of("minecraft:stone_pickaxe", 1)).plan("minecraft:raw_iron", 3, Map.of(), 0, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of("TAKE 1 stone_pickaxe", "MINE 3 raw_iron"), steps(p));
    }

    @Test
    void nothingStoredPlansAsBefore() {
        assertEquals(planner().plan(BUCKET, 1, Map.of(INGOT, 3), 0, true, true).actions(),
            planner().stored(Map.of()).plan(BUCKET, 1, Map.of(INGOT, 3), 0, true, true).actions());
    }
}
