package dev.lazuli.core.control;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import static dev.lazuli.core.control.CourseTest.assertArrives;
import static dev.lazuli.core.control.CourseTest.drive;
import static dev.lazuli.core.control.CourseTest.strip;

/** Harder jumps, closed loop (see {@link CourseTest}). */
class ParkourCourseTest {
    /** {@link CourseTest#strip} under a ceiling {@code room} blocks above the floor. */
    private static GridWorld roofed(int room, int... ranges) {
        GridWorld w = strip(ranges);
        w.fill(ranges[0] - 2, room + 1, -1, ranges[ranges.length - 1] + 2, room + 1, 1, BlockKind.SOLID);
        return w;
    }

    @Test
    void headHitterOneGap() {
        assertArrives(drive(roofed(2, -4, 0, 2, 8), -4, 1, 0, 8, 1, 0, 300), 300);
    }

    @Test
    void oneGapsInATwoHighTunnel() {
        // (a two-gap can't be done under a 2-high ceiling: a capped hop flies about 0.6 blocks)
        assertArrives(drive(roofed(2, -4, 0, 2, 3, 5, 6, 8, 12), -4, 1, 0, 12, 1, 0, 300), 300);
    }

    @Test
    void threeHighCeilingThreeGap() {
        assertArrives(drive(roofed(3, -6, 0, 4, 8), -6, 1, 0, 8, 1, 0, 300), 300);
    }

    /** Run-up at y = 0 from x = -6 to 0, landing platform from {@code gap + 1} on, {@code dy} higher. */
    private static GridWorld gapTo(int gap, int dy) {
        GridWorld w = strip(-6, 0);
        w.fill(gap + 1, dy - 4, 0, gap + 6, dy, 0, BlockKind.SOLID);
        return w;
    }

    @Test
    void threeGapUpOne() {
        assertArrives(drive(gapTo(3, 1), -6, 1, 0, 8, 2, 0, 300), 300);
    }

    @Test
    void fourGapDownOne() {
        assertArrives(drive(gapTo(4, -1), -6, 1, 0, 9, 0, 0, 300), 300);
    }

    @Test
    void fourGapAfterAHopRunUp() {
        assertArrives(drive(gapTo(4, 0), -6, 1, 0, 9, 1, 0, 300), 300);
    }
}
