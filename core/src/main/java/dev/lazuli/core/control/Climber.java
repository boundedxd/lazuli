package dev.lazuli.core.control;

import dev.lazuli.core.move.Climbing;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Drives ladder and vine edges (see {@link Climbing}), which the input planner doesn't: its score is progress along
 * the ground. Up: face the wall, hold W and jump, strafing to stay in the column. Down: face the wall and let go,
 * sneaking to stop where the path steps off. Off: toward the next block, holding jump until clear of the ladder. On:
 * walk into the ladder's column and let go there.
 */
public final class Climber {
    private static final float PITCH_UP = -30f, PITCH_DOWN = 40f;
    /** Lateral offset from the column's center (blocks) that gets strafed back. */
    private static final double DRIFT = 0.12;

    private final BlockView world;

    public Climber(BlockView world) {
        this.world = world;
    }

    /** The controls for the climbing edge the player is on, or null if the next edge isn't climbing. */
    public Controls tick(PlayerState p, List<Long> path, int idx) {
        if (idx < 0 || idx + 1 >= path.size()) return null;
        long u = path.get(idx), v = path.get(idx + 1);
        if (!Climbing.edge(world, u, v)) return null;
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u), vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        if (ux == vx && uz == vz) {
            long ladder = vy > uy ? v : u;
            int[] wall = Climbing.wall(world, Pos.x(ladder), Pos.y(ladder), Pos.z(ladder));
            float yaw = wall[0] == 0 && wall[1] == 0 ? exitYaw(p, path, idx) : yawOf(wall[0], wall[1]);
            if (vy > uy) return new Controls(true, false, true, false, yaw, PITCH_UP, centerStrafe(p, ux, uz, yaw), false);
            // down: slide (pushing into the ladder would climb), holding on once at the node where the path leaves
            // the ladder sideways
            boolean last = idx + 2 >= path.size() || Pos.y(path.get(idx + 2)) >= vy;
            boolean hold = last && p.y() <= vy + 0.05 && !Standing.standable(world, vx, vy, vz);
            return seek(p, ux, uz, wallOf(ladder), yaw, PITCH_DOWN, hold);
        }
        float yaw = PathFollower.yawTo(p.x(), p.z(), vx + 0.5, vz + 0.5);
        if (Climbing.node(world, ux, uy, uz) && Double.isNaN(Standing.feet(world, ux, uy, uz))) {
            // off: rise with jump while still on the ladder, move across once high enough
            double feetV = Standing.feet(world, vx, vy, vz);
            boolean high = Double.isNaN(feetV) || p.y() >= feetV - 0.4;
            return new Controls(high, false, !p.onGround(), false, yaw, 10f, 0, false);
        }
        // on: back into the ladder's column facing the wall, slowly enough to stop there (in the air there's next to
        // no braking, and drifting out of the column is a fall)
        int[] wall = wallOf(v);
        return seek(p, vx, vz, wall, wall[0] == 0 && wall[1] == 0 ? yaw : yawOf(wall[0], wall[1]), 10f, false);
    }

    /** Fastest approach to a column's center (blocks per tick), and how much of the distance to cover per tick. */
    private static final double SEEK_SPEED = 0.12, SEEK_GAIN = 0.3, SEEK_DEADBAND = 0.015, SEEK_MIN = 0.04;
    /**
     * How far from the column's center, away from the wall, it aims: clear of the ladder's box, which it would
     * otherwise stand on (from above) or bump and climb (on the way down).
     */
    private static final double OFF_WALL = 0.1;

    private int[] wallOf(long ladder) {
        return Climbing.wall(world, Pos.x(ladder), Pos.y(ladder), Pos.z(ladder));
    }

    /**
     * Moves toward the middle of column (bx, bz), a little off the wall, without overshooting, facing {@code yaw}: the velocity wanted shrinks
     * with the distance, and W/S and A/D each push whichever way closes the gap between wanted and actual.
     */
    static Controls seek(PlayerState p, int bx, int bz, int[] wall, float yaw, float pitch, boolean sneak) {
        double tx = bx + 0.5 - wall[0] * OFF_WALL, tz = bz + 0.5 - wall[1] * OFF_WALL;
        double wx = (tx - p.x()) * SEEK_GAIN, wz = (tz - p.z()) * SEEK_GAIN;
        double len = Math.hypot(wx, wz), dist = len / SEEK_GAIN;
        double speed = Math.min(SEEK_SPEED, dist < 0.03 ? len : Math.max(SEEK_MIN, len));
        if (len > 1e-9) {
            wx *= speed / len;
            wz *= speed / len;
        }
        double ex = wx - p.vx(), ez = wz - p.vz();
        double rad = Math.toRadians(yaw);
        double along = ex * -Math.sin(rad) + ez * Math.cos(rad), side = ex * Math.cos(rad) + ez * Math.sin(rad);
        int fwd = along > SEEK_DEADBAND ? 1 : along < -SEEK_DEADBAND ? -1 : 0;
        int strafe = side > SEEK_DEADBAND ? 1 : side < -SEEK_DEADBAND ? -1 : 0;
        return new Controls(fwd > 0, fwd < 0, false, false, yaw, pitch, strafe, sneak);
    }

    /** Yaw facing direction {dx, dz}. */
    private static float yawOf(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** No wall (a free-hanging vine): face where the path goes once off it. */
    private static float exitYaw(PlayerState p, List<Long> path, int idx) {
        for (int i = idx + 1; i < path.size(); i++) {
            long n = path.get(i);
            if (Pos.x(n) != Pos.x(path.get(idx)) || Pos.z(n) != Pos.z(path.get(idx))) {
                return PathFollower.yawTo(p.x(), p.z(), Pos.x(n) + 0.5, Pos.z(n) + 0.5);
            }
        }
        return p.yaw();
    }

    /** Strafe (+1 left, -1 right) back toward the column's center across the facing direction. */
    private static int centerStrafe(PlayerState p, int bx, int bz, float yaw) {
        double rad = Math.toRadians(yaw);
        // left of facing (-sin, cos) is (cos, sin)
        double lx = Math.cos(rad), lz = Math.sin(rad);
        double off = (p.x() - (bx + 0.5)) * lx + (p.z() - (bz + 0.5)) * lz;
        return off > DRIFT ? -1 : off < -DRIFT ? 1 : 0;
    }
}
