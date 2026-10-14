package dev.lazuli.core.physics;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSimTest {
    private static final GridWorld FLAT = GridWorld.flatFloor(-100, -100, 100, 100);

    private static PlayerSim at(GridWorld w, double x, double z) {
        return new PlayerSim(w, x, 1, z, 0, 0, 0, true, false);
    }

    @Test
    void sprintAndWalkReachVanillaTopSpeeds() {
        PlayerSim walk = at(FLAT, 0.5, 0.5), sprint = at(FLAT, 0.5, 0.5);
        for (int t = 0; t < 40; t++) {
            walk.step(1, false, false, -90f);
            sprint.step(1, false, true, -90f);
        }
        // 4.317 and 5.612 blocks/second
        assertEquals(4.317 / 20, Math.hypot(walk.vx, walk.vz) / 0.546, 0.002);
        assertEquals(5.612 / 20, Math.hypot(sprint.vx, sprint.vz) / 0.546, 0.002);
        assertTrue(walk.onGround);
        assertEquals(1.0, walk.y, 1e-9);
    }

    @Test
    void jumpPeaksAtVanillaHeightAndLandsAfterTwelveTicks() {
        PlayerSim s = at(FLAT, 0.5, 0.5);
        double peak = 0;
        int land = -1;
        for (int t = 0; t < 30 && land < 0; t++) {
            s.step(0, t == 0, false, 0);
            peak = Math.max(peak, s.y);
            if (t > 0 && s.onGround) land = t;
        }
        assertEquals(1 + 1.2522, peak, 0.001);
        assertEquals(11, land); // airborne for 12 ticks, counting the jump tick
    }

    @Test
    void sprintJumpGetsTheBoostAlongYaw() {
        PlayerSim s = at(FLAT, 0.5, 0.5);
        for (int t = 0; t < 30; t++) s.step(1, false, true, -90f);
        PlayerSim noJump = s.copy();
        noJump.step(1, false, true, -90f);
        s.step(1, true, true, -90f);
        // +0.2 along yaw, then one tick of ground drag (0.6 * 0.91)
        assertEquals(0.2 * 0.546, s.vx - noJump.vx, 1e-6);
        assertEquals(0.42, s.y - noJump.y, 1e-6);
        assertEquals(0, s.vz, 1e-9);
    }

    @Test
    void wallsStopHorizontalMovement() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10).fill(3, 1, -10, 3, 2, 10, BlockKind.SOLID);
        PlayerSim s = at(w, 0.5, 0.5);
        for (int t = 0; t < 40; t++) s.step(1, false, true, -90f);
        assertEquals(3 - PlayerSim.HALF_WIDTH, s.x, 1e-6);
        assertTrue(s.horizontalCollision);
    }

    @Test
    void walksOffAnEdgeOnlyOnceTheWholeHitboxIsPast() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-10, 0, 0, 0, 0, 0, BlockKind.SOLID);
        PlayerSim s = at(w, 1.25, 0.5); // center 0.25 past the edge: still supported
        s.step(0, false, false, -90f);
        assertTrue(s.onGround);
        s = at(w, 1.35, 0.5);
        s.step(0, false, false, -90f);
        assertFalse(s.onGround);
    }

    @Test
    void landsOnTopOfAHigherBlock() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10).fill(2, 1, -10, 10, 1, 10, BlockKind.SOLID);
        PlayerSim s = at(w, 1.2, 0.5);
        for (int t = 0; t < 30; t++) s.step(1, t == 0, false, -90f);
        assertEquals(2.0, s.y, 1e-9);
        assertTrue(s.x > 2);
    }
}
