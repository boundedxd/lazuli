package dev.lazuli.core.physics;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoatSimTest {
    static final BlockPhysics BLUE_ICE = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.989f, 1, 1, 0, 2.8f, 9001);
    static final BlockPhysics PACKED_ICE = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.98f, 1, 1, 0, 0.5f, 9002);

    /** A floor of {@code p} from (x1, z1) to (x2, z2) at y=0. */
    static GridWorld floor(BlockPhysics p, int x1, int z1, int x2, int z2) {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++) w.setPhysics(x, 0, z, p);
        return w;
    }

    @Test
    void topSpeedOnBlueIceIsAbout3point6BlocksATick() {
        BoatSim s = new BoatSim(floor(BLUE_ICE, -2, -2, 2, 2600), 0.5, 1, 0.5, 0, 0, 0, 0, 0);
        for (int t = 0; t < 600; t++) s.step(false, false, true, false);
        assertEquals(BoatSim.Status.ON_LAND, s.status);
        assertEquals(0.04 / (1 - 0.989), s.speed(), 0.02);
        assertTrue(s.vz > 0, "yaw 0 goes +z");
        assertEquals(0.5, s.x, 1e-6);
    }

    @Test
    void topSpeedOnPackedIceIsTwo() {
        BoatSim s = new BoatSim(floor(PACKED_ICE, -2, -2, 2, 1000), 0.5, 1, 0.5, 0, 0, 0, 0, 0);
        for (int t = 0; t < 400; t++) s.step(false, false, true, false);
        assertEquals(2.0, s.speed(), 0.02);
    }

    @Test
    void slowOnPlainGround() {
        BoatSim s = new BoatSim(GridWorld.flatFloor(-2, -2, 2, 50), 0.5, 1, 0.5, 0, 0, 0, 0, 0);
        for (int t = 0; t < 100; t++) s.step(false, false, true, false);
        assertEquals(0.04 / (1 - 0.6), s.speed(), 0.01);
    }

    @Test
    void aTurnsLeftDRightAndTheSpinCarriesOnIce() {
        BoatSim s = new BoatSim(floor(BLUE_ICE, -20, -20, 20, 20), 0.5, 1, 0.5, 0, 0, 0, 0, 0);
        s.step(true, false, false, false);
        assertEquals(-1, s.yaw, 1e-4);
        for (int t = 0; t < 5; t++) s.step(false, false, false, false);
        assertTrue(s.yaw < -5, "keeps spinning: " + s.yaw);
        BoatSim d = new BoatSim(floor(BLUE_ICE, -20, -20, 20, 20), 0.5, 1, 0.5, 0, 0, 0, 0, 0);
        d.step(false, true, false, false);
        assertEquals(1, d.yaw, 1e-4);
    }

    @Test
    void aWallStopsIt() {
        GridWorld w = floor(BLUE_ICE, -3, -3, 3, 30);
        w.fill(-3, 1, 10, 3, 2, 10, BlockKind.SOLID);
        BoatSim s = new BoatSim(w, 0.5, 1, 0.5, 0, 0, 0, 0, 0);
        for (int t = 0; t < 60; t++) s.step(false, false, true, false);
        assertEquals(10 - BoatSim.HALF_WIDTH, s.z, 1e-6);
        assertEquals(0, s.vz, 1e-9);
    }

    @Test
    void fallsOffALedgeLosingSpeedInTheAir() {
        GridWorld w = floor(BLUE_ICE, -3, -3, 3, 10);
        for (int x = -3; x <= 3; x++)
            for (int z = 11; z <= 60; z++) w.setPhysics(x, -6, z, BLUE_ICE);
        BoatSim s = new BoatSim(w, 0.5, 1, 0.5, 0, 0, 1.5, 0, 0);
        boolean flew = false;
        for (int t = 0; t < 40; t++) {
            s.step(false, false, false, false);
            flew |= s.status == BoatSim.Status.IN_AIR;
        }
        assertTrue(flew);
        assertEquals(-5, s.y, 1e-6);
        assertEquals(BoatSim.Status.ON_LAND, s.status);
    }
}
