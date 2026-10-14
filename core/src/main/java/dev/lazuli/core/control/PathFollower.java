package dev.lazuli.core.control;

import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Turns the next path nodes into inputs and a look target. {@code path.get(0)} is the player's current node.
 * With a world it aims at the farthest node reachable in a straight, clear, flat line, and uses
 * {@link PlayerSim} to decide jumps and braking:
 * <ul>
 *   <li>gap jumps take off on the last tick the player is still supported (never slides off first);</li>
 *   <li>in the air it holds W, nothing or S, whichever keeps the most speed while still landing on the
 *       path without sliding off it (no overshooting short or narrow landings);</li>
 *   <li>on the ground an edge guard brakes when the current momentum would carry it off an unplanned edge;</li>
 *   <li>bunny-hops on straights only when the simulated hop lands safely on the path.</li>
 * </ul>
 * Holds a little state (the landing of the gap jump in progress), so use one instance per player.
 */
public final class PathFollower {
    private static final double BLEND_RADIUS = 0.6;
    private static final double ARRIVE_RADIUS = 0.25;
    private static final double HOP_MIN_DISTANCE = 5.0;
    private static final int LOOKAHEAD = 16;
    private static final int LANDING_WINDOW = 12;
    private static final int MAX_AIR_TICKS = 60;
    private static final int SLIDE_TICKS = 5;
    private static final float LOOK_PITCH = 10f;
    private static final long NONE = Long.MIN_VALUE;

    private final BlockView world;
    private long jumpLanding = NONE;

    public PathFollower() {
        this(null);
    }

    public PathFollower(BlockView world) {
        this.world = world;
    }

    public void reset() {
        jumpLanding = NONE;
    }

    public Controls tick(PlayerState p, List<Long> path, boolean allowSprint) {
        if (path.size() < 2) {
            return new Controls(false, false, false, p.yaw(), p.pitch());
        }
        if (p.onGround()) jumpLanding = NONE;
        long cur = path.get(0), next = path.get(1);
        int j = lookahead(p, path);
        long aim = path.get(j);
        if (!p.onGround() && jumpLanding != NONE && path.indexOf(jumpLanding) >= 0) aim = jumpLanding;
        double tx = Pos.x(aim) + 0.5, tz = Pos.z(aim) + 0.5;
        double dist = Math.hypot(tx - p.x(), tz - p.z());
        if (aim == next && j == 1 && dist < BLEND_RADIUS && path.size() > 2 && Pos.y(path.get(2)) == Pos.y(next)) {
            double t = 0.5 * (1 - dist / BLEND_RADIUS);
            tx += (Pos.x(path.get(2)) + 0.5 - tx) * t;
            tz += (Pos.z(path.get(2)) + 0.5 - tz) * t;
        }
        float targetYaw = dist < 1e-3 ? p.yaw() : yawTo(p.x(), p.z(), tx, tz);
        float err = Math.abs(RotationController.wrap(targetYaw - p.yaw()));
        boolean last = j == path.size() - 1;
        boolean forward = err < 60f && !(last && dist < ARRIVE_RADIUS);
        boolean sprint = forward && allowSprint && err < 15f;
        // the yaw the next tick will most likely move with
        float moveYaw = err < 15f ? targetYaw : p.yaw();

        if (world == null) {
            double nextDist = Math.hypot(Pos.x(next) + 0.5 - p.x(), Pos.z(next) + 0.5 - p.z());
            boolean jump = p.onGround() && forward
                && ((Pos.y(next) > Pos.y(cur) && nextDist < 1.3) || p.horizontalCollision());
            return new Controls(forward, jump, sprint, targetYaw, LOOK_PITCH);
        }

        if (!p.onGround()) {
            return airControl(p, path, targetYaw, moveYaw, forward, sprint);
        }

        int span = Math.max(Math.abs(Pos.x(next) - Pos.x(cur)), Math.abs(Pos.z(next) - Pos.z(cur)));
        boolean gapJump = span >= 2;
        boolean ascend = !gapJump && Pos.y(next) > Pos.y(cur);
        boolean descend = !gapJump && Pos.y(next) < Pos.y(cur);
        double nextDist = Math.hypot(Pos.x(next) + 0.5 - p.x(), Pos.z(next) + 0.5 - p.z());
        int fwd = forward ? 1 : 0;

        if (gapJump) {
            // take off on the last tick we are still supported: one more tick on the ground would drop us
            PlayerSim s = sim(p);
            s.step(fwd, false, sprint, moveYaw);
            if (!s.onGround) {
                jumpLanding = next;
                return new Controls(forward, true, sprint, targetYaw, LOOK_PITCH);
            }
            return new Controls(forward, false, sprint, targetYaw, LOOK_PITCH);
        }

        boolean jump = forward && ((ascend && nextDist < 1.3) || p.horizontalCollision());
        if (!jump && forward && sprint && j > 1 && dist >= HOP_MIN_DISTANCE) {
            jump = hopLandsSafely(p, path, moveYaw);
        }
        if (jump || descend) {
            return new Controls(forward, jump, sprint, targetYaw, LOOK_PITCH);
        }
        // edge guard: never let momentum carry us off an edge the path doesn't step off
        if (!canStillStop(p, fwd, sprint, moveYaw)) {
            if (canStillStop(p, 0, false, moveYaw)) {
                return new Controls(false, false, false, false, targetYaw, LOOK_PITCH);
            }
            return new Controls(false, true, false, false, targetYaw, LOOK_PITCH);
        }
        return new Controls(forward, false, sprint, targetYaw, LOOK_PITCH);
    }

