package dev.lazuli.core.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.lazuli.core.world.Pos.pack;
import static org.junit.jupiter.api.Assertions.*;

class GhostTrailTest {
    private final List<Long> straight = List.of(pack(0, 64, 0), pack(1, 64, 0), pack(2, 64, 0), pack(3, 64, 0));

    @Test
    void onTheLineIsNotOff() {
        assertFalse(GhostTrail.offPath(straight, 1.9, 64, 0.7));
        assertFalse(GhostTrail.offPath(straight, 3.5, 64, 0.5));
    }

    @Test
    void sidewaysOrFarBelowIsOff() {
        assertTrue(GhostTrail.offPath(straight, 2, 64, 2.2));
        assertTrue(GhostTrail.offPath(straight, 2, 58, 0.5)); // fell
    }

    @Test
    void keepsTheTripUntilTheNextAndMarksWhereItWentWrong() {
        GhostTrail g = new GhostTrail(100);
        g.tick(true, 1, straight, 0.5, 64, 0.5);
        g.tick(true, 1, straight, 1.5, 64, 2.5);
        g.tick(false, 1, List.of(), 0, 0, 0); // ended: kept, nothing added
        assertEquals(2, g.samples().size());
        assertFalse(g.samples().get(0).off());
        assertTrue(g.samples().get(1).off());
        // a replan elsewhere: the old nodes are abandoned
        List<Long> other = List.of(pack(0, 64, 0), pack(0, 64, 1));
        g.tick(true, 1, other, 0.5, 64, 1.5);
        assertTrue(g.abandoned().contains(pack(3, 64, 0)));
        g.tick(true, 2, straight, 0.5, 64, 0.5);
        assertEquals(1, g.samples().size());
        assertTrue(g.abandoned().isEmpty());
    }

    @Test
    void dropsTheOldestPastTheMax() {
        GhostTrail g = new GhostTrail(3);
        for (int i = 0; i < 5; i++) g.tick(true, 1, straight, i * 0.5, 64, 0.5);
        assertEquals(3, g.samples().size());
        assertEquals(1.0, g.samples().get(0).x(), 1e-9);
    }
}
