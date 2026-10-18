package dev.lazuli.core.storage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SortPlanTest {
    private static int stack(String item) {
        return item.equals("ender_pearl") ? 16 : 64;
    }

    @Test
    void eachItemGoesWhereMostOfItAlreadyIs() {
        var plan = SortPlan.of(List.of(
            new SortPlan.Box("a", 27, Map.of("dirt", 300, "stone", 10)),
            new SortPlan.Box("b", 27, Map.of("stone", 200, "dirt", 5))), SortPlanTest::stack);
        assertEquals(Map.of("stone", 10), plan.misplaced("a"));
        assertEquals(Map.of("dirt", 5), plan.misplaced("b"));
        assertEquals(List.of("a"), plan.homes("dirt"));
        assertEquals(List.of("b"), plan.homes("stone"));
        assertFalse(plan.sorted());
    }

    @Test
    void tooMuchForOneBoxSpillsIntoTheNext() {
        // 2 x 27 stacks of cobble won't fit in one chest
        var plan = SortPlan.of(List.of(
            new SortPlan.Box("a", 27, Map.of("cobblestone", 27 * 64)),
            new SortPlan.Box("b", 27, Map.of("cobblestone", 20 * 64, "sand", 64))), SortPlanTest::stack);
        assertTrue(plan.homes("cobblestone").containsAll(List.of("a", "b")));
        assertEquals(List.of("b"), plan.homes("sand"));
        assertTrue(plan.sorted());
    }

    @Test
    void whatFitsNowhereElseStaysWhereItIs() {
        // a counted as full (a deposit there failed), b one slot taken by cobblestone: the dirt has nowhere to go
        var plan = SortPlan.of(List.of(
            new SortPlan.Box("a", 0, Map.of("dirt", 10)),
            new SortPlan.Box("b", 1, Map.of("cobblestone", 64))), SortPlanTest::stack);
        assertTrue(plan.sorted(), "nowhere to go: " + plan.misplaced("a") + " " + plan.misplaced("b"));
    }

    @Test
    void anAlreadySortedRoomHasNothingToMove() {
        var plan = SortPlan.of(List.of(
            new SortPlan.Box("a", 27, Map.of("dirt", 64)),
            new SortPlan.Box("b", 27, Map.of("ender_pearl", 32))), SortPlanTest::stack);
        assertTrue(plan.sorted());
        assertEquals(Map.of(), plan.misplaced("a"));
    }

    @Test
    void roomForCarriedItemsIsWhereItsHomeHasSpace() {
        var plan = SortPlan.of(List.of(
            new SortPlan.Box("a", 27, Map.of("dirt", 100, "stone", 64)),
            new SortPlan.Box("b", 27, Map.of("stone", 640))), SortPlanTest::stack);
        // the 64 stone out of a goes to b
        assertEquals(Map.of("stone", 64), plan.misplaced("a"));
        assertEquals(64, plan.room("b", "stone"));
        assertEquals(0, plan.room("a", "stone"));
    }
}
