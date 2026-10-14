package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;

import java.util.List;

/** Turns the next path nodes into key presses and a look target. {@code path.get(0)} is the player's current node. */
public final class PathFollower {
    private static final double BLEND_RADIUS = 0.6;
    private static final double ARRIVE_RADIUS = 0.25;
    private static final float LOOK_PITCH = 10f;

    public Controls tick(PlayerState p, List<Long> path, boolean allowSprint) {
        if (path.size() < 2) {
            return new Controls(false, false, false, p.yaw(), p.pitch());
        }
        long cur = path.get(0), next = path.get(1);
        double tx = Pos.x(next) + 0.5, tz = Pos.z(next) + 0.5;
        double dist = Math.hypot(tx - p.x(), tz - p.z());
        if (dist < BLEND_RADIUS && path.size() > 2 && Pos.y(path.get(2)) == Pos.y(next)) {
            double t = 0.5 * (1 - dist / BLEND_RADIUS);
            tx += (Pos.x(path.get(2)) + 0.5 - tx) * t;
            tz += (Pos.z(path.get(2)) + 0.5 - tz) * t;
        }
        float targetYaw = yawTo(p.x(), p.z(), tx, tz);
        float err = Math.abs(RotationController.wrap(targetYaw - p.yaw()));
        boolean last = path.size() == 2;
        boolean forward = err < 60f && !(last && dist < ARRIVE_RADIUS);
        boolean sprint = forward && allowSprint && err < 15f;
        boolean jump = p.onGround() && forward
            && ((Pos.y(next) > Pos.y(cur) && dist < 1.3) || p.horizontalCollision());
        return new Controls(forward, jump, sprint, targetYaw, LOOK_PITCH);
    }

    /** Minecraft convention: yaw 0 faces +Z, -90 faces +X. */
    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        return (float) (Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90.0);
    }
}
