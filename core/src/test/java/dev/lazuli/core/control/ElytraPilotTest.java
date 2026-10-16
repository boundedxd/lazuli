package dev.lazuli.core.control;

import dev.lazuli.core.physics.ElytraSim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElytraPilotTest {
    private record Flight(boolean landed, double x, double z, double lowestOver, int ticks, int rockets, double landSpeed) {}

    /** Flies from just after take-off until on the ground (or out of time), turning at most 15 degrees a tick. */
    private static Flight fly(ElytraPilot.Ground ground, double sx, double sz, double tx, double tz, int rocketsLeft) {
        int sy = ground.at((int) sx, (int) sz);
        ElytraSim.State s = new ElytraSim.State(sx, sy + 3, sz, 0, 0.3, 0);
        ElytraPilot pilot = new ElytraPilot(20);
        float yaw = 0, pitch = 0;
        int boost = 0, used = 0;
        double lowest = Double.POSITIVE_INFINITY;
        int landY = ground.at((int) Math.floor(tx), (int) Math.floor(tz));
        for (int t = 0; t < 20 * 120; t++) {
            ElytraPilot.Command c = pilot.tick(s, tx, tz, landY, ground, used < rocketsLeft);
            if (c.fire()) {
                boost = ElytraSim.BOOST_TICKS;
                used++;
            }
            yaw = InputPlanner.approach(yaw, c.yaw(), 15);
            pitch += Math.max(-15, Math.min(15, c.pitch() - pitch));
            double before = s.speed();
            ElytraSim.step(s, yaw, pitch, boost-- > 0);
            int g = ground.at((int) Math.floor(s.x), (int) Math.floor(s.z));
            double dh = Math.hypot(tx - s.x, tz - s.z);
            if (dh > 8) lowest = Math.min(lowest, s.y - g);
            if (s.y <= g) return new Flight(true, s.x, s.z, lowest, t, used, before);
        }
        return new Flight(false, s.x, s.z, lowest, -1, used, 0);
    }

    @Test
    void fliesAcrossFlatLandAndLandsOnTheSpot() {
        Flight f = fly((x, z) -> 64, 0, 0, 600, 200, 64);
        assertTrue(f.landed(), "never came down");
        assertEquals(600, f.x(), 8, "x " + f.x());
        assertEquals(200, f.z(), 8, "z " + f.z());
        assertTrue(f.lowestOver() > 3, "skimmed the ground: " + f.lowestOver());
        assertTrue(f.landSpeed() < 1.0, "landed at " + f.landSpeed());
        assertTrue(f.rockets() < 40, "used " + f.rockets() + " rockets");
    }

    @Test
    void climbsOverAMountainInTheWay() {
        // a ridge 60 blocks up across the way
        ElytraPilot.Ground g = (x, z) -> x >= 200 && x <= 260 ? 124 : 64;
        Flight f = fly(g, 0, 0, 500, 0, 64);
        assertTrue(f.landed());
        assertEquals(500, f.x(), 8);
        assertTrue(f.lowestOver() > 2, "clipped the ridge: " + f.lowestOver());
    }

    @Test
    void withoutRocketsItGlidesDownGently() {
        Flight f = fly((x, z) -> 64, 0, 0, 600, 0, 0);
        assertTrue(f.landed());
        assertEquals(0, f.rockets());
        assertTrue(f.landSpeed() < 1.0, "came down gently: " + f.landSpeed());
    }
}
