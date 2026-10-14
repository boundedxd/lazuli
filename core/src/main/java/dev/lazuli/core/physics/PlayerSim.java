package dev.lazuli.core.physics;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;

/**
 * Tick-accurate port of vanilla 1.21.11 player ground/air movement over full blocks (no ice, slabs, fluids
 * or step-up). One {@link #step} is one client tick of {@code LocalPlayer.aiStep}: jump, input, travel.
 * State is mutable and cheap to {@link #copy}, so callers can try several input sequences.
 */
public final class PlayerSim {
    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    private static final double GRAVITY = 0.08;
    private static final double JUMP_POWER = 0.42;
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final double GROUND_FRICTION = 0.6;
    private static final double EPS = 1e-7;

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, horizontalCollision;
    public int noJumpDelay;
    private final BlockView world;

    public PlayerSim(BlockView world, double x, double y, double z, double vx, double vy, double vz,
                     boolean onGround, boolean sprinting) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.onGround = onGround;
        this.sprinting = sprinting;
        // a player standing on the ground always carries one tick of gravity into the next tick
        if (onGround && vy == 0) this.vy = -GRAVITY * 0.98;
    }

    public PlayerSim copy() {
        PlayerSim s = new PlayerSim(world, x, y, z, vx, vy, vz, onGround, sprinting);
        s.noJumpDelay = noJumpDelay;
        return s;
    }

    /** Solid for collision: full blocks, odd shapes (conservatively) and unloaded terrain. */
    public static boolean solid(BlockKind k) {
        return k == BlockKind.SOLID || k == BlockKind.OBSTACLE || k == BlockKind.UNKNOWN;
    }

    /**
     * Advances one tick.
     *
     * @param forward 1 for W, -1 for S, 0 for neither
     * @param yaw     Minecraft degrees (0 faces +Z, -90 faces +X)
     */
    public void step(int forward, boolean jump, boolean sprintKey, float yaw) {
        // sprint state (LocalPlayer.aiStep): needs forward impulse; held W keeps it, releasing W stops it
        if (forward <= 0) sprinting = false;
        else if (sprintKey) sprinting = true;

        if (noJumpDelay > 0) noJumpDelay--;
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        if (jump) {
            if (onGround && noJumpDelay == 0) {
                vy = Math.max(JUMP_POWER, vy);
                if (sprinting) {
                    vx += -sin * SPRINT_JUMP_BOOST;
                    vz += cos * SPRINT_JUMP_BOOST;
                }
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }

        double f = onGround ? GROUND_FRICTION : 1.0;
        double drag = f * 0.91;
        double speed = onGround
            ? (sprinting ? 0.13 : 0.1) * (0.21600002 / (f * f * f))
            : (sprinting ? 0.025999999 : 0.02);
        double impulse = forward * 0.98 * speed;
        vx += -impulse * sin;
        vz += impulse * cos;

        move(vx, vy, vz);

        vy = (vy - GRAVITY) * 0.98;
        vx *= drag;
        vz *= drag;
    }

    private void move(double dx, double dy, double dz) {
        double ady = collideY(dy);
        boolean hitY = Math.abs(ady - dy) > EPS;
        y += ady;
        onGround = hitY && dy < 0;
        if (hitY) vy = 0;
        double adx, adz;
        if (Math.abs(dx) < Math.abs(dz)) {
            adz = collideZ(dz);
            z += adz;
            adx = collideX(dx);
            x += adx;
        } else {
            adx = collideX(dx);
            x += adx;
            adz = collideZ(dz);
            z += adz;
        }
        boolean hitX = Math.abs(adx - dx) > EPS, hitZ = Math.abs(adz - dz) > EPS;
        if (hitX) vx = 0;
        if (hitZ) vz = 0;
        horizontalCollision = hitX || hitZ;
    }

    private boolean blocked(double x0, double y0, double z0, double x1, double y1, double z1) {
        for (int bx = (int) Math.floor(x0 + EPS); bx <= (int) Math.floor(x1 - EPS); bx++) {
            for (int by = (int) Math.floor(y0 + EPS); by <= (int) Math.floor(y1 - EPS); by++) {
                for (int bz = (int) Math.floor(z0 + EPS); bz <= (int) Math.floor(z1 - EPS); bz++) {
                    if (solid(world.get(bx, by, bz))) return true;
                }
            }
        }
        return false;
    }

    private double collideY(double dy) {
        if (dy == 0) return 0;
        double x0 = x - HALF_WIDTH, x1 = x + HALF_WIDTH, z0 = z - HALF_WIDTH, z1 = z + HALF_WIDTH;
        if (dy < 0) {
            // highest solid top in [y+dy, y]
            for (int by = (int) Math.floor(y - EPS); by >= (int) Math.floor(y + dy); by--) {
                if (blocked(x0, by, z0, x1, by + 1, z1)) return Math.min(0, by + 1 - y);
            }
        } else {
            double top = y + HEIGHT;
            for (int by = (int) Math.floor(top + EPS); by <= (int) Math.floor(top + dy - EPS); by++) {
                if (blocked(x0, by, z0, x1, by + 1, z1)) return Math.max(0, by - top);
            }
        }
        return dy;
    }

    private double collideX(double dx) {
        if (dx == 0) return 0;
        double y0 = y, y1 = y + HEIGHT, z0 = z - HALF_WIDTH, z1 = z + HALF_WIDTH;
        if (dx > 0) {
            double edge = x + HALF_WIDTH;
            for (int bx = (int) Math.floor(edge + EPS); bx <= (int) Math.floor(edge + dx - EPS); bx++) {
                if (blocked(bx, y0, z0, bx + 1, y1, z1)) return Math.max(0, bx - edge);
            }
        } else {
            double edge = x - HALF_WIDTH;
            for (int bx = (int) Math.floor(edge - EPS); bx >= (int) Math.floor(edge + dx); bx--) {
                if (blocked(bx, y0, z0, bx + 1, y1, z1)) return Math.min(0, bx + 1 - edge);
            }
        }
        return dx;
    }

    private double collideZ(double dz) {
        if (dz == 0) return 0;
        double y0 = y, y1 = y + HEIGHT, x0 = x - HALF_WIDTH, x1 = x + HALF_WIDTH;
        if (dz > 0) {
            double edge = z + HALF_WIDTH;
            for (int bz = (int) Math.floor(edge + EPS); bz <= (int) Math.floor(edge + dz - EPS); bz++) {
                if (blocked(x0, y0, bz, x1, y1, bz + 1)) return Math.max(0, bz - edge);
            }
        } else {
            double edge = z - HALF_WIDTH;
            for (int bz = (int) Math.floor(edge - EPS); bz >= (int) Math.floor(edge + dz); bz--) {
                if (blocked(x0, y0, bz, x1, y1, bz + 1)) return Math.min(0, bz + 1 - edge);
            }
        }
        return dz;
    }
}
