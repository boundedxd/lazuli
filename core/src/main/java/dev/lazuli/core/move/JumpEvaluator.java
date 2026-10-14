package dev.lazuli.core.move;

import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

/**
 * Decides whether a jump u -> v is physically possible by simulating it: start standing at the center of u,
 * run straight at v (sprinting if allowed), try every take-off tick while still supported, and in the air hold
 * W for a while and then let go or brake with S. The jump counts if it lands standing over v and still does
 * with the aim a couple of degrees off either way. Works for any direction, distance and height change.
 */
public final class JumpEvaluator {
    public static final double INF = Double.POSITIVE_INFINITY;
    private static final int MAX_RUN_TICKS = 40;
    private static final int MAX_AIR_TICKS = 60;
    private static final int NO_BRAKE = Integer.MAX_VALUE;
    private static final int[] BRAKE_AT = {NO_BRAKE, 12, 10, 8, 7, 6, 5, 4, 3, 2, 1, 0};
    private static final float[] AIM_ERRORS = {-2f, 2f};
    private static final double LANDING_MARGIN = 0.05;

    private JumpEvaluator() {}

    /** Ticks from standing at u to landing on v, or {@link #INF} if no robust input sequence works. */
    public static double ticks(BlockView w, long u, long v, boolean sprint) {
        return ticks(w, u, Standing.feet(w, Pos.x(u), Pos.y(u), Pos.z(u)), v, Standing.feet(w, Pos.x(v), Pos.y(v), Pos.z(v)), sprint);
    }

    /** As {@link #ticks(BlockView, long, long, boolean)} with the feet heights at u and v already known. */
    public static double ticks(BlockView w, long u, double feetU, long v, double feetV, boolean sprint) {
        if (Double.isNaN(feetU) || Double.isNaN(feetV)) return INF;
        double sx = Pos.x(u) + 0.5, sz = Pos.z(u) + 0.5;
        double tx = Pos.x(v) + 0.5, tz = Pos.z(v) + 0.5;
        float yaw = (float) (Math.toDegrees(Math.atan2(tz - sz, tx - sx)) - 90.0);
        double best = INF;
        // jumpTick -1: never jump, just run off the edge (drops)
        for (int jumpTick = -1; jumpTick < MAX_RUN_TICKS; jumpTick++) {
            for (int brakeAt : BRAKE_AT) {
                for (int after = 0; after >= (brakeAt == NO_BRAKE ? 0 : -1); after--) {
                    int t = run(w, u, feetU, v, feetV, sprint, yaw, jumpTick, brakeAt, after);
                    if (t < 0) {
                        if (t == RUN_LOST) return best; // a later take-off tick can't exist
                        continue;
                    }
                    if (t >= best) continue;
                    boolean robust = true;
                    for (float e : AIM_ERRORS) {
                        if (run(w, u, feetU, v, feetV, sprint, yaw + e, jumpTick, brakeAt, after) < 0) {
                            robust = false;
                            break;
                        }
                    }
                    if (robust) best = t;
                }
            }
        }
        return best;
    }

    private static final int RUN_LOST = -2;
    private static final int MISSED = -1;

    /**
     * @return landing tick count, {@link #MISSED}, or {@link #RUN_LOST} if the player left the ground before
     *         {@code jumpTick} (so no later take-off is possible either)
     */
    private static int run(BlockView w, long u, double feetU, long v, double feetV, boolean sprint, float yaw,
                           int jumpTick, int brakeAt, int after) {
        PlayerSim s = new PlayerSim(w, Pos.x(u) + 0.5, feetU, Pos.z(u) + 0.5, 0, 0, 0, true, false);
        double vy = feetV;
        int t0 = 0;
        if (jumpTick < 0) {
            while (s.onGround) {
                if (++t0 > MAX_RUN_TICKS || s.horizontalCollision) return MISSED;
                s.step(1, false, sprint, yaw);
            }
            if (vy >= feetU) return MISSED;
        } else {
            for (int t = 0; t < jumpTick; t++) {
                s.step(1, false, sprint, yaw);
                if (!s.onGround) return RUN_LOST;
            }
            s.step(1, true, sprint, yaw);
            if (s.onGround) return MISSED; // no take-off (ceiling or similar)
            t0 = jumpTick + 1;
        }
        for (int a = 0; a < MAX_AIR_TICKS; a++) {
            int fwd = a >= brakeAt ? after : 1;
            s.step(fwd, false, sprint && fwd > 0, yaw);
            if (s.onGround) {
                boolean onV = Math.abs(s.y - vy) < 1e-6
                    && s.x >= Pos.x(v) + LANDING_MARGIN && s.x <= Pos.x(v) + 1 - LANDING_MARGIN
                    && s.z >= Pos.z(v) + LANDING_MARGIN && s.z <= Pos.z(v) + 1 - LANDING_MARGIN;
                return onV ? t0 + 1 + a : MISSED;
            }
            if (s.y < vy - 1) return MISSED;
        }
        return MISSED;
    }
}
