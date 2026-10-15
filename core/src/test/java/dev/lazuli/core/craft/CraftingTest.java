package dev.lazuli.core.craft;

import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Stack;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CraftingTest {
    /** Vanilla's own files (trimmed), as they sit in the game jar. */
    static Recipes vanilla() {
        Map<String, String> recipes = new HashMap<>(), tags = new HashMap<>();
        recipes.put("minecraft:stick", """
            {"type": "minecraft:crafting_shaped", "category": "misc", "group": "sticks",
             "key": {"#": "#minecraft:planks"}, "pattern": ["#", "#"], "result": {"count": 4, "id": "minecraft:stick"}}""");
        recipes.put("minecraft:stick_from_bamboo_item", """
            {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:bamboo"}, "pattern": ["#", "#"],
             "result": {"id": "minecraft:stick"}}""");
        recipes.put("minecraft:oak_planks", """
            {"type": "minecraft:crafting_shapeless", "ingredients": ["#minecraft:oak_logs"],
             "result": {"count": 4, "id": "minecraft:oak_planks"}}""");
        recipes.put("minecraft:birch_planks", """
            {"type": "minecraft:crafting_shapeless", "ingredients": ["#minecraft:birch_logs"],
             "result": {"count": 4, "id": "minecraft:birch_planks"}}""");
        recipes.put("minecraft:crafting_table", """
            {"type": "minecraft:crafting_shaped", "key": {"#": "#minecraft:planks"}, "pattern": ["##", "##"],
             "result": {"count": 1, "id": "minecraft:crafting_table"}, "show_notification": false}""");
        recipes.put("minecraft:wooden_pickaxe", """
            {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:stick", "X": "#minecraft:planks"},
             "pattern": ["XXX", " # ", " # "], "result": {"count": 1, "id": "minecraft:wooden_pickaxe"}}""");
        recipes.put("minecraft:flint_and_steel", """
            {"type": "minecraft:crafting_shapeless", "ingredients": ["minecraft:iron_ingot", ["minecraft:flint"]],
             "result": {"count": 1, "id": "minecraft:flint_and_steel"}}""");
        recipes.put("minecraft:iron_ingot_from_smelting", """
            {"type": "minecraft:smelting", "ingredient": "minecraft:raw_iron", "result": {"id": "minecraft:iron_ingot"}}""");
        tags.put("minecraft:planks", """
            {"values": ["minecraft:oak_planks", "minecraft:birch_planks", {"id": "mod:odd_planks", "required": false}]}""");
        tags.put("minecraft:oak_logs", """
            {"values": ["minecraft:oak_log", "minecraft:oak_wood", "#minecraft:stripped_oak"]}""");
        tags.put("minecraft:stripped_oak", """
            {"values": ["minecraft:stripped_oak_log"]}""");
        tags.put("minecraft:birch_logs", """
            {"values": ["minecraft:birch_log"]}""");
        return Recipes.load(recipes, tags);
    }

    @Test
    void readsShapedAndShapelessRecipesWithTags() {
        Recipes r = vanilla();
        List<Recipe> sticks = r.producing("minecraft:stick");
        assertEquals(2, sticks.size());
        Recipe stick = sticks.stream().filter(x -> x.id().equals("minecraft:stick")).findFirst().orElseThrow();
        assertEquals(4, stick.count());
        assertEquals(1, stick.width());
        assertEquals(2, stick.height());
        assertTrue(stick.fits(2));
        assertTrue(stick.cells().get(0).contains("mod:odd_planks"));
        assertTrue(r.producing("minecraft:oak_planks").get(0).cells().get(0).contains("minecraft:stripped_oak_log"));
        assertFalse(r.producing("minecraft:wooden_pickaxe").get(0).fits(2));
        assertEquals(1, sticks.stream().filter(x -> x.id().endsWith("bamboo_item")).findFirst().orElseThrow().count());
        assertTrue(r.producing("minecraft:iron_ingot").isEmpty(), "smelting isn't crafting");
        assertEquals(2, r.producing("minecraft:flint_and_steel").get(0).cells().size());
    }

    @Test
    void craftsWhatItHasStraightAway() {
        CraftTree.Result res = CraftTree.resolve(vanilla(), "minecraft:stick", 8, Map.of("minecraft:oak_planks", 10));
        assertTrue(res.ok(), res.toString());
        assertEquals(1, res.steps().size());
        CraftTree.Step s = res.steps().get(0);
        assertEquals("minecraft:stick", s.recipe().id());
        assertEquals(2, s.crafts());
        assertArrayEquals(new String[]{"minecraft:oak_planks", "minecraft:oak_planks"}, s.cells());
    }

    @Test
    void craftsTheIngredientsFirst() {
        // logs -> planks -> sticks -> pickaxe
        CraftTree.Result res = CraftTree.resolve(vanilla(), "minecraft:wooden_pickaxe", 1, Map.of("minecraft:oak_log", 2));
        assertTrue(res.ok(), res.toString());
        List<String> order = res.steps().stream().map(s -> s.recipe().id() + "x" + s.crafts()).toList();
        assertEquals("minecraft:wooden_pickaxex1", order.get(order.size() - 1), order.toString());
        assertTrue(order.indexOf("minecraft:stickx1") < order.size() - 1, order.toString());
        int planks = res.steps().stream().filter(s -> s.recipe().id().equals("minecraft:oak_planks")).mapToInt(CraftTree.Step::crafts).sum();
        assertEquals(2, planks, order.toString()); // 3 planks + 2 for the sticks = 5 -> two logs
    }

    @Test
    void mixesPlankKindsWhenOneKindIsShort() {
        CraftTree.Result res = CraftTree.resolve(vanilla(), "minecraft:crafting_table", 1,
            Map.of("minecraft:oak_planks", 2, "minecraft:birch_planks", 2));
        assertTrue(res.ok(), res.toString());
        assertEquals(1, res.steps().size());
        String[] cells = res.steps().get(0).cells();
        assertEquals(2, Arrays.stream(cells).filter("minecraft:oak_planks"::equals).count());
        assertEquals(2, Arrays.stream(cells).filter("minecraft:birch_planks"::equals).count());
    }

    @Test
    void saysWhatIsMissing() {
        CraftTree.Result res = CraftTree.resolve(vanilla(), "minecraft:flint_and_steel", 1, Map.of("minecraft:flint", 1));
        assertFalse(res.ok());
        assertEquals(Map.of("minecraft:iron_ingot", 1), res.missing());
        assertFalse(CraftTree.resolve(vanilla(), "minecraft:bedrock", 1, Map.of()).ok());
    }

    @Test
    void doesNotUseUpWhatItIsMaking() {
        // asked for 4 more sticks: the 4 it has don't count
        CraftTree.Result res = CraftTree.resolve(vanilla(), "minecraft:stick", 4,
            Map.of("minecraft:stick", 4, "minecraft:oak_planks", 2));
        assertTrue(res.ok(), res.toString());
        assertEquals(1, res.steps().get(0).crafts());
    }

    @Test
    void chainsAndCanStayShallow() {
        CraftTree.Result table = CraftTree.resolve(vanilla(), "minecraft:crafting_table", 1, Map.of("minecraft:oak_log", 2), true);
        assertTrue(table.ok(), table.toString());
        assertEquals(1, table.left().get("minecraft:crafting_table"));
        assertNull(table.left().get("minecraft:oak_planks")); // one log is just the 4 planks it needs
        assertEquals(1, table.left().get("minecraft:oak_log"));
        CraftTree.Result shallow = CraftTree.resolve(vanilla(), "minecraft:crafting_table", 1, Map.of("minecraft:oak_log", 2), false);
        assertEquals(Map.of("minecraft:oak_planks", 4), shallow.missing());
    }

    /** The player's own menu: result 0, grid 1-4, armor 5-8, inventory 9-44. */
    static Menu inventory(Object... contents) {
        Stack[] s = new Stack[46];
        Arrays.fill(s, Stack.EMPTY);
        for (int i = 0; i < contents.length; i += 3) s[(int) contents[i]] = new Stack((String) contents[i + 1], (int) contents[i + 2], 64);
        return new Menu(s, slot -> Menu.range(9, 45));
    }

    @Test
    void fillsTheGridWithExactlyEnough() {
        Menu m = inventory(9, "minecraft:oak_planks", 64, 10, "minecraft:birch_planks", 3);
        Recipe table = vanilla().producing("minecraft:crafting_table").get(0);
        String[] cells = {"minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:birch_planks"};
        int[] grid = {1, 2, 3, 4};
        m.applyAll(Crafter.fill(m, Menu.range(9, 45), grid, 2, table, cells, 3));
        for (int i = 0; i < 4; i++) assertEquals(new Stack(cells[i], 3, 64), m.get(grid[i]), m.toString());
        assertEquals(55, m.count(Menu.range(9, 45), "minecraft:oak_planks"::equals));
        assertEquals(0, m.count(Menu.range(9, 45), "minecraft:birch_planks"::equals));
        assertTrue(m.cursor().empty(), m.toString());
    }

    @Test
    void placesShapedRecipesTopLeftInABigGrid() {
        // a crafting table: result 0, grid 1-9, inventory 10-45
        Stack[] s = new Stack[46];
        Arrays.fill(s, Stack.EMPTY);
        s[20] = new Stack("minecraft:oak_planks", 64, 64);
        s[21] = new Stack("minecraft:stick", 64, 64);
        Menu m = new Menu(s, slot -> Menu.range(10, 46));
        Recipe pick = vanilla().producing("minecraft:wooden_pickaxe").get(0);
        String p = "minecraft:oak_planks", st = "minecraft:stick";
        m.applyAll(Crafter.fill(m, Menu.range(10, 46), Menu.range(1, 10), 3, pick, new String[]{p, p, p, null, st, null, null, st, null}, 2));
        assertEquals(p, m.get(1).item());
        assertEquals(p, m.get(3).item());
        assertTrue(m.get(4).empty());
        assertEquals(new Stack(st, 2, 64), m.get(8));
        assertTrue(m.cursor().empty());
    }

    @Test
    void batchesStopAtAStack() {
        assertEquals(64, Crafter.batch(200));
        assertEquals(5, Crafter.batch(5));
    }
}
