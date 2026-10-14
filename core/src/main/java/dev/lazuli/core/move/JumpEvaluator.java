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
        Script sc = script(w, u, feetU, v, feetV, sprint);
        return sc == null ? INF : sc.ticks();
    }

    /** Hold W and jump all the way (see {@link Script#jumpTick}). */
    public static final int HOP = -3;

    /**
     * The fastest robust way to make the jump, from standing still at u's center: face {@code yaw}, hold W (sprinting
     * if allowed) and jump on tick {@code jumpTick} (-1: never, run off the edge; {@link #HOP}: hold jump throughout);
     * in the air hold W until air tick {@code brakeAt}, then {@code after} (0 nothing, -1 S). Lands on v after
     * {@code ticks}.
     */
    public record Script(float yaw, int jumpTick, int brakeAt, int after, int ticks) {}

    /** See {@link Script}; null if the jump can't be made. */
    public static Script script(BlockView w, long u, double feetU, long v, double feetV, boolean sprint) {
        if (Double.isNaN(feetU) || Double.isNaN(feetV)) return null;
        double sx = Pos.x(u) + 0.5, sz = Pos.z(u) + 0.5;
        double tx = Pos.x(v) + 0.5, tz = Pos.z(v) + 0.5;
        float yaw = (float) (Math.toDegrees(Math.atan2(tz - sz, tx - sx)) - 90.0);
        double best = INF;
        Script found = null;
        // hopping the whole way (sprint-jump chains: under a low ceiling the only way to get the speed for a gap)
        int hop = hop(w, u, feetU, v, feetV, sprint, yaw);
        if (hop >= 0 && hop(w, u, feetU, v, feetV, sprint, yaw + AIM_ERRORS[0]) >= 0
            && hop(w, u, feetU, v, feetV, sprint, yaw + AIM_ERRORS[1]) >= 0) {
            best = hop;
            found = new Script(yaw, HOP, NO_BRAKE, 0, hop);
        }
        // jumpTick -1: never jump, just run off the edge (drops)
        for (int jumpTick = -1; jumpTick < MAX_RUN_TICKS; jumpTick++) {
            for (int brakeAt : BRAKE_AT) {
                for (int after = 0; after >= (brakeAt == NO_BRAKE ? 0 : -1); after--) {
                    int t = run(w, u, feetU, v, feetV, sprint, yaw, jumpTick, brakeAt, after);
                    if (t < 0) {
                        if (t == RUN_LOST) return found; // a later take-off tick can't exist
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
                    if (robust) {
                        best = t;
                        found = new Script(yaw, jumpTick, brakeAt, after, t);
                    }
                }
            }
        }
        return found;
    }

    /**
     * Holding W and jump from the start: landings back at u's height short of v are hops on the run-up; the jump
     * counts if a landing is on v. Ticks to land there, or {@link #MISSED}.
     */
    private static int hop(BlockView w, long u, double feetU, long v, double feetV, boolean sprint, float yaw) {
        if (!sprint) return MISSED;
        PlayerSim s = new PlayerSim(w, Pos.x(u) + 0.5, feetU, Pos.z(u) + 0.5, 0, 0, 0, true, false);
        double tx = Pos.x(v) + 0.5, tz = Pos.z(v) + 0.5;
        double total = Math.hypot(tx - s.x, tz - s.z);
        boolean flew = false;
        for (int t = 1; t <= MAX_RUN_TICKS + MAX_AIR_TICKS; t++) {
            s.step(1, true, true, yaw);
            if (!s.onGround) {
                flew = true;
                if (s.y < feetV - 1) return MISSED;
                continue;
            }
            if (!flew) continue;
            flew = false;
            if (Math.abs(s.y - feetV) < 1e-6 && s.x >= Pos.x(v) + LANDING_MARGIN && s.x <= Pos.x(v) + 1 - LANDING_MARGIN
                && s.z >= Pos.z(v) + LANDING_MARGIN && s.z <= Pos.z(v) + 1 - LANDING_MARGIN) {
                return t;
            }
            // a hop on the run-up: same height, still well short of v
            if (Math.abs(s.y - feetU) > 1e-6 || Math.hypot(tx - s.x, tz - s.z) > total || Math.hypot(tx - s.x, tz - s.z) < 1) {
                return MISSED;
            }
        }
        return MISSED;
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
