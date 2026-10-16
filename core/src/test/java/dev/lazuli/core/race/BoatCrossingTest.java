package dev.lazuli.core.race;

import dev.lazuli.core.physics.BoatSim;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Boat crossings on water (what a path's boat ride does): the racer drives the boat sim over a water-only track from
 * S to the landing L, keys a tick late as in the game. Maps: '~' water, '#' land (level with the water, which a boat
 * can't climb onto), 'S' water to start on, 'L' the land to get out onto. A row is z, a column x.
 */
class BoatCrossingTest {
    record Course(GridWorld world, int sx, int sz, int lx, int lz) {}

    static Course course(String... rows) {
        GridWorld w = new GridWorld(BlockKind.AIR);
        int sx = 0, sz = 0, lx = 0, lz = 0;
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                char c = rows[z].charAt(x);
                w.fill(x, -3, z, x, -1, z, BlockKind.SOLID);
                w.set(x, 0, z, c == '~' || c == 'S' ? BlockKind.WATER : BlockKind.SOLID);
                if (c == 'S') {
                    sx = x;
                    sz = z;
                } else if (c == 'L') {
                    lx = x;
                    lz = z;
                }
            }
        }
        return new Course(w, sx, sz, lx, lz);
    }

    record Result(boolean landed, int ticks, String why) {}

    /** Drives from S facing {@code yaw} until the landing's in reach, at most {@code max} ticks. */
    static Result cross(Course c, float yaw, int max) {
        TrackField f = TrackField.water(c.world(), c.sx(), 1, c.sz(), c.lx(), c.lz(), 2, 100_000);
        assertNotNull(f, "no way over the water to the landing");
        IceRacer racer = new IceRacer(c.world()).beam(32).budget(Long.MAX_VALUE);
        BoatSim sim = new BoatSim(racer.view(), c.sx() + 0.5, 8.0 / 9.0, c.sz() + 0.5, 0, 0, 0, yaw, 0);
        RaceLine.Cursor cur = f.start(sim.x, sim.y, sim.z);
        IceRacer.Keys pending = IceRacer.Keys.NONE;
        for (int t = 0; t < max; t++) {
            IceRacer.Keys k = racer.next(sim, pending, f, cur);
            sim.step(pending.left(), pending.right(), pending.forward(), pending.back());
            pending = k;
            double toGo = f.toGo(cur, sim.x, sim.y, sim.z);
            if (Double.isNaN(toGo)) return new Result(false, t, "off the water at " + sim.x + " " + sim.y + " " + sim.z);
            if (f.finished(cur, toGo)) return new Result(true, t, "");
        }
        return new Result(false, max, "out of time at " + sim.x + " " + sim.z);
    }

    static final float EAST = -90, WEST = 90, NORTH = 180;

    @Test
    void crossesALakeStartingFacingTheWrongWay() {
        Course c = course(
            "##############################",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "#~~~S~~~~~~~~~~~~~~~~~~~~~~~~L",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "##############################");
        Result r = cross(c, WEST, 400);
        assertTrue(r.landed(), r.toString());
        assertTrue(r.ticks() < 140, "slow: " + r.ticks());
    }

    @Test
    void goesRoundAnIslandNotIntoIt() {
        Course c = course(
            "##############################",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~#####~~~~~~~~~~~~~#",
            "#~~S~~~~~~~#####~~~~~~~~~~~~~L",
            "#~~~~~~~~~~#####~~~~~~~~~~~~~#",
            "#~~~~~~~~~~#####~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~~~~~~~~~~#",
            "##############################");
        Result r = cross(c, EAST, 500);
        assertTrue(r.landed(), r.toString());
        assertTrue(r.ticks() < 200, "slow: " + r.ticks());
    }

    @Test
    void windsDownATwistyRiver() {
        Course c = course(
            "###########################",
            "#S~~~~~~~~~~~~~~~~~~~~~~###",
            "#~~~~~~~~~~~~~~~~~~~~~~~###",
            "#~~~~~~~~~~~~~~~~~~~~~~~###",
            "#################~~~~~~~###",
            "#################~~~~~~~###",
            "###~~~~~~~~~~~~~~~~~~~~~###",
            "###~~~~~~~~~~~~~~~~~~~~~###",
            "###~~~~~~~~~~~~~~~~~~~~~###",
            "###~~~~~##################",
            "###~~~~~##################",
            "###~~~~~~~~~~~~~~~~~~~~~~~L",
            "###~~~~~~~~~~~~~~~~~~~~~~~#",
            "###~~~~~~~~~~~~~~~~~~~~~~~#",
            "###########################");
        Result r = cross(c, EAST, 900);
        assertTrue(r.landed(), r.toString());
    }

    @Test
    void landingRightBehindTheStartIsntCircledForever() {
        // the shore to land on is two blocks behind where the boat went down, facing away from it
        Course c = course(
            "##########",
            "#~~~~~~~~#",
            "#~~~~~~~~#",
            "L~S~~~~~~#",
            "#~~~~~~~~#",
            "#~~~~~~~~#",
            "##########");
        Result r = cross(c, EAST, 300);
        assertTrue(r.landed(), r.toString());
        assertTrue(r.ticks() < 100, "slow: " + r.ticks());
    }

    @Test
    void takesTheWideGapNotTheTightOneNearer() {
        // a wall across with a 1 wide gap (too narrow for a boat) straight ahead, a 4 wide one off to the side
        Course c = course(
            "#####################",
            "#~~~~~~~~~#~~~~~~~~~#",
            "#~~~~~~~~~#~~~~~~~~~#",
            "#~S~~~~~~~~~~~~~~~~~L",
            "#~~~~~~~~~#~~~~~~~~~#",
            "#~~~~~~~~~#~~~~~~~~~#",
            "#~~~~~~~~~#~~~~~~~~~#",
            "#~~~~~~~~~#~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~#",
            "#~~~~~~~~~~~~~~~~~~~#",
            "#####################");
        Result r = cross(c, EAST, 600);
        assertTrue(r.landed(), r.toString());
    }

    @Test
    void theShoreLevelWithTheWaterIsntTrack() {
        Course c = course(
            "##########",
            "#S~~~~~~~#",
            "##########",
            "#~~~~~~~~L");
        assertNull(TrackField.water(c.world(), c.sx(), 1, c.sz(), c.lx(), c.lz(), 2, 10_000), "no water way to the landing");
    }
}
