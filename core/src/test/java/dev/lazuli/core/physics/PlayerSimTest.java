package dev.lazuli.core.physics;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.testutil.Shapes;
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

    private static PlayerSim walk(GridWorld w, double x, double y, double z, int ticks, boolean sprint, float yaw) {
        PlayerSim s = new PlayerSim(w, x, y, z, 0, 0, 0, true, false);
        for (int t = 0; t < ticks; t++) s.step(1, false, sprint, yaw);
        return s;
    }

    @Test
    void stepsUpSlabsAndStairsWithoutJumping() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 20, 5);
        w.setPhysics(3, 1, 0, Shapes.BOTTOM_SLAB).fill(4, 1, -5, 20, 1, 5, BlockKind.SOLID).set(4, 1, 0, BlockKind.AIR)
            .setPhysics(4, 1, 0, Shapes.BOTTOM_SLAB).set(4, 2, 0, BlockKind.AIR);
        // slab at x=3, then a slab inside a raised floor at x=4 (top 1.5), then the raised floor (top 2.0)
        PlayerSim s = walk(w, 0.5, 1, 0.5, 40, true, -90f);
        assertEquals(2.0, s.y, 1e-9, "walked up slab, slab, block");
        assertTrue(s.x > 6, "got stuck at " + s.x);
        GridWorld stairs = GridWorld.flatFloor(-5, -5, 20, 5);
        stairs.fill(4, 1, -5, 20, 1, 5, BlockKind.SOLID);
        for (int z = -5; z <= 5; z++) stairs.setPhysics(3, 1, z, Shapes.STAIRS_EAST);
        PlayerSim t = walk(stairs, 0.5, 1, 0.5, 30, true, -90f);
        assertEquals(2.0, t.y, 1e-9, "stairs climb to the top");
        assertTrue(t.x > 5);
    }

    @Test
    void fullBlocksAndFencesAreNotSteppedOver() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 20, 5).set(3, 1, 0, BlockKind.SOLID);
        assertEquals(3 - PlayerSim.HALF_WIDTH, walk(w, 0.5, 1, 0.5, 30, true, -90f).x, 1e-6);
        GridWorld f = GridWorld.flatFloor(-5, -5, 20, 5);
        for (int z = -5; z <= 5; z++) f.setPhysics(3, 1, z, Shapes.FENCE_Z);
        PlayerSim s = new PlayerSim(f, 0.5, 1, 0.5, 0, 0, 0, true, false);
        for (int t = 0; t < 40; t++) s.step(1, t % 12 == 0, true, -90f);
        assertTrue(s.x < 3.4, "jumped a 1.5 fence: " + s.x);
    }

    @Test
    void iceSlidesAndSoulSandIsSlow() {
        GridWorld ice = new GridWorld(BlockKind.AIR);
        GridWorld soul = new GridWorld(BlockKind.AIR);
        for (int x = -5; x <= 200; x++) {
            ice.setPhysics(x, 0, 0, Shapes.ICE);
            soul.setPhysics(x, 0, 0, Shapes.SOUL_SAND);
        }
        PlayerSim onIce = walk(ice, 0.5, 1, 0.5, 60, true, -90f), onFloor = walk(FLAT, 0.5, 1, 0.5, 60, true, -90f);
        double iceStart = onIce.x, floorStart = onFloor.x;
        for (int t = 0; t < 40; t++) {
            onIce.step(0, false, false, -90f);
            onFloor.step(0, false, false, -90f);
        }
        assertTrue(onIce.x - iceStart > 5 * (onFloor.x - floorStart), "ice slide " + (onIce.x - iceStart));
        PlayerSim ss = walk(soul, 0.5, 0.875, 0.5, 60, true, -90f);
        // vanilla: speed factor 0.4 on top of ground drag leaves about 58% of normal sprint speed
        assertEquals(0.58, (ss.x - 0.5) / (floorStart - 0.5), 0.03, "soul sand " + ss.x);
    }

    @Test
    void slimeBouncesAndHoneyStopsJumps() {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) w.setPhysics(x, 0, z, Shapes.SLIME);
        PlayerSim s = new PlayerSim(w, 0.5, 6, 0.5, 0, 0, 0, false, false);
        double low = 99, highAfter = 0;
        boolean bounced = false;
        for (int t = 0; t < 60; t++) {
            s.step(0, false, false, 0);
            low = Math.min(low, s.y);
            if (s.vy > 0.3) bounced = true;
            if (bounced) highAfter = Math.max(highAfter, s.y);
        }
        assertEquals(1.0, low, 1e-6);
        assertTrue(highAfter > 3, "bounce peak " + highAfter);
        GridWorld h = new GridWorld(BlockKind.AIR);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) h.setPhysics(x, 0, z, Shapes.HONEY);
        PlayerSim hs = new PlayerSim(h, 0.5, 0.9375, 0.5, 0, 0, 0, true, false);
        double peak = 0;
        for (int t = 0; t < 20; t++) {
            hs.step(0, t == 0, false, 0);
            peak = Math.max(peak, hs.y - 0.9375);
        }
        assertTrue(peak < 0.5, "honey jump " + peak);
    }

    @Test
    void climbsLadders() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).fill(-1, 1, -5, -1, 20, 5, BlockKind.SOLID);
        for (int y = 1; y <= 10; y++) w.setPhysics(0, y, 0, Shapes.LADDER);
        PlayerSim s = new PlayerSim(w, 0.5, 1, 0.5, 0, 0, 0, true, false);
        for (int t = 0; t < 40; t++) s.step(1, false, false, 90f); // face -x, into the ladder
        assertTrue(s.y > 1 + 30 * 0.11, "ladder climb " + s.y);
    }

    @Test
    void swimsInWater() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-10, 0, -10, 10, 0, 10, BlockKind.SOLID)
            .fill(-10, 1, -10, 10, 5, 10, BlockKind.WATER);
        PlayerSim sink = new PlayerSim(w, 0.5, 4, 0.5, 0, 0, 0, false, false);
        for (int t = 0; t < 20; t++) sink.step(0, false, false, 0);
        assertTrue(sink.inWater);
        assertTrue(sink.y < 4 && sink.y > 2, "sinks slowly: " + sink.y);
        PlayerSim swim = new PlayerSim(w, 0.5, 2, 0.5, 0, 0, 0, false, false);
        for (int t = 0; t < 60; t++) swim.step(1, true, true, -90f);
        assertTrue(swim.y > 3.5, "holding jump floats up: " + swim.y);
        assertTrue(swim.x > 2 && swim.x < 0.5 + 60 * 0.12, "swim speed " + swim.x);
    }
}
