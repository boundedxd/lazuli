package dev.lazuli.core.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RechaseTest {
    @Test
    void keepsAWalkWhileTheMobWandersNearby() {
        assertTrue(Rechase.keep(true, 4, 20));
        assertTrue(Rechase.keep(true, Rechase.MOVED_SQ, Rechase.MAX_TICKS));
    }

    @Test
    void replansWhenTheMobMovedAwayOrTheWalkIsStale() {
        assertFalse(Rechase.keep(true, Rechase.MOVED_SQ + 1, 20));
        assertFalse(Rechase.keep(true, 1, Rechase.MAX_TICKS + 1));
    }

    @Test
    void neverKeepsAFinishedTrip() {
        assertFalse(Rechase.keep(false, 0, 0));
    }
}
