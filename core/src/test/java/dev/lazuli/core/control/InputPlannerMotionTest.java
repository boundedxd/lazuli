package dev.lazuli.core.control;

import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Closed-loop {@link InputPlanner} runs: no pointless jumps, no turning round. */
class InputPlannerMotionTest {
    private record Run(int jumps, float worstYawOff, PlayerSim end) {}

    /** Drives the planner along {@code path} (all nodes, the tracker picks the index) for {@code ticks}. */
    private static Run drive(BlockView w, List<Long> path, double x, double y, double z, float yaw, int ticks) {
        return drive(w, path, x, y, z, yaw, ticks, false);
    }

    private static Run drive(BlockView w, List<Long> path, double x, double y, double z, float yaw, int ticks, boolean flicks) {
        InputPlanner planner = new InputPlanner(w);
        planner.setFlicks(flicks);
        PathTracker tracker = new PathTracker();
        PlayerSim s = new PlayerSim(w, x, y, z, 0, 0, 0, true, false);
        float start = yaw, worst = 0;
        int jumps = 0;
        for (int t = 0; t < ticks; t++) {
            long block = Pos.pack((int) Math.floor(s.x), (int) Math.floor(s.y + 1e-6), (int) Math.floor(s.z));
            int idx = tracker.update(path, 1, Pos.x(block), Pos.y(block), Pos.z(block));
            PlayerState st = new PlayerState(s.x, s.y, s.z, yaw, 0, s.onGround, s.horizontalCollision, s.vx, s.vy, s.vz,
                s.sprinting, s.noJumpDelay, s.minorHorizontalCollision, s.onGround ? 0 : Math.max(0, s.peakY - s.y));
            Controls c = idx < 0 ? null : planner.plan(st, path, idx, true, 45f);
            if (c == null && !s.onGround) c = InputPlanner.airBrake(st, flicks);
            if (c == null) c = new Controls(false, false, false, yaw, 0);
            yaw = InputPlanner.approach(yaw, c.targetYaw(), 45f);
            worst = Math.max(worst, Math.abs(RotationController.wrap(yaw - start)));
            if (c.jump() && s.onGround) jumps++;
            s.step(c.impulse(), c.strafe(), c.jump(), c.sprint(), c.sneak(), yaw);
        }
        return new Run(jumps, worst, s);
    }

    @Test
    void doesNotHopTheLastFewBlocksOfATunnel() {
        // a 2-high tunnel: a head-hitter hop is faster, but it has to stop two blocks on anyway (the next block to mine)
        GridWorld w = GridWorld.flatFloor(-3, -2, 10, 2).fill(-3, 3, -2, 10, 3, 2, BlockKind.SOLID);
        List<Long> path = new ArrayList<>();
        for (int x = 0; x <= 2; x++) path.add(Pos.pack(x, 1, 0));
        Run r = drive(w, path, 0.5, 1, 0.5, -90f, 60);
        assertEquals(0, r.jumps(), "hops");
        assertEquals(2.5, r.end().x, 0.3);
    }

    @Test
    void runsDownAStaircaseFacingForward() {
        // natural staircase, like the recording's: mostly one block down per block, some two, then flat
        int[] drops = {0, 1, 1, 1, 2, 1, 1, 2, 1, 1, 0, 0, 0, 0, 0};
        GridWorld w = new GridWorld(BlockKind.AIR);
        List<Long> path = new ArrayList<>();
        int top = 0;
        for (int x = 0; x <= 14; x++) {
            top -= drops[x];
            w.fill(x, -20, -3, x, top, 3, BlockKind.SOLID);
            path.add(Pos.pack(x, top + 1, 0));
        }
        Run r = drive(w, path, 0.5, 1, 0.5, -90f, 120);
        assertTrue(r.worstYawOff() < 60, "turned " + r.worstYawOff() + " degrees");
        assertTrue(r.end().x > 13, "got to x=" + r.end().x);
    }

    /** Without flicks it brakes for the end of a run, sprint-jumping or not, without looking aside. */
    @Test
    void stopsAtTheEndWithoutLookingAside() {
        GridWorld w = GridWorld.flatFloor(-3, -3, 30, 3);
        for (int end : new int[]{4, 7, 12, 20}) {
            List<Long> path = new ArrayList<>();
            for (int x = 0; x <= end; x++) path.add(Pos.pack(x, 1, 0));
            Run r = drive(w, path, 0.5, 1, 0.5, -90f, 100, false);
            assertTrue(r.worstYawOff() <= 46, "end " + end + ": turned " + r.worstYawOff() + " degrees");
            assertEquals(end + 0.5, r.end().x, 0.3, "end " + end);
        }
    }

    /** Falling (off the map, say) with next to no sideways motion: the brake doesn't spin round chasing it. */
    @Test
    void airBrakeDoesNotSpinWhenFallingStraightDown() {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (boolean flicks : new boolean[]{true, false}) {
            PlayerSim s = new PlayerSim(w, 0.5, 60, 0.5, -0.01, -0.5, 0.03, false, false);
            float yaw = 180f;
            for (int t = 0; t < 30; t++) {
                PlayerState st = new PlayerState(s.x, s.y, s.z, yaw, 0, s.onGround, false, s.vx, s.vy, s.vz, false, 0,
                    false, 60 - s.y);
                Controls c = InputPlanner.airBrake(st, flicks);
                yaw = InputPlanner.approach(yaw, c.targetYaw(), 45f);
                s.step(c.impulse(), c.strafe(), c.jump(), c.sprint(), c.sneak(), yaw);
            }
            assertEquals(180f, yaw, 1e-3, "flicks " + flicks);
        }
    }
}
