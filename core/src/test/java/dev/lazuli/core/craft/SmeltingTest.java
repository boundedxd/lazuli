package dev.lazuli.core.craft;

import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.inventory.Stack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmeltingTest {
    static Recipes vanilla() {
        Map<String, String> recipes = new HashMap<>(), tags = new HashMap<>();
        recipes.put("minecraft:iron_ingot", """
            {"type": "minecraft:smelting", "category": "misc", "cookingtime": 200, "experience": 0.7, "group": "iron_ingot",
             "ingredient": "minecraft:iron_ore", "result": {"id": "minecraft:iron_ingot"}}""");
        recipes.put("minecraft:iron_ingot_from_smelting_raw_iron", """
            {"type": "minecraft:smelting", "cookingtime": 200, "ingredient": "minecraft:raw_iron", "result": {"id": "minecraft:iron_ingot"}}""");
        recipes.put("minecraft:iron_ingot_from_blasting_raw_iron", """
            {"type": "minecraft:blasting", "cookingtime": 100, "ingredient": "minecraft:raw_iron", "result": {"id": "minecraft:iron_ingot"}}""");
        recipes.put("minecraft:cooked_beef_from_smoking", """
            {"type": "minecraft:smoking", "cookingtime": 100, "ingredient": "minecraft:beef", "result": {"id": "minecraft:cooked_beef"}}""");
        recipes.put("minecraft:cooked_beef", """
            {"type": "minecraft:smelting", "cookingtime": 200, "ingredient": "minecraft:beef", "result": {"id": "minecraft:cooked_beef"}}""");
        recipes.put("minecraft:charcoal", """
            {"type": "minecraft:smelting", "cookingtime": 200, "ingredient": "#minecraft:logs_that_burn",
             "result": {"id": "minecraft:charcoal"}}""");
        recipes.put("minecraft:cooked_beef_from_campfire_cooking", """
            {"type": "minecraft:campfire_cooking", "cookingtime": 600, "ingredient": "minecraft:beef", "result": {"id": "minecraft:cooked_beef"}}""");
        tags.put("minecraft:logs_that_burn", """
            {"values": ["minecraft:oak_log", "minecraft:birch_log"]}""");
        return Recipes.load(recipes, tags);
    }

    @Test
    void readsFurnaceRecipes() {
        Recipes r = vanilla();
        List<SmeltRecipe> iron = r.smelting("minecraft:iron_ingot");
        assertEquals(3, iron.size(), iron.toString());
        assertTrue(iron.stream().anyMatch(s -> s.furnace() == SmeltRecipe.Furnace.BLAST_FURNACE && s.cookTicks() == 100));
        assertEquals(2, r.smelting("minecraft:cooked_beef").size(), "campfires aren't furnaces");
        assertTrue(r.producing("minecraft:iron_ingot").isEmpty(), "smelting isn't crafting");
        assertTrue(r.smelting("minecraft:charcoal").get(0).input().contains("minecraft:birch_log"));
    }

    @Test
    void picksTheInputItHasAndTheFastestFurnace() {
        Recipes r = vanilla();
        Smelting.Job job = Smelting.job(r, "minecraft:iron_ingot", Map.of("minecraft:raw_iron", 5, "minecraft:iron_ore", 2));
        assertEquals("minecraft:raw_iron", job.input());
        assertEquals(SmeltRecipe.Furnace.FURNACE, job.recipe().furnace());
        assertEquals(SmeltRecipe.Furnace.BLAST_FURNACE, job.fast().furnace());
        Smelting.Job charcoal = Smelting.job(r, "minecraft:charcoal", Map.of("minecraft:oak_log", 1, "minecraft:birch_log", 9));
        assertEquals("minecraft:birch_log", charcoal.input());
        assertNull(charcoal.fast());
        assertNull(Smelting.job(r, "minecraft:iron_ingot", Map.of()));
        // asked by what goes in
        assertEquals("minecraft:iron_ingot", Smelting.job(r, "minecraft:raw_iron", Map.of("minecraft:raw_iron", 1)).recipe().result());
    }

    @Test
    void takesFuelInTheOrderGiven() {
        Map<String, Integer> burn = Map.of("minecraft:coal", 1600, "minecraft:oak_planks", 300, "minecraft:stick", 100);
        List<String> prefs = List.of("minecraft:coal", "minecraft:oak_planks", "minecraft:stick");
        // 10 items = 2000 ticks: one coal (8 items), then 2 planks (3 items)
        assertEquals(Map.of("minecraft:coal", 1, "minecraft:oak_planks", 2),
            Smelting.fuel(prefs, Map.of("minecraft:coal", 1, "minecraft:oak_planks", 64), i -> burn.getOrDefault(i, 0), 10));
        assertEquals(Map.of("minecraft:coal", 2),
            Smelting.fuel(prefs, Map.of("minecraft:coal", 5, "minecraft:oak_planks", 64), i -> burn.getOrDefault(i, 0), 10));
        assertNull(Smelting.fuel(prefs, Map.of("minecraft:stick", 3), i -> burn.getOrDefault(i, 0), 2));
    }

    @Test
    void putsExactlyAnAmountInOneSlot() {
        Stack[] s = new Stack[39];
        Arrays.fill(s, Stack.EMPTY);
        s[1] = new Stack("minecraft:coal", 1, 64); // some fuel already in
        s[10] = new Stack("minecraft:coal", 64, 64);
        s[11] = new Stack("minecraft:raw_iron", 3, 64);
        s[12] = new Stack("minecraft:raw_iron", 5, 64);
        Menu m = new Menu(s, slot -> Menu.range(3, 39));
        int[] inv = Menu.range(3, 39);
        m.applyAll(Moves.put(m, inv, 0, "minecraft:raw_iron", 7));
        m.applyAll(Moves.put(m, inv, 1, "minecraft:coal", 2));
        assertEquals(new Stack("minecraft:raw_iron", 7, 64), m.get(0));
        assertEquals(new Stack("minecraft:coal", 3, 64), m.get(1));
        assertEquals(1, m.count(inv, "minecraft:raw_iron"::equals));
        assertEquals(62, m.count(inv, "minecraft:coal"::equals));
        assertTrue(m.cursor().empty(), m.toString());
    }
}
