package dev.lazuli.core.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CombatMathTest {
    /** Flies a shot the way the game does and returns its height when it gets {@code dist} out. */
    private static double heightAt(float pitch, double dist) {
        double a = Math.toRadians(-pitch), vx = Math.cos(a) * 3, vy = Math.sin(a) * 3, x = 0, y = 0;
        while (x + vx < dist) {
            x += vx;
            y += vy;
            vx *= 0.99;
            vy = vy * 0.99 - 0.05;
        }
        return y + vy * (dist - x) / vx;
    }

    @Test
    void aimsSlightlyUpForAFarTargetAtEyeLevel() {
        Archery.Shot s = Archery.aim(30, 0);
        assertNotNull(s);
        assertTrue(s.pitch() < 0, "should aim up, got " + s.pitch());
        assertEquals(0, heightAt(s.pitch(), 30), 0.3);
        assertTrue(s.ticks() >= 10 && s.ticks() <= 12, "ticks " + s.ticks());
    }

    @Test
    void hitsTargetsAboveAndBelow() {
        for (double dy : new double[]{-8, -2, 3, 8}) {
            Archery.Shot s = Archery.aim(20, dy);
            assertNotNull(s, "dy " + dy);
            assertEquals(dy, heightAt(s.pitch(), 20), 0.3, "dy " + dy);
        }
    }

    @Test
    void outOfRangeIsNull() {
        assertNull(Archery.aim(400, 0));
    }

    @Test
    void drawPowerCurve() {
        assertEquals(3.0, Archery.speed(20), 1e-9);
        assertEquals(3.0, Archery.speed(40), 1e-9);
        assertTrue(Archery.speed(5) < 1.5);
    }

    @Test
    void runsFromAMobWhenBareHandedButFightsWithGear() {
        List<String> three = List.of("minecraft:zombie", "minecraft:zombie", "minecraft:skeleton");
        assertTrue(Threat.outmatched(three, 20, 0, 1));
        assertFalse(Threat.outmatched(three, 20, 15, 6)); // iron armor and sword
        assertTrue(Threat.outmatched(three, 4, 15, 6)); // but not at 2 hearts
        assertFalse(Threat.outmatched(List.of("minecraft:zombie"), 20, 0, 1));
        assertTrue(Threat.outmatched(List.of("minecraft:warden"), 20, 20, 8));
    }
}
