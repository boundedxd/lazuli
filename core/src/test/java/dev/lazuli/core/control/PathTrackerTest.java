package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathTrackerTest {
    private static List<Long> straight(int n) {
        List<Long> p = new ArrayList<>();
        for (int i = 0; i < n; i++) p.add(Pos.pack(i, 1, 0));
        return p;
    }

    @Test
    void followsAlong() {
        PathTracker t = new PathTracker();
        List<Long> p = straight(50);
        assertEquals(0, t.update(p, 1, 0, 1, 0));
        assertEquals(5, t.update(p, 1, 5, 1, 0));
    }

    @Test
    void findsThePlayerFarAheadAfterABoatRide() {
        // tracked at the start of a long crossing, then the boat lands 60 nodes on: not still "at the start"
        PathTracker t = new PathTracker();
        List<Long> p = straight(120);
        assertEquals(10, t.update(p, 1, 10, 1, 0));
        assertEquals(70, t.update(p, 1, 70, 1, 0));
    }
}
