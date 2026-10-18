package dev.lazuli.core.inventory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RestockTest {
    private static final List<String> BLOCKS = List.of("minecraft:cobblestone", "minecraft:dirt");

    @Test
    void readsItemsAndGroups() {
        var w = Restock.parse("food:16, torch:32 ,pickaxe:1,blocks:64,bogus", BLOCKS);
        assertEquals(4, w.size());
        assertEquals("food", w.get(0).name());
        assertTrue(w.get(0).ids().contains("minecraft:bread"));
        assertEquals(List.of("minecraft:torch"), w.get(1).ids());
        assertEquals("minecraft:netherite_pickaxe", w.get(2).ids().get(0));
        assertEquals(BLOCKS, w.get(3).ids());
    }

    @Test
    void refillsOnlyBelowHalfAndFromWhatAChestHas() {
        var w = Restock.parse("torch:32,food:16", BLOCKS);
        Map<String, Integer> have = Map.of("minecraft:torch", 20, "minecraft:bread", 2);
        Set<String> stored = Set.of("minecraft:torch", "minecraft:cooked_beef");
        ItemCount next = Restock.next(w, ids -> ids.stream().mapToInt(i -> have.getOrDefault(i, 0)).sum(), stored::contains, Set.of());
        // 20 torches is over half of 32; 2 food is under 8: the food a chest has, up to 16
        assertEquals(new ItemCount("minecraft:cooked_beef", 14), next);
    }

    @Test
    void aToolWantsOneWhenThereIsNone() {
        var w = Restock.parse("pickaxe:1", BLOCKS);
        Set<String> stored = Set.of("minecraft:iron_pickaxe", "minecraft:stone_pickaxe");
        assertEquals(new ItemCount("minecraft:iron_pickaxe", 1), Restock.next(w, ids -> 0, stored::contains, Set.of()));
        assertNull(Restock.next(w, ids -> 1, stored::contains, Set.of()));
    }

    @Test
    void skipsWhatItGaveUpOn() {
        var w = Restock.parse("torch:32", BLOCKS);
        assertNull(Restock.next(w, ids -> 0, id -> true, Set.of("torch")));
    }

    @Test
    void builtInNamesForArrowsTotemShieldAndWater() {
        var w = Restock.parse("arrows:32,totem:1,shield:1,water_bucket:1", BLOCKS);
        assertEquals(4, w.size());
        assertTrue(w.get(0).ids().contains("minecraft:arrow"));
        assertEquals(List.of("minecraft:totem_of_undying"), w.get(1).ids());
        assertEquals(List.of("minecraft:shield"), w.get(2).ids());
        assertEquals(List.of("minecraft:water_bucket"), w.get(3).ids());
    }
}
