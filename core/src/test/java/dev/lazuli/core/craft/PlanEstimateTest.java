package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanEstimateTest {
    @Test
    void oresTakeLongerThanStoneAndSmeltingAddsUp() {
        var stone = new ItemPlanner.Action(ItemPlanner.Kind.MINE, "minecraft:cobblestone", 8, List.of("minecraft:stone"));
        var iron = new ItemPlanner.Action(ItemPlanner.Kind.MINE, "minecraft:raw_iron", 8, List.of("minecraft:iron_ore"));
        var smelt = new ItemPlanner.Action(ItemPlanner.Kind.SMELT, "minecraft:iron_ingot", 8, List.of());
        assertTrue(PlanEstimate.seconds(iron) > PlanEstimate.seconds(stone));
        assertEquals(88, PlanEstimate.seconds(smelt), 1e-9);
        assertEquals(PlanEstimate.seconds(stone) + PlanEstimate.seconds(smelt), PlanEstimate.seconds(List.of(stone, smelt)), 1e-9);
    }

    @Test
    void formats() {
        assertEquals("45s", PlanEstimate.format(45));
        assertEquals("3m 20s", PlanEstimate.format(200));
        assertEquals("1h 05m", PlanEstimate.format(3900));
    }
}
