package dev.lazuli.core.probe;

import dev.lazuli.core.craft.*;
import org.junit.jupiter.api.Test;
import java.util.Map;

class PlanPrintProbe {
    @Test
    void print() throws Exception {
        var m = Class.forName("dev.lazuli.core.craft.ItemPlannerTest").getDeclaredMethod("planner");
        m.setAccessible(true);
        ItemPlanner pl = (ItemPlanner) m.invoke(null);
        for (var a : pl.batching(true).plan("minecraft:iron_pickaxe", 1, Map.of(), 0, false, false).actions()) System.out.println("PLAN " + a);
    }
}
