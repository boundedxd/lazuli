package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EdgeWatchTest {
    private static final long U = Pos.pack(0, 1, 0), V = Pos.pack(1, 2, 1), W = Pos.pack(2, 2, 1);
    private static final List<Long> PATH = List.of(U, V, W);

    /** One jump from u: take off, then land on {@code at}. */
    private static long[] hop(EdgeWatch w, long at) {
        w.update(PATH, 0, U, true, 0.2, false);
        w.update(PATH, 0, U, false, 0.2, false);
        return w.update(PATH, 0, at, true, 0.2, false);
    }

    @Test
    void aStepUpThatKeepsLandingBackWhereItStartedIsGivenUp() {
        EdgeWatch w = new EdgeWatch();
        assertNull(hop(w, U), "one bonk is allowed");
        assertArrayEquals(new long[]{U, V}, hop(w, U));
    }

    @Test
    void landingOnTheTargetOrFurtherIsFine() {
        EdgeWatch w = new EdgeWatch();
        for (int i = 0; i < 3; i++) assertNull(hop(w, V));
        assertNull(hop(w, W));
    }

    @Test
    void slidingAlongAWallWithoutReachingTheNextNodeIsGivenUp() {
        EdgeWatch w = new EdgeWatch();
        long[] failed = null;
        for (int t = 0; t <= EdgeWatch.NO_PROGRESS_TICKS && failed == null; t++) failed = w.update(PATH, 0, U, true, 0.1, false);
        assertArrayEquals(new long[]{U, V}, failed);
    }

    @Test
    void buildingTakesAsLongAsItTakes() {
        EdgeWatch w = new EdgeWatch();
        for (int t = 0; t < 3 * EdgeWatch.NO_PROGRESS_TICKS; t++) assertNull(w.update(PATH, 0, U, true, 0, true));
    }

    @Test
    void hopsOnTheRunUpOfALongJumpAreNotMisses() {
        long u = Pos.pack(0, 1, 0), v = Pos.pack(6, 1, 0), runUp = Pos.pack(2, 1, 0), wrong = Pos.pack(2, 1, 3);
        List<Long> path = List.of(u, v);
        EdgeWatch w = new EdgeWatch();
        w.update(path, 0, u, true, 0.2, false);
        w.update(path, 0, u, false, 0.2, false);
        assertNull(w.update(path, 0, runUp, true, 0.2, false));
        w.update(path, 0, runUp, false, 0.2, false);
        assertNull(w.update(path, 0, v, true, 0.2, false));
        // landing off the line is still a miss
        w.update(path, 0, u, true, 0.2, false);
        w.update(path, 0, u, false, 0.2, false);
        assertArrayEquals(new long[]{u, v}, w.update(path, 0, wrong, true, 0.2, false));
    }
}
