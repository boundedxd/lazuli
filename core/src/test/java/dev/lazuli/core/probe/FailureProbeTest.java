package dev.lazuli.core.probe;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.EdgeWatch;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.control.Jumper;
import dev.lazuli.core.control.PathTracker;
import dev.lazuli.core.control.PlayerState;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.physics.Trace;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Scratch: the hollowcube failure.trace (fell off a jump, then spun). */
class FailureProbeTest {
    static final Path F = Path.of(System.getProperty("user.home"),
        "AppData/Roaming/PrismLauncher/instances/1.21.11(1)/minecraft/config/lazuli/traces/failure.trace");

    static Trace load() throws Exception {
        try (var r = Files.newBufferedReader(F)) { return Trace.read(r); }
    }

    @Test
    void openLoop() throws Exception {
        if (!Files.exists(F)) return;
        Trace w = load();
        for (int[] seg : new int[][]{{186, 213}, {228, 243}, {534, 560}}) {
            Trace.Tick t0 = w.ticks.get(seg[0]);
            PlayerSim s = new PlayerSim(w, t0.x(), t0.y(), t0.z(), t0.vx(), t0.vy(), t0.vz(), t0.onGround(), t0.sprint());
            for (int i = seg[0] + 1; i <= seg[1]; i++) {
                Trace.Tick t = w.ticks.get(i);
                s.step(t.forward(), t.strafe(), t.jump(), t.sprint(), t.yaw());
                System.out.printf("t=%d in f=%d s=%d j=%s sp=%s | real (%.3f,%.3f,%.3f) g=%s | sim (%.3f,%.3f,%.3f) g=%s%n", i,
                    t.forward(), t.strafe(), t.jump(), t.sprint(), t.x(), t.y(), t.z(), t.onGround(), s.x, s.y, s.z, s.onGround);
            }
        }
        for (int y = 56; y <= 62; y++) {
            StringBuilder sb = new StringBuilder("y=" + y + " ");
            for (int z = -95; z >= -110; z--) {
                for (int x = 23; x <= 31; x++) sb.append(w.get(x, y, z).name().charAt(0));
                sb.append('|');
            }
            System.out.println(sb);
        }
    }

    @Test
    void sneakLag() throws Exception {
        if (!Files.exists(F)) return;
        Trace w = load();
        for (int lag = 0; lag <= 3; lag++)
        for (int start : new int[]{186, 533}) {
            Trace.Tick t0 = w.ticks.get(start);
            PlayerSim s = new PlayerSim(w, t0.x(), t0.y(), t0.z(), t0.vx(), t0.vy(), t0.vz(), t0.onGround(), t0.sprint());
            double worst = 0;
            for (int i = start + 1; i <= start + 12; i++) {
                Trace.Tick t = w.ticks.get(i);
                s.step(t.forward(), t.strafe(), t.jump(), t.sprint(), i <= start + 1 + lag, t.yaw());
                worst = Math.max(worst, Math.hypot(s.x - t.x(), s.z - t.z()));
            }
            System.out.println("lag " + lag + " start " + start + " worst " + worst);
        }
    }

    @Test
    void closedLoop() throws Exception {
        if (!Files.exists(F)) return;
        Trace w = load();
        Settings settings = new Settings();
        Interaction in = new Interaction(true, true, 64, p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4)));
        PathingSession session = new PathingSession(w, () -> -64, () -> 319, settings, Runnable::run, System.out::println, () -> in);
        Trace.Tick t0 = w.ticks.get(230);
        PlayerSim s = new PlayerSim(w, t0.x(), t0.y(), t0.z(), t0.vx(), t0.vy(), t0.vz(), t0.onGround(), t0.sprint());
        session.start(new GoalBlock(30, 59, -109), true, Standing.nodeUnder(w, s.x, s.y, s.z));
        InputPlanner planner = new InputPlanner(w);
        Jumper jumper = new Jumper(w);
        PathTracker tracker = new PathTracker();
        EdgeWatch watch = new EdgeWatch();
        float yaw = t0.yaw(), pitch = 0;
        long last = Long.MIN_VALUE;
        System.out.println("path " + session.snapshot().path().stream().map(Pos::toString).toList());
        for (int t = 0; t < 200; t++) {
            PathingSession.Snapshot snap = session.snapshot();
            if (snap.state() != PathingSession.State.PATHING) {
                System.out.println("state " + snap.state() + " at " + t);
                break;
            }
            long block = s.onGround ? Standing.nodeUnder(w, s.x, s.y, s.z)
                : Pos.pack((int) Math.floor(s.x), (int) Math.floor(s.y + 1e-9), (int) Math.floor(s.z));
            List<Long> path = snap.path();
            int idx = tracker.update(path, snap.version(), Pos.x(block), Pos.y(block), Pos.z(block));
            if (s.onGround && block != last && PathTracker.onNode(path, idx, Pos.x(block), Pos.y(block), Pos.z(block))) {
                last = block;
                session.playerMoved(block);
                System.out.println("t=" + t + " path " + session.snapshot().path().stream().limit(6).map(Pos::toString).toList());
                continue;
            }
            PlayerState st = new PlayerState(s.x, s.y, s.z, yaw, pitch, s.onGround, s.horizontalCollision, s.vx, s.vy, s.vz,
                s.sprinting, s.noJumpDelay, s.minorHorizontalCollision, s.onGround ? 0 : Math.max(0, s.peakY - s.y));
            String who = "jumper";
            Controls c = jumper.tick(st, path, idx, true);
            if (c == null) {
                planner.setMaxFall(3);
                c = planner.plan(st, path, idx, true, 45f);
                who = "mpc";
            }
            if (c == null && !s.onGround) {
                c = InputPlanner.airBrake(st, false);
                who = "brake";
            }
            if (c == null) {
                c = new Controls(false, false, false, yaw, pitch);
                who = "none";
            }
            long[] failed = watch.update(path, idx, block, s.onGround, Math.hypot(s.vx, s.vz), false);
            if (failed != null) {
                System.out.println("t=" + t + " edge failed " + Pos.toString(failed[0]) + "->" + Pos.toString(failed[1]));
                session.edgeFailed(failed[0], failed[1]);
            }
            yaw = InputPlanner.approach(yaw, c.targetYaw(), 45f);
            s.step(c.impulse(), c.strafe(), c.jump(), c.sprint(), c.sneak(), yaw);
            System.out.printf("t=%d %s f=%d s=%d j=%s yaw=%.1f pos=(%.2f,%.2f,%.2f) g=%s idx=%d block=%s%n", t, who, c.impulse(),
                c.strafe(), c.jump(), yaw, s.x, s.y, s.z, s.onGround, idx, Pos.toString(block));
            if (s.y < 50) break;
        }
    }
}