    /** Holds W, nothing or S: the fastest choice whose simulated landing is safely on the path. */
    private Controls airControl(PlayerState p, List<Long> path, float targetYaw, float moveYaw,
                                boolean forward, boolean sprint) {
        int from = 0;
        if (jumpLanding != NONE) {
            int li = path.indexOf(jumpLanding);
            if (li >= 0) from = li;
        }
        long target = jumpLanding != NONE && from > 0 ? jumpLanding : path.get(Math.min(1, path.size() - 1));
        int[] choices = forward ? new int[]{1, 0, -1} : new int[]{0, -1};
        int best = choices[0];
        double bestMiss = Double.POSITIVE_INFINITY;
        for (int fwd : choices) {
            PlayerSim s = sim(p);
            boolean landed = fly(s, fwd, fwd > 0 && sprint, moveYaw, Pos.y(target));
            if (landed && acceptable(s, path, from, moveYaw)) {
                best = fwd;
                bestMiss = -1;
                break;
            }
            double miss = landed
                ? Math.hypot(s.x - (Pos.x(target) + 0.5), s.z - (Pos.z(target) + 0.5)) + Math.abs(s.y - Pos.y(target))
                : 1e6 + Math.hypot(s.x - (Pos.x(target) + 0.5), s.z - (Pos.z(target) + 0.5));
            if (miss < bestMiss) {
                bestMiss = miss;
                best = fwd;
            }
        }
        return new Controls(best > 0, best < 0, false, best > 0 && sprint, targetYaw, LOOK_PITCH);
    }

    /** Steps until the sim lands; false if it falls well below {@code refY} or stays airborne too long. */
    private static boolean fly(PlayerSim s, int fwd, boolean sprint, float yaw, int refY) {
        for (int t = 0; t < MAX_AIR_TICKS; t++) {
            s.step(fwd, false, sprint, yaw);
            if (s.onGround) return true;
            if (s.y < refY - 24) return false;
        }
        return false;
    }

