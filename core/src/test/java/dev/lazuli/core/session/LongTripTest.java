package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTripTest {
    @Test
    void offAxisTripThroughUnknownTerrainPlansQuickly() {
        GridWorld w = new GridWorld(BlockKind.UNKNOWN);
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add);
        long t0 = System.nanoTime();
        s.start(new GoalBlock(2000, 64, 700), false, Pos.pack(0, 64, 0));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("LONGTRIP " + ms + " ms, msgs=" + msgs);
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
        assertTrue(ms < 3000, "took " + ms + " ms");
    }

    static int hill(int x, int z) {
        return 64 + (int) Math.round(5 * Math.sin(x * 0.07) * Math.cos(z * 0.05) + 1.2 * Math.sin(x * 0.21 + z * 0.17)
            + 0.8 * Math.cos(z * 0.33 - x * 0.11));
    }

    /** Hills used to sprout thousands of hopeless jump edges (13 s for this trip); now they're priced by physics. */
    @Test
    void hillyTripWithParkourAndDiggingPlansQuickly() {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int x = -20; x <= 100; x++) for (int z = -40; z <= 40; z++) {
            int h = hill(x, z);
            w.fill(x, 50, z, x, h, z, BlockKind.SOLID);
            if ((x * 31 + z * 17) % 53 == 0) w.fill(x, h + 1, z, x, h + 5, z, BlockKind.SOLID); // tree trunks
        }
        Interaction both = new Interaction(true, true, 64, p -> Interaction.handTicks(p, false));
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> both);
        long t0 = System.nanoTime();
        s.start(new GoalBlock(80, hill(80, 20) + 1, 20), false, Pos.pack(1, hill(1, 0) + 1, 0));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("HILLS " + ms + " ms, msgs=" + msgs);
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
        assertTrue(ms < 3000, "took " + ms + " ms");
    }

    /**
     * A goal in mid-air with a fast pickaxe: searching backward, blocks inside the ground and in the air used to look
     * as cheap as the surface, and with an inflated heuristic one corrected jump cost re-expanded them for a second.
     */
    @Test
    void airGoalWithFastToolsPlansQuickly() {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int x = -60; x <= 100; x++) for (int z = -60; z <= 60; z++) {
            int h = hill(x, z);
            w.fill(x, 0, z, x, h, z, BlockKind.SOLID);
            if ((x * 31 + z * 17) % 53 == 0) w.fill(x, h + 1, z, x, h + 5, z, BlockKind.SOLID);
        }
        Interaction pickaxe = new Interaction(true, true, 64, p -> 4);
        for (int up = 1; up <= 3; up += 2) {
            List<String> msgs = new ArrayList<>();
            PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add, () -> pickaxe);
            long t0 = System.nanoTime();
            s.start(new GoalBlock(20, hill(20, 5) + 1 + up, 5), false, Pos.pack(1, hill(1, 0) + 1, 0));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("AIRGOAL +" + up + " " + ms + " ms, msgs=" + msgs);
            assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
            assertTrue(ms < 400, "took " + ms + " ms");
        }
    }
}
