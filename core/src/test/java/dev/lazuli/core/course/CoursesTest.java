package dev.lazuli.core.course;

import dev.lazuli.core.control.InteractionCourseTest;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every in-game test course, driven closed-loop in the sim with exactly the building it's meant to need. */
class CoursesTest {
    /** A pickaxe-ish break time: about a third of a second for stone. */
    static final Interaction.BreakTime TOOLS = p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4));

    static GridWorld build(Course c) {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (Course.Fill f : c.fills()) {
            for (int x = f.x0(); x <= f.x1(); x++)
                for (int y = f.y0(); y <= f.y1(); y++)
                    for (int z = f.z0(); z <= f.z1(); z++) {
                        if (f.m() == Course.Material.AIR) w.set(x, y, z, BlockKind.AIR);
                        else w.setPhysics(x, y, z, f.m().physics());
                    }
        }
        return w;
    }

    @TestFactory
    List<DynamicTest> everyCourse() {
        return Courses.all().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            GridWorld w = build(c);
            Interaction in = new Interaction(c.needsBreak(), c.needsPlace(), c.needsPlace() ? 64 : 0, TOOLS);
            int[] s = c.start(), g = c.goal();
            var r = InteractionCourseTest.drive(w, in, in.throwaways(), true, s[0], s[1], s[2], g[0], g[1], g[2], 4000);
            System.out.printf("COURSE %-14s arrived=%s ticks=%d breaks=%d places=%d lowest=%.2f%n", c.name(), r.arrived(),
                r.ticks(), r.breaks(), r.places(), r.lowestY());
            assertTrue(r.arrived(), c.name() + ": " + r.log());
            assertTrue(r.lowestY() >= c.lowest() - 0.01, c.name() + " went below " + c.lowest() + " (took the long way round)\n" + r.log());
        })).toList();
    }
}
