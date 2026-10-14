package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ControlTest {
    private static List<Long> path(long... p) {
        List<Long> l = new ArrayList<>();
        for (long x : p) l.add(x);
        return l;
    }

    @Test
    void wrapsShortestWay() {
        assertEquals(-20f, RotationController.wrap(340f), 1e-4);
        assertEquals(180f, RotationController.wrap(-180f), 1e-4);
    }

    @Test
    void neverExceedsMaxSpeedAndConverges() {
        RotationController rc = new RotationController();
        float yaw = 0, pitch = 0;
        for (int i = 0; i < 60; i++) {
            float[] d = rc.step(yaw, pitch, 179f, 30f, 30f);
            assertTrue(Math.abs(d[0]) <= 30f + 1e-4);
            yaw += d[0];
            pitch += d[1];
        }
        assertEquals(179f, yaw, 0.1);
        assertEquals(30f, pitch, 0.1);
    }

    @Test
    void turnsAcrossTheSeam() {
        assertTrue(new RotationController().step(170f, 0, -170f, 0, 30f)[0] > 0);
    }

    @Test
    void faceAndSprintTowardNextNode() {
        PlayerState s = new PlayerState(0.5, 1, 0.5, -90f, 0, true, false);
        Controls c = new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 1, 0), Pos.pack(2, 1, 0)), true);
        assertEquals(-90f, c.targetYaw(), 1.0);
        assertTrue(c.forward());
        assertTrue(c.sprint());
        assertFalse(c.jump());
    }

    @Test
    void jumpsForAscend() {
        PlayerState s = new PlayerState(0.5, 1, 0.5, -90f, 0, true, false);
        assertTrue(new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 2, 0)), true).jump());
    }

    @Test
    void noSprintWhileTurningHard() {
        PlayerState s = new PlayerState(0.5, 1, 0.5, 90f, 0, true, false);
        Controls c = new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 1, 0)), true);
        assertFalse(c.sprint());
        assertFalse(c.forward());
    }

    @Test
    void sprintOffWhenDisallowed() {
        PlayerState s = new PlayerState(0.5, 1, 0.5, -90f, 0, true, false);
        Controls c = new PathFollower().tick(s, path(Pos.pack(0, 1, 0), Pos.pack(1, 1, 0), Pos.pack(2, 1, 0)), false);
        assertTrue(c.forward());
        assertFalse(c.sprint());
    }

    @Test
    void idleWithoutPath() {
        assertFalse(new PathFollower().tick(new PlayerState(0, 1, 0, 0, 0, true, false), path(Pos.pack(0, 1, 0)), true).forward());
    }
}