    /**
     * The landed sim stands over a path node (or right next to one, for the zigzag the look-ahead skips),
     * and coasting doesn't slide it off, unless that node starts a gap jump the momentum carries into.
     */
    private boolean acceptable(PlayerSim s, List<Long> path, int from, float yaw) {
        int bx = (int) Math.floor(s.x), by = (int) Math.round(s.y), bz = (int) Math.floor(s.z);
        if (Math.abs(s.y - by) > 1e-6 || !MoveGraph.floor(world.get(bx, by - 1, bz))) return false;
        int exact = -1;
        boolean near = false;
        for (int i = from; i < Math.min(path.size(), from + LANDING_WINDOW); i++) {
            long n = path.get(i);
            if (Pos.y(n) != by) continue;
            int dx = Math.abs(Pos.x(n) - bx), dz = Math.abs(Pos.z(n) - bz);
            if (dx == 0 && dz == 0) {
                exact = i;
                break;
            }
            if (dx <= 1 && dz <= 1) near = true;
        }
        if (exact < 0 && !near) return false;
        PlayerSim c = s.copy();
        boolean stays = true;
        for (int t = 0; t < SLIDE_TICKS && stays; t++) {
            c.step(0, false, false, yaw);
            stays = c.onGround;
        }
        if (stays) return true;
        if (exact >= 0 && exact + 1 < path.size()) {
            long u = path.get(exact), v = path.get(exact + 1);
            int dx = Pos.x(v) - Pos.x(u), dz = Pos.z(v) - Pos.z(u);
            return Math.max(Math.abs(dx), Math.abs(dz)) >= 2 && dx * s.vx + dz * s.vz > 0;
        }
        return false;
    }

    private boolean hopLandsSafely(PlayerState p, List<Long> path, float yaw) {
        PlayerSim s = sim(p);
        s.step(1, true, true, yaw);
        return fly(s, 1, true, yaw, Pos.y(path.get(0))) && acceptable(s, path, 0, yaw);
    }

    /** After one tick of these inputs, coasting still keeps the player on the ground. */
    private boolean canStillStop(PlayerState p, int fwd, boolean sprint, float yaw) {
        PlayerSim s = sim(p);
        s.step(fwd, false, sprint, yaw);
        for (int t = 0; t < SLIDE_TICKS; t++) {
            if (!s.onGround) return false;
            s.step(0, false, false, yaw);
        }
        return s.onGround;
    }

    private PlayerSim sim(PlayerState p) {
        return p.sim(world);
    }

    /** Index of the farthest path node on a clear flat straight line from the player; 1 if none beyond the next. */
    private int lookahead(PlayerState p, List<Long> path) {
        if (world == null) return 1;
        int y = Pos.y(path.get(0));
        int best = 1;
        for (int j = 1; j < Math.min(path.size(), LOOKAHEAD + 1); j++) {
            long n = path.get(j);
            if (Pos.y(n) != y) break;
            long prev = path.get(j - 1);
            if (Math.max(Math.abs(Pos.x(n) - Pos.x(prev)), Math.abs(Pos.z(n) - Pos.z(prev))) >= 2) break;
            if (clearLine(p.x(), p.z(), Pos.x(n) + 0.5, Pos.z(n) + 0.5, y)) best = j;
        }
        return best;
    }

    private boolean clearLine(double x0, double z0, double x1, double z1, int y) {
        double dx = x1 - x0, dz = z1 - z0;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        double ux = dx / len, uz = dz / len;
        double px = -uz * 0.3, pz = ux * 0.3;
        int steps = (int) Math.ceil(len / 0.25);
        for (int i = 0; i <= steps; i++) {
            double t = Math.min(len, i * 0.25);
            double cx = x0 + ux * t, cz = z0 + uz * t;
            for (int side = -1; side <= 1; side++) {
                int bx = (int) Math.floor(cx + px * side), bz = (int) Math.floor(cz + pz * side);
                if (!(MoveGraph.floor(world.get(bx, y - 1, bz))
                    && MoveGraph.passable(world.get(bx, y, bz))
                    && MoveGraph.passable(world.get(bx, y + 1, bz)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Minecraft convention: yaw 0 faces +Z, -90 faces +X. */
    public static float yawTo(double fromX, double fromZ, double toX, double toZ) {
        return (float) (Math.toDegrees(Math.atan2(toZ - fromZ, toX - fromX)) - 90.0);
    }
}
