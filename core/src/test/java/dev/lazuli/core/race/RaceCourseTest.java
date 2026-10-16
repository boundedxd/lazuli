package dev.lazuli.core.race;

import dev.lazuli.core.physics.BoatSim;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closed-loop ice boat courses: the racer drives the boat sim with the game's one-tick key delay, from S to G (or
 * round laps), drawn as maps: '#' a wall (two high), '.' blue ice, ' ' nothing (the void), '1'-'9' blue ice that many
 * blocks lower, 'S' the start, 'G' the goal. A row is z, a column x.
 */
class RaceCourseTest {
    static final BlockPhysics BLUE_ICE = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.989f, 1, 1, 0, 2.8f, 9001);

    record Course(GridWorld world, int sx, int sz, int gx, int gz, int gy) {}

    static Course course(String... rows) {
        GridWorld w = new GridWorld(BlockKind.AIR);
        int sx = 0, sz = 0, gx = 0, gz = 0, gy = 1;
        for (int z = 0; z < rows.length; z++) {
            for (int x = 0; x < rows[z].length(); x++) {
                char c = rows[z].charAt(x);
                switch (c) {
                    case ' ' -> { }
                    case '#' -> {
                        w.setPhysics(x, 0, z, BLUE_ICE);
                        w.fill(x, 1, z, x, 2, z, BlockKind.SOLID);
                        for (int y = -9; y < 0; y++) w.set(x, y, z, BlockKind.SOLID); // walls go down past the drops too
                    }
                    default -> {
                        // a goal takes the level of the track beside it (right, else left)
                        char lvl = c != 'G' ? c : x + 1 < rows[z].length() && Character.isDigit(rows[z].charAt(x + 1)) ? rows[z].charAt(x + 1)
                            : x > 0 ? rows[z].charAt(x - 1) : c;
                        int drop = Character.isDigit(c) ? c - '0' : Character.isDigit(lvl) ? lvl - '0' : 0;
                        w.setPhysics(x, -drop, z, BLUE_ICE);
                        if (c == 'S') {
                            sx = x;
                            sz = z;
                        } else if (c == 'G') {
                            gx = x;
                            gz = z;
                            gy = 1 - drop;
                        }
                    }
                }
            }
        }
        return new Course(w, sx, sz, gx, gz, gy);
    }

    record Result(boolean finished, int ticks, boolean left, int laps, double lowest) {}

    /** Drives from S facing {@code yaw}, until within 2 of G (or {@code laps} laps), at most {@code max} ticks. */
    static Result drive(Course c, float yaw, RaceLine line, int laps, int max) {
        IceRacer racer = new IceRacer(c.world()).beam(24).budget(Long.MAX_VALUE);
        BoatSim sim = new BoatSim(racer.view(), c.sx() + 0.5, 1, c.sz() + 0.5, 0, 0, 0, yaw, 0);
        RaceLine.Cursor cur = line.start(sim.x, sim.y, sim.z);
        IceRacer.Keys pending = IceRacer.Keys.NONE;
        double lowest = sim.y;
        for (int t = 0; t < max; t++) {
            IceRacer.Keys k = racer.next(sim, pending, line, cur);
            sim.step(pending.left(), pending.right(), pending.forward(), pending.back());
            pending = k;
            lowest = Math.min(lowest, sim.y);
            double toGo = line.toGo(cur, sim.x, sim.y, sim.z);
            if (Double.isNaN(toGo) || sim.y < -20) return new Result(false, t, true, cur.laps, lowest);
            if (line.finished(cur, toGo)) {
                return new Result(true, t, false, cur.laps, lowest);
            }
        }
        return new Result(false, max, false, cur.laps, lowest);
    }

    static Result toGoal(Course c, float yaw, int max) {
        TrackField f = TrackField.toGoal(c.world(), c.sx(), 1, c.sz(), c.gx(), c.gy(), c.gz(), 1.5, 200_000);
        assertNotNull(f, "no track to the goal");
        return drive(c, yaw, f, 0, max);
    }

    static final float EAST = -90, SOUTH = 0;

    @Test
    void sprintsAStraight() {
        String row = "#" + ".".repeat(198) + "#";
        Course c = course("#".repeat(200), row, row.replaceFirst("\\.", "S").replace("..#", "G.#"), row, "#".repeat(200));
        Result r = toGoal(c, EAST, 400);
        assertTrue(r.finished(), r.toString());
        assertTrue(r.ticks() < 130, "slow: " + r.ticks()); // 196 blocks, getting up to 3.6 a tick takes ~90 ticks
    }

    @Test
    void takesAHairpinThroughAThreeWideGap() {
        Course c = course(
            "##########################################",
            "#S.......................................#",
            "#........................................#",
            "#........................................#",
            "######################################...#",
            "#G.......................................#",
            "#........................................#",
            "#........................................#",
            "##########################################");
        Result r = toGoal(c, EAST, 600);
        assertTrue(r.finished(), r.toString());
        assertTrue(r.ticks() < 160, "slow: " + r.ticks());
    }

    @Test
    void snakesThroughTightTurns() {
        Course c = course(
            "#################################",
            "#S.......#.........#.........####",
            "#........#.........#..........###",
            "#........#....#....#....#.....###",
            "######...#....#....#....#.....###",
            "#........#....#....#....#.....###",
            "#.............#.........#........",
            "#.............#.........#.......G",
            "#################################");
        Result r = toGoal(c, EAST, 900);
        assertTrue(r.finished(), r.toString());
    }

    @Test
    void staysOnABridgeOverTheVoid() {
        // no walls: a 4-wide bridge with two kinks, falling off is the end
        Course c = course(
            "S...........                    ",
            ".............                   ",
            "..............                  ",
            "...............                 ",
            " ...............                ",
            "   ..............               ",
            "     ...........................",
            "       ........................G",
            "         .......................",
            "            ....................");
        Result r = toGoal(c, EAST, 600);
        assertTrue(r.finished(), r.toString());
    }

    @Test
    void dropsDownALevelAndTurns() {
        Course c = course(
            "##################################",
            "#S...............4444444444444444#",
            "#................4444444444444444#",
            "#................4444444444444444#",
            "###############################44#",
            "#G4444444444444444444444444444444#",
            "#44444444444444444444444444444444#",
            "##################################");
        Result r = toGoal(c, EAST, 800);
        assertTrue(r.finished(), r.toString());
        assertEquals(-3, r.lowest(), 1e-6, "should end up on the lower level");
    }

    @Test
    void lapsARing() {
        Course c = course(
            "##############################",
            "#............................#",
            "#............................#",
            "#............................#",
            "#....####################....#",
            "#.S..#                  #....#",
            "#....####################....#",
            "#............................#",
            "#............................#",
            "#............................#",
            "##############################");
        TrackField f = TrackField.lap(c.world(), c.sx(), 1, c.sz(), SOUTH, 200_000);
        assertNotNull(f);
        assertTrue(f.lapLength() > 40, "lap " + f.lapLength());
        Result r = drive(c, SOUTH, f.laps(2), 2, 1500);
        assertTrue(r.finished(), r.toString());
    }

    @Test
    void followsARecordedLine() {
        Course c = course(
            "##########################################",
            "#S.......................................#",
            "#........................................#",
            "#........................................#",
            "######################################...#",
            "#G.......................................#",
            "#........................................#",
            "#........................................#",
            "##########################################");
        List<double[]> pts = new ArrayList<>();
        for (double x = 1.5; x <= 39.5; x += 1) pts.add(new double[]{x, 1, 2.5});
        for (double z = 2.5; z <= 6.5; z += 1) pts.add(new double[]{39.5, 1, z});
        for (double x = 39.5; x >= 1.5; x -= 1) pts.add(new double[]{x, 1, 6.5});
        RecordedLine line = new RecordedLine(pts, false);
        assertFalse(line.loop());
        Result r = drive(c, EAST, line, 0, 600);
        assertTrue(r.finished(), r.toString());
    }

    @Test
    void threadsTwoWideCorridors() {
        Course c = course(
            "####################",
            "#S...........#######",
            "#............#######",
            "###########..#######",
            "###########..#######",
            "###########..#######",
            "###########.........",
            "###########.......G.",
            "####################");
        Result r = toGoal(c, EAST, 900);
        assertTrue(r.finished(), r.toString());
    }

    @Test
    void jumpsAGapOverTheVoid() {
        // a run-up, then nothing for 8 blocks, then a landing 3 lower: only fast enough makes it
        Course c = course(
            "S.........................................        33333333333333",
            "..........................................        3333333333333G",
            "..........................................        33333333333333",
            "..........................................        33333333333333");
        TrackField f = TrackField.toGoal(c.world(), c.sx(), 1, c.sz(), c.gx(), c.gy(), c.gz(), 1.5, 200_000);
        assertNotNull(f, "the gap should be a link");
        Result r = drive(c, EAST, f, 0, 600);
        assertTrue(r.finished(), r.toString());
    }

    @Test
    void runsTheGauntlet() {
        // a hairpin, a turn, a short run-up and a jump down onto a lower deck
        Course c = course(
            "#########################                         ",
            "#S......................#                         ",
            "#.......................#                         ",
            "####################....#                         ",
            "#.......................#                         ",
            "#.......................#                         ",
            "#...#####################                         ",
            "#...#                                             ",
            "#...#                                             ",
            "#.......................        33333333333333333 ",
            "#.......................        33333333333333333 ",
            "#.......................        3333333333333333G ",
            "########################        33333333333333333 ");
        Result r = toGoal(c, EAST, 1200);
        assertTrue(r.finished(), r.toString());
        assertEquals(-2, r.lowest(), 1e-6);
    }
}
