package dev.lazuli.core.probe;

import dev.lazuli.core.control.InteractionCourseTest;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

class DigDownProbeTest {
    @Test
    void probe() {
        GridWorld w = GridWorld.flatFloor(-20, -20, 20, 20).fill(-20, -80, -20, 20, 0, 20, BlockKind.SOLID);
        var r = InteractionCourseTest.drive(w, new dev.lazuli.core.move.Interaction(true, false, 0, p -> 4), 0, false, 0, 1, 0, new dev.lazuli.core.goal.GoalAdjacent(10, -50, 6), 10, 6, 8000);
        System.out.println("PROBE arrived=" + r.arrived() + " ticks=" + r.ticks() + " breaks=" + r.breaks());
        for (String l : r.log().split("\n")) if (l.startsWith("path")) System.out.println("PROBE " + (l.length() > 200 ? l.substring(0, 200) : l));
    }
}
