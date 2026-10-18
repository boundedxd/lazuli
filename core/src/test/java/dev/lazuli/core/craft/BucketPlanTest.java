package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** #getitem for water, lava and milk buckets, and obsidian cast from water over lava. */
class BucketPlanTest {
    private static final String BUCKET = "minecraft:bucket", WATER = "minecraft:water_bucket", LAVA = "minecraft:lava_bucket",
        MILK = "minecraft:milk_bucket", OBSIDIAN = "minecraft:obsidian", DPICK = "minecraft:diamond_pickaxe", INGOT = "minecraft:iron_ingot";

    private static ItemPlanner planner(ItemPlanner.Rules rules) {
        Map<String, String> r = new HashMap<>();
        r.put(BUCKET, """
            {"type": "minecraft:crafting_shaped", "key": {"#": "minecraft:iron_ingot"}, "pattern": ["# #", " # "], "result": {"id": "minecraft:bucket"}}""");
        Map<String, Integer> tier = Map.of("minecraft:obsidian", 4);
        return new ItemPlanner(Recipes.load(r, Map.of()), ItemPlannerTest.drops(), b -> tier.getOrDefault(b, 0), f -> 0, List.of(), rules);
    }

    private static List<String> steps(ItemPlanner.Plan p) {
        return p.actions().stream().map(a -> a.kind() + " " + a.item().replace("minecraft:", "")).toList();
    }

    @Test
    void waterBucketFromABucket() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).plan(WATER, 1, Map.of(BUCKET, 1), 0, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.BUCKET, WATER, 1, List.of("minecraft:water"))), p.actions());
    }

    @Test
    void makesTheBucketFirst() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).plan(LAVA, 1, Map.of(INGOT, 3), 0, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of("CRAFT bucket", "BUCKET lava_bucket"), steps(p));
        assertEquals(List.of("minecraft:lava"), p.actions().get(1).blocks());
    }

    @Test
    void milkComesFromACow() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).plan(MILK, 2, Map.of(BUCKET, 2), 0, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.BUCKET, MILK, 2, List.of("minecraft:cow"))), p.actions());
    }

    @Test
    void bucketsOffMeansNoWay() {
        ItemPlanner.Rules off = new ItemPlanner.Rules(true, true, true, true, true, false);
        assertFalse(planner(off).plan(WATER, 1, Map.of(BUCKET, 1), 0, true, true).ok());
    }

    @Test
    void obsidianIsCastWhereLavaIsAround() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).nearby(b -> b.equals("minecraft:lava") ? 1 : 0)
            .plan(OBSIDIAN, 10, Map.of(WATER, 1, DPICK, 1), 4, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of(new ItemPlanner.Action(ItemPlanner.Kind.CAST, OBSIDIAN, 10, List.of("minecraft:lava"))), p.actions());
    }

    @Test
    void castingGetsTheWaterBucketToo() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).nearby(b -> b.equals("minecraft:lava") ? 1 : 0)
            .plan(OBSIDIAN, 4, Map.of(BUCKET, 1, DPICK, 1), 4, true, true);
        assertTrue(p.ok(), p.toString());
        assertEquals(List.of("BUCKET water_bucket", "CAST obsidian"), steps(p));
    }

    @Test
    void noLavaLoadedNoCasting() {
        ItemPlanner.Plan p = planner(ItemPlanner.Rules.ALL).nearby(b -> 0)
            .plan(OBSIDIAN, 4, Map.of(WATER, 1, DPICK, 1), 4, true, true);
        assertTrue(p.actions().stream().noneMatch(a -> a.kind() == ItemPlanner.Kind.CAST), p.toString());
    }
}
