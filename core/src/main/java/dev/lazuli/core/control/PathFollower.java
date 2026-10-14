package dev.lazuli.core.control;

import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Turns the next path nodes into key presses and a look target. {@code path.get(0)} is the player's current node.
 * With a world, it aims at the farthest node reachable in a straight, clear, flat line (ignoring the planner's
 * zigzag between equal-cost steps) and bunny-hops along long straights.
 */
public final class PathFollower {
    private static final double BLEND_RADIUS = 0.6;
    private static final double ARRIVE_RADIUS = 0.25;
    private static final double HOP_MIN_DISTANCE = 5.0;
    private static final int LOOKAHEAD = 16;
    private static final float LOOK_PITCH = 10f;

    private final BlockView world;

    public PathFollower() {
        this(null);
    }

    public PathFollower(BlockView world) {
        this.world = world;
    }

    public Controls tick(PlayerState p, List<Long> path, boolean allowSprint) {
        if (path.size() < 2) {
            return new Controls(false, false, false, p.yaw(), p.pitch());
        }
        long cur = path.get(0), next = path.get(1);
        int j = lookahead(p, path);
        long aim = path.get(j);
        double tx = Pos.x(aim) + 0.5, tz = Pos.z(aim) + 0.5;
        double dist = Math.hypot(tx - p.x(), tz - p.z());
        if (j == 1 && dist < BLEND_RADIUS && path.size() > 2 && Pos.y(path.get(2)) == Pos.y(next)) {
            double t = 0.5 * (1 - dist / BLEND_RADIUS);
            tx += (Pos.x(path.get(2)) + 0.5 - tx) * t;
            tz += (Pos.z(path.get(2)) + 0.5 - tz) * t;
        }
        float targetYaw = yawTo(p.x(), p.z(), tx, tz);
        float err = Math.abs(RotationController.wrap(targetYaw - p.yaw()));
        boolean last = j == path.size() - 1;
        boolean forward = err < 60f && !(last && dist < ARRIVE_RADIUS);
        boolean sprint = forward && allowSprint && err < 15f;
        boolean hop = sprint && j > 1 && dist >= HOP_MIN_DISTANCE;
        double nextDist = Math.hypot(Pos.x(next) + 0.5 - p.x(), Pos.z(next) + 0.5 - p.z());
        int span = Math.max(Math.abs(Pos.x(next) - Pos.x(cur)), Math.abs(Pos.z(next) - Pos.z(cur)));
        boolean gapJump = span >= 2;
        boolean takeoff = gapJump && pastEdge(p, next, Pos.y(cur));
        boolean jump = p.onGround() && forward
            && ((!gapJump && Pos.y(next) > Pos.y(cur) && nextDist < 1.3) || takeoff
                || (!gapJump && p.horizontalCollision()) || hop);
        return new Controls(forward, jump, sprint, targetYaw, LOOK_PITCH);
    }

    /**
     * True once the player's center has crossed the take-off edge toward {@code landing}: the hitbox still
     * overhangs the block (it is 0.6 wide), so this is the latest tick to jump and gives the longest jump.
     */
    private boolean pastEdge(PlayerState p, long landing, int y) {
        if (world == null) return false;
        double dx = Pos.x(landing) + 0.5 - p.x(), dz = Pos.z(landing) + 0.5 - p.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return false;
        int bx = (int) Math.floor(p.x() + dx / len * 0.1), bz = (int) Math.floor(p.z() + dz / len * 0.1);
        return !MoveGraph.floor(world.get(bx, y - 1, bz));
    }

    /** Index of the farthest path node on a clear flat straight line from the player; 1 if none beyond the next. */
    private int lookahead(PlayerState p, List<Long> path) {
        if (world == null) return 1;
        int y = Pos.y(path.get(0));
        int best = 1;
        for (int j = 1; j < Math.min(path.size(), LOOKAHEAD + 1); j++) {
            if (Pos.y(path.get(j)) != y) break;
            long n = path.get(j);
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
