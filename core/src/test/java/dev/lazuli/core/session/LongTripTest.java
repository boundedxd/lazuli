package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
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
}
