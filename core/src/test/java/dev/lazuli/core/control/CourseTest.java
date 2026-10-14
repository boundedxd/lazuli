package dev.lazuli.core.control;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import dev.lazuli.core.move.Standing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Closed loop: planner + tracker + input planner driving the physics sim, the same way the mod's executor does. */
class CourseTest {
    static final float MAX_TURN = 45f;

    /** maxAirYawDev: largest yaw difference from the start-to-goal heading while airborne (degrees). */
    record Run(boolean arrived, int ticks, String log, float maxAirYawDev) {}

    static Run drive(GridWorld w, int sx, int sy, int sz, int gx, int gy, int gz, int maxTicks) {
        List<String> msgs = new ArrayList<>();
        PathingSession session = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add);
        session.start(new GoalBlock(gx, gy, gz), false, Pos.pack(sx, sy, sz));
        PlayerSim s = new PlayerSim(w, sx + 0.5, sy, sz + 0.5, 0, 0, 0, true, false);
        InputPlanner planner = new InputPlanner(w);
        PathFollower fallback = new PathFollower(w);
        Climber climber = new Climber(w);
        PathTracker tracker = new PathTracker();
        float yaw = PathFollower.yawTo(sx, sz, gx, gz);
        final float heading = yaw;
        float maxDev = 0;
        long lastBlock = Long.MIN_VALUE;
        int offPath = 0;
        long loggedVersion = -1;
        StringBuilder log = new StringBuilder();
        long planNanos = 0, maxNanos = 0;
        int plans = 0;
        for (int t = 0; t < maxTicks; ) {
            PathingSession.Snapshot snap = session.snapshot();
            if (snap.state() == PathingSession.State.ARRIVED) {
                System.out.printf("%s: %d ticks, plan avg %.2f ms max %.2f ms%n", Thread.currentThread().getStackTrace()[2].getMethodName(),
                    t, planNanos / 1e6 / Math.max(1, plans), maxNanos / 1e6);
                return new Run(true, t, log.toString(), maxDev);
            }
            if (snap.state() != PathingSession.State.PATHING) return new Run(false, t, msgs + "\n" + log, maxDev);
            if (s.y < -10) return new Run(false, t, "fell at " + t + "\n" + log, maxDev);
            long block = s.onGround ? Standing.nodeUnder(w, s.x, s.y, s.z)
                : Pos.pack((int) Math.floor(s.x), (int) Math.floor(s.y + 1e-9), (int) Math.floor(s.z));
            int bx = Pos.x(block), by = Pos.y(block), bz = Pos.z(block);
            List<Long> path = snap.path();
            if (snap.version() != loggedVersion && log.length() < 20000) {
                loggedVersion = snap.version();
                StringBuilder ps = new StringBuilder("path v" + loggedVersion + ":");
                for (int i = 0; i < Math.min(14, path.size()); i++) ps.append(' ').append(Pos.toString(path.get(i)));
                log.append(ps).append('\n');
            }
            int idx = tracker.update(path, snap.version(), bx, by, bz);
            boolean onNode = PathTracker.onNode(path, idx, bx, by, bz);
            if (onNode) offPath = 0; else if (s.onGround) offPath++;
            if (s.onGround && block != lastBlock && (onNode || offPath > 10)) {
                lastBlock = block;
                session.playerMoved(block);
                continue; // re-read the snapshot (state may now be ARRIVED); no tick passes
            }
            PlayerState st = new PlayerState(s.x, s.y, s.z, yaw, 10f, s.onGround, s.horizontalCollision,
                s.vx, s.vy, s.vz, s.sprinting, s.noJumpDelay, s.minorHorizontalCollision);
            long t0 = System.nanoTime();
            Controls c = idx < 0 ? null : climber.tick(st, path, idx);
            boolean climbing = c != null;
            if (climbing) planner.reset();
            else if (idx >= 0) c = planner.plan(st, path.subList(0, Builder.walkableEnd(w, path, idx, 64) + 1), idx, true, MAX_TURN);
            long dt = System.nanoTime() - t0;
            planNanos += dt;
            maxNanos = Math.max(maxNanos, dt);
            plans++;
            boolean planned = c != null && !climbing;
            if (c == null && idx >= 0) c = fallback.tick(st, path.subList(idx, path.size()), true);
            if (c == null) c = new Controls(false, false, false, yaw, 10f);
            yaw = InputPlanner.approach(yaw, c.targetYaw(), MAX_TURN);
            s.step(c.impulse(), c.strafe(), c.jump(), c.sprint(), c.sneak(), yaw);
            t++;
            if (s.onGround && s.fallen() > 3.05) return new Run(false, t, "fell " + s.fallen() + " blocks at " + t + "\n" + log, maxDev);
            if (!s.onGround) maxDev = Math.max(maxDev, Math.abs(RotationController.wrap(yaw - heading)));
            if (log.length() < 20000) {
                log.append(String.format("t=%d pos=(%.2f,%.2f,%.2f) v=(%.3f,%.3f) g=%s idx=%d %s in=%d/%d%s%s yaw=%.0f%n",
                    t, s.x, s.y, s.z, s.vx, s.vz, s.onGround, idx, climbing ? "C" : planned ? "P" : "F", c.impulse(), c.strafe(), c.jump() ? "J" : "", c.sprint() ? "S" : "", yaw));
            }
        }
        return new Run(false, maxTicks, "timeout\n" + log, maxDev);
    }

    /** 1-wide strips along +x at y=0, z=0. */
    static GridWorld strip(int... ranges) {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int i = 0; i < ranges.length; i += 2) w.fill(ranges[i], 0, 0, ranges[i + 1], 0, 0, BlockKind.SOLID);
        return w;
    }

    static void assertArrives(Run r, int maxTicks) {
        assertTrue(r.arrived(), r.log());
        assertTrue(r.ticks() <= maxTicks, "took " + r.ticks() + " ticks\n" + r.log());
    }

    @Test
    void consecutiveOneGapsOnSingleBlocks() {
        Run r = drive(strip(0, 2, 4, 4, 6, 6, 8, 8, 10, 13), 0, 1, 0, 13, 1, 0, 400);
        assertArrives(r, 120);
    }

    @Test
    void twoGapThenJumpUpThenThreeGap() {
        GridWorld w = strip(0, 4, 7, 10).fill(12, 1, 0, 14, 1, 0, BlockKind.SOLID).fill(18, 0, 0, 22, 1, 0, BlockKind.SOLID);
        Run r = drive(w, 0, 1, 0, 22, 2, 0, 400);
        assertArrives(r, 150);
    }

    @Test
    void oneGapOntoASingleBlockThenATurn() {
        // landing block (3,0) then the path turns to +z: must not overshoot the landing
        GridWorld w = strip(0, 1, 3, 3).fill(3, 0, 1, 3, 0, 6, BlockKind.SOLID);
        Run r = drive(w, 0, 1, 0, 3, 1, 6, 300);
        assertArrives(r, 100);
    }

    @Test
    void longOneWideBridgeIsSprintedWithoutFallingOff() {
        Run r = drive(strip(0, 60), 0, 1, 0, 60, 1, 0, 400);
        // sprinting is 5.6 b/s (≈214 ticks for 60 blocks... at 20 tps: 60/0.28 = 214); sprint-jumping is faster
        assertArrives(r, 185); // chained sprint-jumps: ~7 b/s
    }

    @Test
    void openFlatIsFasterThanPlainSprinting() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 70, 70);
        Run r = drive(w, 0, 1, 0, 60, 1, 30, 600);
        double dist = Math.hypot(60, 30);
        assertArrives(r, (int) (0.92 * dist / 0.2806)); // clearly beats straight-line sprinting
    }

    @Test
    void staircaseOfAscends() {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int x = 0; x <= 12; x++) w.fill(x, 0, -1, x, x / 2, 1, BlockKind.SOLID);
        Run r = drive(w, 0, 1, 0, 12, 7, 0, 400);
        assertArrives(r, 120);
    }

    @Test
    void zigzagGapsWithTurns() {
        GridWorld w = new GridWorld(BlockKind.AIR)
            .fill(0, 0, 0, 2, 0, 0, BlockKind.SOLID)
            .set(4, 0, 0, BlockKind.SOLID)
            .set(4, 0, 2, BlockKind.SOLID)
            .set(6, 0, 2, BlockKind.SOLID)
            .set(6, 0, 4, BlockKind.SOLID)
            .fill(8, 0, 4, 10, 0, 4, BlockKind.SOLID);
        Run r = drive(w, 0, 1, 0, 10, 1, 4, 400);
        assertArrives(r, 200);
    }

    @Test
    void twoGapTwoGapOneGapOnSingleBlocks() {
        // 2 blocks of momentum, 2-gap, single block, 2-gap, single block, 1-gap, finish
        Run r = drive(strip(0, 2, 5, 5, 8, 8, 10, 12), 0, 1, 0, 12, 1, 0, 400);
        assertArrives(r, 120);
    }

    @Test
    void longChainOfTwoGapsKeepsMomentumAndFacesForward() {
        // single blocks with 2-block gaps: real players sprint-jump straight through, never turning to brake
        Run r = drive(strip(0, 1, 4, 4, 7, 7, 10, 10, 13, 13, 16, 16, 19, 19, 22, 25), 0, 1, 0, 25, 1, 0, 400);
        assertArrives(r, 130);
        assertTrue(r.maxAirYawDev() < 15, "turned " + r.maxAirYawDev() + " degrees off the line mid-air\n" + r.log());
    }

    @Test
    void zigzagTwoGapsOnSingleBlocks() {
        // each 2-gap also shifts a block sideways: only doable carrying momentum from the jump before
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-2, 0, 0, 0, 0, 0, BlockKind.SOLID);
        for (int i = 1; i <= 6; i++) w.fill(3 * i, 0, i % 2, 3 * i, 0, i % 2, BlockKind.SOLID);
        assertArrives(drive(w, 0, 1, 0, 18, 1, 0, 400), 110);
    }

    @Test
    void twoGapsUpwardWithoutTurningAway() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-2, 0, 0, 0, 0, 0, BlockKind.SOLID);
        for (int i = 1; i <= 5; i++) w.fill(3 * i, 0, 0, 3 * i, i, 0, BlockKind.SOLID);
        Run r = drive(w, 0, 1, 0, 15, 6, 0, 400);
        assertArrives(r, 80);
        assertTrue(r.maxAirYawDev() < 15, "turned " + r.maxAirYawDev() + " degrees off the line mid-air\n" + r.log());
    }

    @Test
    void twoGapsBetweenOneDeepLedges() {
        // ledges 1 deep along the direction of travel: no room to stop, so it has to keep the chain going
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-2, 0, 0, 0, 0, 0, BlockKind.SOLID);
        for (int i = 1; i <= 6; i++) w.fill(3 * i, 0, 0, 3 * i, 0, 4, BlockKind.SOLID);
        assertArrives(drive(w, 0, 1, 0, 18, 1, 2, 400), 110);
    }

    @Test
    void jumpUpABlockThenJumpOffItSideways() {
        // run, jump up onto a 1x1 block, then a gap jump off to the side: it must not turn for the second jump
        // before it is up
        for (int side = 1; side <= 3; side++) {
            GridWorld w = new GridWorld(BlockKind.AIR).fill(-3, 0, 0, 2, 0, 0, BlockKind.SOLID)
                .fill(3, 0, 0, 3, 1, 0, BlockKind.SOLID).fill(5, 0, side, 5, 1, side, BlockKind.SOLID)
                .fill(5, 0, side + 1, 9, 1, side + 1, BlockKind.SOLID);
            Run r = drive(w, -3, 1, 0, 9, 2, side + 1, 300);
            assertArrives(r, 90);
        }
    }

    @Test
    void threeGapsAndAFourGap() {
        Run r = drive(strip(-4, 0, 4, 5, 9, 12, 17, 19), -4, 1, 0, 19, 1, 0, 400);
        assertArrives(r, 150);
    }

    @Test
    void diagonalIslands() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-2, 0, -2, 0, 0, 0, BlockKind.SOLID)
            .set(2, 0, 2, BlockKind.SOLID).set(4, 0, 5, BlockKind.SOLID).set(7, 0, 6, BlockKind.SOLID)
            .fill(9, 0, 8, 11, 0, 10, BlockKind.SOLID);
        Run r = drive(w, -2, 1, -2, 11, 1, 10, 400);
        assertArrives(r, 150);
    }

    @Test
    @org.junit.jupiter.api.Tag("benchmark")
    void bedrockOnly() {
        // the bottom of the world with everything but bedrock deleted: a solid layer, then
        // 80/60/40/20 percent chances per block for the four layers above it
        int arrived = 0;
        StringBuilder fails = new StringBuilder();
        for (long seed = 1; seed <= 10; seed++) {
            java.util.Random rnd = new java.util.Random(seed);
            GridWorld w = new GridWorld(BlockKind.AIR).fill(-4, 0, -6, 44, 0, 6, BlockKind.SOLID);
            double[] p = {0.8, 0.6, 0.4, 0.2};
            for (int x = -4; x <= 44; x++) {
                for (int z = -6; z <= 6; z++) {
                    for (int y = 1; y <= 4; y++) {
                        if (rnd.nextDouble() < p[y - 1]) w.set(x, y, z, BlockKind.SOLID);
                    }
                }
            }
            int[] s0 = surface(w, 0, 0), s1 = surface(w, 40, 0);
            Run r = drive(w, s0[0], s0[1], s0[2], s1[0], s1[1], s1[2], 1500);
            if (r.arrived()) arrived++;
            else fails.append("seed ").append(seed).append(": ").append(r.log(), 0, Math.min(400, r.log().length())).append('\n');
        }
        System.out.println("bedrock: " + arrived + "/10\n" + fails);
        assertTrue(arrived >= 8, fails.toString());
    }

    /** A standable spot near (x, z): the top of a column with two air blocks above. */
    static int[] surface(GridWorld w, int x, int z) {
        for (int r = 0; r < 6; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int y = 6; y >= 1; y--) {
                        if (w.get(x + dx, y - 1, z + dz) == BlockKind.SOLID && w.get(x + dx, y, z + dz) == BlockKind.AIR
                            && w.get(x + dx, y + 1, z + dz) == BlockKind.AIR) {
                            return new int[]{x + dx, y, z + dz};
                        }
                    }
                }
            }
        }
        throw new IllegalStateException("no surface");
    }
}
