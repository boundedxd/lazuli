package dev.lazuli.core.control;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Digging;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Closed loop with breaking and placing, wired the way the mod's PathExecutor is: planner, tracker, the real
 * {@link Builder} with simulated hands (a break takes {@link #BREAK_TICKS}; a placement needs reach, a block to click
 * and a cell clear of the player), and the input planner moving only up to the next edge that still needs building.
 * The sim sneaks like the game, edge guard included.
 */
public class InteractionCourseTest {
    static final int BREAK_TICKS = 8;
    static final double REACH = 4.5;
    static final Interaction BOTH = new Interaction(true, true, 64, p -> Interaction.handTicks(p, false));
    static final Interaction BREAK = new Interaction(true, false, 0, p -> Interaction.handTicks(p, false));
    static final Interaction PLACE = new Interaction(false, true, 64, p -> Double.POSITIVE_INFINITY);

    public record Run(boolean arrived, int ticks, int breaks, int places, double lowestY, double maxFall, String log, double turned) {}

    /** The game as far as building goes: blocks appear and vanish in the sim's world. */
    static final class SimHands implements Builder.Hands {
        final GridWorld w;
        final PathingSession session;
        PlayerSim s;
        int blocks, breaks, places, cooldown, rebuilds;
        long minePos = Long.MIN_VALUE;
        int mineTicks;

        SimHands(GridWorld w, PathingSession session, int blocks) {
            this.w = w;
            this.session = session;
            this.blocks = blocks;
        }

        public boolean selectThrowaway() {
            return blocks > 0;
        }

        public void place(long pos, long against) {
            if (cooldown > 0 || blocks <= 0) return;
            cooldown = 4;
            int x = Pos.x(pos), y = Pos.y(pos), z = Pos.z(pos);
            if (!Digging.open(w, x, y, z) || Digging.open(w, Pos.x(against), Pos.y(against), Pos.z(against))) return;
            double[] face = Builder.faceCenter(pos, against);
            double eye = s.y + (s.sneaking ? Builder.SNEAK_EYE : Builder.EYE);
            if (Math.sqrt(sq(face[0] - s.x) + sq(face[1] - eye) + sq(face[2] - s.z)) > REACH) return;
            // the game refuses a block that would overlap the player
            if (s.x + PlayerSim.HALF_WIDTH > x && s.x - PlayerSim.HALF_WIDTH < x + 1 && s.z + PlayerSim.HALF_WIDTH > z
                && s.z - PlayerSim.HALF_WIDTH < z + 1 && s.y + PlayerSim.HEIGHT > y && s.y < y + 1) {
                return;
            }
            w.set(x, y, z, BlockKind.SOLID);
            session.regionChanged(x, y, z, x, y, z);
            places++;
            blocks--;
            cooldown = 1;
        }

        public void mine(long pos) {
            if (pos != minePos) {
                minePos = pos;
                mineTicks = 0;
            }
            if (++mineTicks >= BREAK_TICKS) {
                w.set(Pos.x(pos), Pos.y(pos), Pos.z(pos), BlockKind.AIR);
                session.regionChanged(Pos.x(pos), Pos.y(pos), Pos.z(pos), Pos.x(pos), Pos.y(pos), Pos.z(pos));
                breaks++;
                minePos = Long.MIN_VALUE;
            }
        }

        public boolean breakable(long pos) {
            return w.physics(Pos.x(pos), Pos.y(pos), Pos.z(pos)).hardness >= 0;
        }

        public void outOfBlocks() {
            if (rebuilds++ == 0) session.rebuild();
        }

        public double eyeHeight() {
            return s.sneaking ? Builder.SNEAK_EYE : Builder.EYE;
        }

        public void stuck(long u, long v) {
            session.edgeFailed(u, v);
        }

        private static double sq(double d) {
            return d * d;
        }
    }

    public static Run drive(GridWorld w, Interaction in, int sx, int sy, int sz, int gx, int gy, int gz, int maxTicks) {
        return drive(w, in, in.throwaways(), sx, sy, sz, gx, gy, gz, maxTicks);
    }

    public static Run drive(GridWorld w, Interaction in, int blocks, int sx, int sy, int sz, int gx, int gy, int gz, int maxTicks) {
        return drive(w, in, blocks, false, sx, sy, sz, gx, gy, gz, maxTicks);
    }

    public static Run drive(GridWorld w, Interaction in, int blocks, boolean parkour, int sx, int sy, int sz, int gx, int gy, int gz,
                     int maxTicks) {
        List<String> msgs = new ArrayList<>();
        Settings settings = new Settings();
        settings.allowParkour.set(parkour);
        SimHands[] handsRef = new SimHands[1];
        PathingSession session = new PathingSession(w, () -> -64, () -> 319, settings, Runnable::run, msgs::add,
            () -> handsRef[0] == null ? in : new Interaction(in.allowBreak(), in.allowPlace(), handsRef[0].blocks, in.breakTime()));
        SimHands hands = new SimHands(w, session, blocks);
        handsRef[0] = hands;
        Builder builder = new Builder(w, hands);
        session.start(new GoalBlock(gx, gy, gz), false, Pos.pack(sx, sy, sz));
        PlayerSim s = new PlayerSim(w, sx + 0.5, sy, sz + 0.5, 0, 0, 0, true, false);
        hands.s = s;
        InputPlanner planner = new InputPlanner(w);
        PathFollower fallback = new PathFollower(w);
        PathTracker tracker = new PathTracker();
        EdgeWatch edgeWatch = new EdgeWatch();
        float yaw = PathFollower.yawTo(sx, sz, gx, gz), pitch = 10f;
        long lastBlock = Long.MIN_VALUE;
        int offPath = 0;
        double lowest = s.y, maxFall = 0, turned = 0;
        long shownVersion = -1;
        StringBuilder log = new StringBuilder();
        for (int t = 0; t < maxTicks; ) {
            PathingSession.Snapshot snap = session.snapshot();
            if (snap.state() == PathingSession.State.ARRIVED) {
                return new Run(true, t, hands.breaks, hands.places, lowest, maxFall, log.toString(), turned);
            }
            if (snap.state() != PathingSession.State.PATHING) {
                return new Run(false, t, hands.breaks, hands.places, lowest, maxFall, msgs + "\n" + log, turned);
            }
            if (s.y < -40) return new Run(false, t, hands.breaks, hands.places, lowest, maxFall, "fell\n" + log, turned);
            long swim = s.inWater ? Standing.swimNode(w, s.x, s.y, s.z) : Long.MIN_VALUE;
            boolean settled = s.onGround || swim != Long.MIN_VALUE;
            long block = swim != Long.MIN_VALUE ? swim
                : s.onGround ? Standing.nodeUnder(w, s.x, s.y, s.z)
                : Pos.pack((int) Math.floor(s.x), (int) Math.floor(s.y + 1e-9), (int) Math.floor(s.z));
            List<Long> path = snap.path();
            if (snap.version() != shownVersion && log.length() < 30000) {
                shownVersion = snap.version();
                log.append("path v").append(shownVersion).append(' ').append(path.stream().map(Pos::toString).toList()).append(System.lineSeparator());
            }
            int idx = tracker.update(path, snap.version(), Pos.x(block), Pos.y(block), Pos.z(block));
            boolean onNode = PathTracker.onNode(path, idx, Pos.x(block), Pos.y(block), Pos.z(block));
            if (onNode) offPath = 0; else if (settled) offPath++;
            if (settled && block != lastBlock && (onNode || offPath > 10)) {
                lastBlock = block;
                session.playerMoved(block);
                continue;
            }
            if (hands.cooldown > 0) hands.cooldown--;
            PlayerState st = new PlayerState(s.x, s.y, s.z, yaw, pitch, s.onGround, s.horizontalCollision,
                s.vx, s.vy, s.vz, s.sprinting, s.noJumpDelay, s.minorHorizontalCollision, s.onGround ? 0 : Math.max(0, s.peakY - s.y));
            Controls c = builder.tick(st, path, idx, block, in.allowBreak(), in.allowPlace());
            String who = "build";
            boolean building = c != null;
            if (!building && idx >= 0) {
                List<Long> walkable = path.subList(0, Builder.walkableEnd(w, path, idx, 64) + 1);
                c = planner.plan(st, walkable, idx, true, CourseTest.MAX_TURN);
                who = "mpc";
                if (c == null && !s.onGround) {
                    c = InputPlanner.airBrake(st);
                    who = "brake";
                }
                if (c == null) {
                    c = fallback.tick(st, walkable.subList(Math.min(idx, walkable.size() - 1), walkable.size()), true);
                    who = "follow";
                }
            } else {
                planner.reset();
            }
            long[] failed = edgeWatch.update(path, idx, block, settled, Math.hypot(s.vx, s.vz), building);
            if (failed != null) session.edgeFailed(failed[0], failed[1]);
            if (c == null) c = new Controls(false, false, false, yaw, pitch);
            float before = yaw;
            yaw = InputPlanner.approach(yaw, c.targetYaw(), CourseTest.MAX_TURN);
            turned += Math.abs(yaw - before);
            pitch = c.targetPitch();
            s.step(c.impulse(), c.strafe(), c.jump(), c.sprint(), c.sneak(), yaw);
            lowest = Math.min(lowest, s.y);
            maxFall = Math.max(maxFall, s.fallen());
            t++;
            if (log.length() < 30000) {
                log.append(String.format("t=%d pos=(%.2f,%.2f,%.2f) g=%s idx=%d/%d in=%d%s%s%s block=%s path0=%s %s%n", t, s.x, s.y, s.z,
                    s.onGround, idx, path.size(), c.impulse(), c.jump() ? "J" : "", c.sneak() ? "S" : "", building ? " build" : "",
                    Pos.toString(block), path.isEmpty() ? "-" : Pos.toString(path.get(0)), who));
            }
        }
        return new Run(false, maxTicks, hands.breaks, hands.places, lowest, maxFall, "timeout\n" + log, turned);
    }

    // ---- breaking ----

    @Test
    void tunnelsThroughAWall() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 20, 10).fill(5, 1, -10, 6, 3, 10, BlockKind.SOLID);
        Run r = drive(w, BREAK, 0, 1, 0, 10, 1, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.breaks() >= 2, r.log());
    }

    @Test
    void tunnelsALongWayThroughStone() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 30, 5).fill(3, 1, -5, 30, 6, 5, BlockKind.SOLID);
        Run r = drive(w, BOTH, 0, 1, 0, 18, 1, 0, 2500);
        assertTrue(r.arrived(), r.log());
    }

    @Test
    void minesDownAShaft() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).fill(-5, -8, -5, 5, -1, 5, BlockKind.SOLID);
        Run r = drive(w, BREAK, 0, 1, 0, 0, -4, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertEquals(5, r.breaks(), r.log());
    }

    @Test
    void digsToAGoalInsideStone() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 15, 5).fill(-5, -8, -5, 15, -1, 5, BlockKind.SOLID);
        Run r = drive(w, BOTH, 0, 1, 0, 6, -3, 2, 2000);
        assertTrue(r.arrived(), r.log());
    }

    @Test
    void digsUpAndDownStairsThroughAHill() {
        // a solid hill with a floor two blocks higher beyond it: dig in, step up, carry on
        GridWorld w = GridWorld.flatFloor(-5, -5, 20, 5).fill(4, 1, -5, 20, 2, 5, BlockKind.SOLID)
            .fill(4, 3, -5, 8, 5, 5, BlockKind.SOLID);
        Run r = drive(w, BOTH, 0, 1, 0, 12, 3, 0, 900);
        assertTrue(r.arrived(), r.log());
    }

    @Test
    void digsOutOfASealedRoomToTheSurface() {
        // a 3x3 room under 4 blocks of stone; the goal is on top
        GridWorld w = GridWorld.flatFloor(-6, -6, 6, 6).fill(-6, 1, -6, 6, 7, 6, BlockKind.SOLID)
            .fill(-1, 1, -1, 1, 2, 1, BlockKind.AIR);
        Run r = drive(w, BOTH, 0, 1, 0, 0, 8, 0, 3000);
        assertTrue(r.arrived(), r.log());
    }

    // ---- placing ----

    @Test
    void bridgesAVoid() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-5, 0, -2, 0, 0, 2, BlockKind.SOLID).fill(6, 0, -2, 10, 0, 2, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 8, 1, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.places() >= 3, r.log());
        assertTrue(r.lowestY() >= 0.99, "never steps off the bridge\n" + r.log());
    }

    @Test
    void bridgesALongWayOverTheVoid() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-3, 0, -2, 0, 0, 2, BlockKind.SOLID).fill(16, 0, -2, 18, 0, 2, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 17, 1, 0, 2500);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.lowestY() >= 0.99, r.log());
        // ninja bridging: one turn round at the start and one at the end, never a turn per block
        assertTrue(r.turned() < 500, "turned " + r.turned() + " degrees" + System.lineSeparator() + r.log());
    }

    @Test
    void bridgesAroundACorner() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-2, 0, -2, 0, 0, 0, BlockKind.SOLID).fill(5, 0, 6, 7, 0, 8, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 6, 1, 7, 2500);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.lowestY() >= 0.99, r.log());
    }

    @Test
    void towersToAGoalInTheAir() {
        for (int[] g : new int[][]{{0, 2, 0}, {0, 3, 0}, {0, 7, 0}, {3, 2, 1}, {4, 3, 0}}) {
            GridWorld w = GridWorld.flatFloor(-5, -5, 10, 5);
            Run r = drive(w, BOTH, 0, 1, 0, g[0], g[1], g[2], 1200);
            assertTrue(r.arrived(), java.util.Arrays.toString(g) + " " + r.log());
            assertTrue(r.lowestY() >= 0.99, java.util.Arrays.toString(g) + " never falls\n" + r.log());
        }
    }

    @Test
    void pillarsUpOntoALedge() {
        // a sheer 4-high cliff: pillar up beside it, then step across
        GridWorld w = GridWorld.flatFloor(-5, -5, 10, 5).fill(3, 1, -5, 10, 4, 5, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 5, 5, 0, 900);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.places() >= 3, r.log());
    }

    @Test
    void towersOutOfAPit() {
        // a 1x1 hole 4 deep with no way to dig stairs (placing only)
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).fill(-5, 1, -5, 5, 4, 5, BlockKind.SOLID)
            .fill(0, 1, 0, 0, 4, 0, BlockKind.AIR);
        Run r = drive(w, PLACE, 0, 1, 0, 3, 5, 0, 1200);
        assertTrue(r.arrived(), r.log());
    }

    @Test
    void bridgesThenTowersOntoAFloatingIsland() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-3, 0, -2, 0, 0, 2, BlockKind.SOLID).fill(6, 3, -1, 8, 3, 1, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 7, 4, 0, 2500);
        assertTrue(r.arrived(), r.log());
    }

    @Test
    void runsOutOfBlocksWithoutFallingIn() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-5, 0, -2, 0, 0, 2, BlockKind.SOLID).fill(8, 0, -2, 12, 0, 2, BlockKind.SOLID);
        Run r = drive(w, PLACE, 3, 0, 1, 0, 10, 1, 0, 600);
        assertFalse(r.arrived(), r.log());
        assertTrue(r.lowestY() >= 0.99, "stops at the end of what it could build\n" + r.log());
    }
}
