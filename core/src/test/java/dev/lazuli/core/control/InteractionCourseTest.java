package dev.lazuli.core.control;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Digging;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.MoveGraph;
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
 * Closed loop with breaking and placing: planner + tracker + input planner driving the sim, and a stand-in for the
 * mod's Interactor that performs each edge's actions (a break takes {@link #BREAK_TICKS}, a placement is instant,
 * a pillar jumps and places under the feet).
 */
class InteractionCourseTest {
    static final int BREAK_TICKS = 8;
    static final Interaction BOTH = new Interaction(true, true, 64, p -> Interaction.handTicks(p, false));
    static final Interaction BREAK = new Interaction(true, false, 0, p -> Interaction.handTicks(p, false));
    static final Interaction PLACE = new Interaction(false, true, 64, p -> Double.POSITIVE_INFINITY);

    record Run(boolean arrived, int ticks, int breaks, int places, String log) {}

    static Run drive(GridWorld w, Interaction in, int sx, int sy, int sz, int gx, int gy, int gz, int maxTicks) {
        List<String> msgs = new ArrayList<>();
        Settings settings = new Settings();
        settings.allowParkour.set(false);
        PathingSession session = new PathingSession(w, () -> -64, () -> 319, settings, Runnable::run, msgs::add, () -> in);
        session.start(new GoalBlock(gx, gy, gz), false, Pos.pack(sx, sy, sz));
        PlayerSim s = new PlayerSim(w, sx + 0.5, sy, sz + 0.5, 0, 0, 0, true, false);
        InputPlanner planner = new InputPlanner(w);
        PathFollower fallback = new PathFollower(w);
        PathTracker tracker = new PathTracker();
        MoveGraph plain = new MoveGraph(w, settings.maxFallHeight.get(), true, 1, false);
        float yaw = PathFollower.yawTo(sx, sz, gx, gz);
        long lastBlock = Long.MIN_VALUE, pillar = Long.MIN_VALUE;
        boolean pillarLeft = false;
        int offPath = 0, busy = 0, breaks = 0, places = 0;
        StringBuilder log = new StringBuilder();
        for (int t = 0; t < maxTicks; ) {
            PathingSession.Snapshot snap = session.snapshot();
            if (snap.state() == PathingSession.State.ARRIVED) return new Run(true, t, breaks, places, log.toString());
            if (snap.state() != PathingSession.State.PATHING) return new Run(false, t, breaks, places, msgs + "\n" + log);
            if (s.y < -20) return new Run(false, t, breaks, places, "fell\n" + log);
            long block = s.onGround ? Standing.nodeUnder(w, s.x, s.y, s.z)
                : Pos.pack((int) Math.floor(s.x), (int) Math.floor(s.y + 1e-9), (int) Math.floor(s.z));
            List<Long> path = snap.path();
            int idx = tracker.update(path, snap.version(), Pos.x(block), Pos.y(block), Pos.z(block));
            boolean onNode = PathTracker.onNode(path, idx, Pos.x(block), Pos.y(block), Pos.z(block));
            if (onNode) offPath = 0; else if (s.onGround) offPath++;
            if (s.onGround && block != lastBlock && (onNode || offPath > 10)) {
                lastBlock = block;
                session.playerMoved(block);
                continue;
            }
            Controls c = null;
            if (pillar != Long.MIN_VALUE) {
                int px = Pos.x(pillar), py = Pos.y(pillar), pz = Pos.z(pillar);
                boolean placed = !Digging.open(w, px, py, pz);
                if (!s.onGround) pillarLeft = true;
                if (s.onGround && (placed || pillarLeft)) {
                    pillar = Long.MIN_VALUE;
                } else {
                    if (!placed && !s.onGround && s.y > py + 1.02) {
                        w.set(px, py, pz, BlockKind.SOLID);
                        session.regionChanged(px, py, pz, px, py, pz);
                        places++;
                    }
                    c = new Controls(false, false, s.onGround && !pillarLeft, false, yaw, 90f);
                }
            } else if (s.onGround && idx >= 0 && idx + 1 < path.size() && path.get(idx) == block) {
                long u = path.get(idx), v = path.get(idx + 1);
                List<Digging.Action> acts = plain.actions(u, v);
                if (!acts.isEmpty()) {
                    Digging.Action a = acts.get(0);
                    long p = a.pos();
                    boolean vertical = Pos.x(u) == Pos.x(v) && Pos.z(u) == Pos.z(v);
                    if (vertical && Math.hypot(s.x - Pos.x(u) - 0.5, s.z - Pos.z(u) - 0.5) > 0.15) {
                        // the mod sneaks to the middle; the sim has no sneak, so put it there once it has stopped
                        if (Math.hypot(s.vx, s.vz) < 0.01) {
                            s.x = Pos.x(u) + 0.5;
                            s.z = Pos.z(u) + 0.5;
                        }
                    } else if (!a.place()) {
                        if (++busy >= BREAK_TICKS) {
                            busy = 0;
                            w.set(Pos.x(p), Pos.y(p), Pos.z(p), BlockKind.AIR);
                            session.regionChanged(Pos.x(p), Pos.y(p), Pos.z(p), Pos.x(p), Pos.y(p), Pos.z(p));
                            breaks++;
                        }
                    } else if (vertical) {
                        pillar = p;
                        pillarLeft = false;
                    } else {
                        w.set(Pos.x(p), Pos.y(p), Pos.z(p), BlockKind.SOLID);
                        session.regionChanged(Pos.x(p), Pos.y(p), Pos.z(p), Pos.x(p), Pos.y(p), Pos.z(p));
                        places++;
                    }
                    c = new Controls(false, false, pillar != Long.MIN_VALUE, false, yaw, 30f);
                    planner.reset();
                }
            }
            if (c == null) {
                PlayerState st = new PlayerState(s.x, s.y, s.z, yaw, 10f, s.onGround, s.horizontalCollision,
                    s.vx, s.vy, s.vz, s.sprinting, s.noJumpDelay, s.minorHorizontalCollision);
                c = idx < 0 ? null : planner.plan(st, path, idx, true, CourseTest.MAX_TURN);
                if (c == null && idx >= 0) c = fallback.tick(st, path.subList(idx, path.size()), true);
                if (c == null) c = new Controls(false, false, false, yaw, 10f);
            }
            yaw = InputPlanner.approach(yaw, c.targetYaw(), CourseTest.MAX_TURN);
            s.step(c.impulse(), c.strafe(), c.jump(), c.sprint(), yaw);
            t++;
            if (log.length() < 30000) {
                log.append(String.format("t=%d pos=(%.2f,%.2f,%.2f) g=%s idx=%d/%d in=%d%s%s%n", t, s.x, s.y, s.z, s.onGround,
                    idx, path.size(), c.impulse(), c.jump() ? "J" : "", pillar != Long.MIN_VALUE ? " pillar" : ""));
            }
        }
        return new Run(false, maxTicks, breaks, places, "timeout\n" + log);
    }

    @Test
    void tunnelsThroughAWall() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 20, 10).fill(5, 1, -10, 6, 3, 10, BlockKind.SOLID);
        Run r = drive(w, BREAK, 0, 1, 0, 10, 1, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.breaks() >= 2, r.log());
    }

    @Test
    void bridgesAVoid() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-5, 0, -2, 0, 0, 2, BlockKind.SOLID).fill(6, 0, -2, 10, 0, 2, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 8, 1, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.places() >= 3, r.log());
    }

    @Test
    void pillarsUpOntoALedge() {
        // a sheer 4-high cliff: pillar up beside it, then step across
        GridWorld w = GridWorld.flatFloor(-5, -5, 10, 5).fill(3, 1, -5, 10, 4, 5, BlockKind.SOLID);
        Run r = drive(w, PLACE, 0, 1, 0, 5, 5, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertTrue(r.places() >= 3, r.log());
    }

    @Test
    void towersToAGoalInTheAir() {
        for (int[] g : new int[][]{{0, 2, 0}, {0, 3, 0}, {3, 2, 1}, {4, 3, 0}}) {
            GridWorld w = GridWorld.flatFloor(-5, -5, 10, 5);
            Run r = drive(w, BOTH, 0, 1, 0, g[0], g[1], g[2], 600);
            assertTrue(r.arrived(), java.util.Arrays.toString(g) + " " + r.log());
        }
    }

    @Test
    void minesDownAShaft() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).fill(-5, -8, -5, 5, -1, 5, BlockKind.SOLID);
        Run r = drive(w, BREAK, 0, 1, 0, 0, -4, 0, 600);
        assertTrue(r.arrived(), r.log());
        assertEquals(5, r.breaks(), r.log());
    }

    @Test
    void digsUpAndDownStairsThroughAHill() {
        // a solid hill with a floor two blocks higher beyond it: dig in, step up, carry on
        GridWorld w = GridWorld.flatFloor(-5, -5, 20, 5).fill(4, 1, -5, 20, 2, 5, BlockKind.SOLID)
            .fill(4, 3, -5, 8, 5, 5, BlockKind.SOLID);
        Run r = drive(w, BOTH, 0, 1, 0, 12, 3, 0, 900);
        assertTrue(r.arrived(), r.log());
    }
}
