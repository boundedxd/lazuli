package dev.lazuli.core.physics;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;

import java.util.Arrays;

/**
 * Tick-accurate port of vanilla 1.21.11 player movement ({@code LocalPlayer.aiStep} through
 * {@code LivingEntity.travel} and {@code Entity.move}): input, sprint and jump, ground and air friction, block
 * friction/speed/jump factors, collision against real block shapes with the 0.6 step-up, slime and bed
 * bounces, slime slowdown, ladders and vines, and swimming in water (not sprint-swimming). State is mutable and
 * cheap to {@link #copy}, so callers can try many input sequences.
 */
public final class PlayerSim {
    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    public static final double STEP_HEIGHT = 0.6;
    private static final double GRAVITY = 0.08;
    private static final double JUMP_POWER = 0.42;
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final double WATER_HEIGHT = 8.0 / 9.0;
    private static final double EPS = 1e-7;
    /** Attributes.SNEAKING_SPEED default. */
    private static final double SNEAK_SPEED = 0.3;

    public double x, y, z, vx, vy, vz;
    public boolean onGround, sprinting, horizontalCollision, inWater;
    /** Shift held this tick. */
    public boolean sneaking;
    /** Highest feet height since last on the ground or in water: a landing's fall is this minus the landing height. */
    public double peakY;
    /** The last horizontal collision only grazed (movement within 8 degrees of the input): sprinting survives it. */
    public boolean minorHorizontalCollision;
    /** This tick's input direction in world space, for {@link #minorHorizontalCollision}. */
    private double inputX, inputZ;
    public int noJumpDelay;
    private final BlockView world;

    // scratch collider list: world-space boxes, 6 doubles each, grouped by block (groupEnd marks each block's end)
    private double[] col = new double[6 * 32];
    private int[] groupEnd = new int[32];
    private int colCount, groupCount;

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
        this.inWater = fluidHeight() > 0;
        this.peakY = y;
    }

    public PlayerSim copy() {
        PlayerSim s = new PlayerSim(world, x, y, z, vx, vy, vz, onGround, sprinting);
        s.vy = vy;
        s.noJumpDelay = noJumpDelay;
        s.horizontalCollision = horizontalCollision;
        s.minorHorizontalCollision = minorHorizontalCollision;
        s.inWater = inWater;
        s.sneaking = sneaking;
        s.peakY = peakY;
        return s;
    }

    /** Solid for planning purposes: full blocks, odd shapes (conservatively) and unloaded terrain. */
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
        step(forward, 0, jump, sprintKey, yaw);
    }

    /**
     * Advances one tick.
     *
     * @param forward 1 for W, -1 for S, 0 for neither
     * @param strafe  1 for A (left), -1 for D (right), 0 for neither
     * @param yaw     Minecraft degrees (0 faces +Z, -90 faces +X)
     */
    public void step(int forward, int strafe, boolean jump, boolean sprintKey, float yaw) {
        step(forward, strafe, jump, sprintKey, false, yaw);
    }

    /**
     * Advances one tick.
     *
     * @param sneak shift held: input scaled by the sneaking speed (0.3), no sprinting, and on the ground the player
     *              won't walk off an edge higher than a step ({@code Player.maybeBackOffFromEdge})
     */
    public void step(int forward, int strafe, boolean jump, boolean sprintKey, boolean sneak, float yaw) {
        sneaking = sneak;
        if (onGround || inWater) peakY = y; // a new fall starts from where the player stood or swam
        // sprint state (LocalPlayer.aiStep): needs forward impulse; held W keeps it, releasing W stops it
        if (forward <= 0 || inWater || sneak) sprinting = false;
        else if (sprintKey) sprinting = true;
        // LocalPlayer.shouldStopRunSprinting: last tick's head-on collision ends the sprint
        if (sprinting && horizontalCollision && !minorHorizontalCollision) sprinting = false;

        if (noJumpDelay > 0) noJumpDelay--;
        if (Math.abs(vx) < 0.003) vx = 0;
        if (Math.abs(vy) < 0.003) vy = 0;
        if (Math.abs(vz) < 0.003) vz = 0;
        double rad = Math.toRadians(yaw);
        double sin = Math.sin(rad), cos = Math.cos(rad);
        if (jump) {
            if (inWater && (!onGround || fluidHeight() > 0.4)) {
                vy += 0.04; // jumpInLiquid
            } else if (onGround && noJumpDelay == 0) {
                vy = Math.max(JUMP_POWER * jumpFactor(), vy);
                if (sprinting) {
                    vx += -sin * SPRINT_JUMP_BOOST;
                    vz += cos * SPRINT_JUMP_BOOST;
                }
                noJumpDelay = 10;
            }
        } else {
            noJumpDelay = 0;
        }

        // KeyboardInput normalizes (strafe, forward); LocalPlayer scales by 0.98 and stretches diagonals to the
        // unit square (modifyInputSpeedForSquareMovement); Entity.getInputVector rotates by yaw
        double mx = strafe, mz = forward, len = Math.hypot(mx, mz);
        if (len > 0) {
            mx /= len;
            mz /= len;
            double ax = Math.abs(mx), az = Math.abs(mz);
            double ratio = az > ax ? ax / az : az / ax;
            double scale = Math.min(0.98 * (sneak ? SNEAK_SPEED : 1) * Math.sqrt(1 + ratio * ratio), 1.0);
            mx *= scale;
            mz *= scale;
        }
        double ix = mx * cos - mz * sin, iz = mz * cos + mx * sin;
        inputX = ix;
        inputZ = iz;
        if (inWater) {
            travelInWater(ix, iz);
        } else {
            travelInAir(ix, iz, jump);
        }

        // Block.stepOn (slime slowdown) runs after travel
        if (onGround) {
            BlockPhysics on = world.physics(fx(x), fx(y - 0.2), fx(z));
            if (on.has(BlockPhysics.SLIME) && Math.abs(vy) < 0.1) {
                double d = 0.4 + Math.abs(vy) * 0.2;
                vx *= d;
                vz *= d;
            }
        }
        inWater = fluidHeight() > 0;
        if (inWater) peakY = y;
        else peakY = Math.max(peakY, y);
    }

    /** Blocks fallen by a landing this tick (0 if not landing): what decides fall damage. */
    public double fallen() {
        return onGround ? Math.max(0, peakY - y) : 0;
    }

    private void travelInAir(double ix, double iz, boolean jumping) {
        double f = onGround ? supporting(0.500001).friction : 1.0;
        double drag = f * 0.91;
        double speed = onGround
            ? (sprinting ? 0.13 : 0.1) * (0.21600002 / (f * f * f))
            : (sprinting ? 0.025999999 : 0.02);
        vx += ix * speed;
        vz += iz * speed;
        boolean climbable = onClimbable();
        if (climbable) {
            vx = clamp(vx, -0.15, 0.15);
            vz = clamp(vz, -0.15, 0.15);
            vy = Math.max(vy, -0.15);
        }
        move(vx, vy, vz);
        if ((horizontalCollision || jumping) && climbable) vy = 0.2;
        vy = (vy - GRAVITY) * 0.98;
        vx *= drag;
        vz *= drag;
    }

    private void travelInWater(double ix, double iz) {
        boolean falling = vy <= 0;
        double yBefore = y;
        double f = 0.8; // never sprinting in water here (no sprint-swimming)
        vx += ix * 0.02;
        vz += iz * 0.02;
        move(vx, vy, vz);
        if (horizontalCollision && onClimbable()) vy = 0.2;
        vx *= f;
        vy *= 0.8;
        vz *= f;
        // getFluidFallingAdjustedMovement
        if (falling && Math.abs(vy - 0.005) >= 0.003 && Math.abs(vy - GRAVITY / 16) < 0.003) {
            vy = -0.003;
        } else {
            vy -= GRAVITY / 16;
        }
        // jumpOutOfFluid
        if (horizontalCollision && free(vx, vy + 0.6 - y + yBefore, vz)) vy = 0.3;
    }

    // ---- Entity.move ----

    /** LocalPlayer.isHorizontalCollisionMinor: the actual movement is within about 8 degrees of the input. */
    private boolean minorCollision(double mx, double mz) {
        double i = inputX * inputX + inputZ * inputZ, j = mx * mx + mz * mz;
        if (i < 1.0E-5F || j < 1.0E-5F) return false;
        return Math.acos((inputX * mx + inputZ * mz) / Math.sqrt(i * j)) < 0.13962634F;
    }

    private void move(double dx, double dy, double dz) {
        if (sneaking && onGround && dy <= 0) {
            // Player.maybeBackOffFromEdge: shorten the step on each axis until there's ground within a step below
            double sx = Math.signum(dx) * 0.05, sz = Math.signum(dz) * 0.05;
            while (dx != 0 && canFall(dx, 0)) {
                if (Math.abs(dx) <= 0.05) { dx = 0; break; }
                dx -= sx;
            }
            while (dz != 0 && canFall(0, dz)) {
                if (Math.abs(dz) <= 0.05) { dz = 0; break; }
                dz -= sz;
            }
            while (dx != 0 && dz != 0 && canFall(dx, dz)) {
                dx = Math.abs(dx) <= 0.05 ? 0 : dx - sx;
                dz = Math.abs(dz) <= 0.05 ? 0 : dz - sz;
            }
        }
        double[] r = collide(dx, dy, dz);
        x += r[0];
        y += r[1];
        z += r[2];
        boolean hitX = Math.abs(dx - r[0]) > 1e-5, hitZ = Math.abs(dz - r[2]) > 1e-5;
        horizontalCollision = hitX || hitZ;
        minorHorizontalCollision = horizontalCollision && minorCollision(r[0], r[2]);
        boolean vertical = dy != r[1];
        onGround = vertical && dy < 0;
        if (hitX) vx = 0;
        if (hitZ) vz = 0;
        if (vertical) {
            BlockPhysics on = world.physics(fx(x), fx(y - 0.2), fx(z));
            if (on.has(BlockPhysics.SLIME) && vy < 0) vy = -vy;
            else if (on.has(BlockPhysics.BED) && vy < 0) vy = -vy * 0.66;
            else vy = 0;
        }
        double sf = speedFactor();
        vx *= sf;
        vz *= sf;
    }

    private double[] collide(double dx, double dy, double dz) {
        double x0 = x - HALF_WIDTH, y0 = y, z0 = z - HALF_WIDTH, x1 = x + HALF_WIDTH, y1 = y + HEIGHT, z1 = z + HALF_WIDTH;
        double[] r;
        if (dx == 0 && dy == 0 && dz == 0) {
            r = new double[]{0, 0, 0};
        } else {
            gather(Math.min(x0, x0 + dx), Math.min(y0, y0 + dy), Math.min(z0, z0 + dz),
                Math.max(x1, x1 + dx), Math.max(y1, y1 + dy), Math.max(z1, z1 + dz));
            r = collideWithShapes(dx, dy, dz, x0, y0, z0, x1, y1, z1);
        }
        boolean bx = dx != r[0], by = dy != r[1], bz = dz != r[2];
        boolean landing = by && dy < 0;
        if ((landing || onGround) && (bx || bz)) {
            double shift = landing ? r[1] : 0;
            double sy0 = y0 + shift, sy1 = y1 + shift;
            double gy0 = landing ? sy0 : sy0 - 1.0E-5F;
            gather(Math.min(x0, x0 + dx), gy0, Math.min(z0, z0 + dz), Math.max(x1, x1 + dx), sy1 + STEP_HEIGHT,
                Math.max(z1, z1 + dz));
            float fy = (float) r[1];
            float[] heights = stepHeights(sy0, fy);
            double h0 = r[0] * r[0] + r[2] * r[2];
            for (float g : heights) {
                double[] s = collideWithShapes(dx, g, dz, x0, sy0, z0, x1, sy1, z1);
                if (s[0] * s[0] + s[2] * s[2] > h0) {
                    s[1] -= y0 - sy0; // vanilla: vec33.subtract(0, aABB.minY - aABB2.minY, 0)
                    return s;
                }
            }
        }
        return r;
    }

    /** Vanilla collectCandidateStepUpHeights: shape y-coordinates within step height above the box bottom. */
    private float[] stepHeights(double boxMinY, float exclude) {
        float[] out = new float[8];
        int n = 0, start = 0;
        double[] ys = new double[16];
        for (int g = 0; g < groupCount; g++) {
            int end = groupEnd[g];
            int k = 0;
            if (ys.length < (end - start) / 3) ys = new double[(end - start) / 3];
            for (int i = start; i < end; i += 6) {
                ys[k++] = col[i + 1];
                ys[k++] = col[i + 4];
            }
            Arrays.sort(ys, 0, k);
            for (int i = 0; i < k; i++) {
                float h = (float) (ys[i] - boxMinY);
                if (h < 0.0F || h == exclude) continue;
                if (h > (float) STEP_HEIGHT) break;
                boolean dup = false;
                for (int j = 0; j < n && !dup; j++) dup = out[j] == h;
                if (!dup) {
                    if (n == out.length) out = Arrays.copyOf(out, n * 2);
                    out[n++] = h;
                }
            }
            start = end;
        }
        float[] r = Arrays.copyOf(out, n);
        Arrays.sort(r);
        return r;
    }

    /** Vanilla collideWithShapes: Y first, then the larger horizontal axis first (Direction.axisStepOrder). */
    private double[] collideWithShapes(double dx, double dy, double dz,
                                       double x0, double y0, double z0, double x1, double y1, double z1) {
        double ry = 0, rx = 0, rz = 0;
        if (dy != 0) {
            ry = collideAxis(1, dy, x0, y0, z0, x1, y1, z1);
            y0 += ry;
            y1 += ry;
        }
        if (Math.abs(dx) < Math.abs(dz)) {
            if (dz != 0) {
                rz = collideAxis(2, dz, x0, y0, z0, x1, y1, z1);
                z0 += rz;
                z1 += rz;
            }
            if (dx != 0) rx = collideAxis(0, dx, x0, y0, z0, x1, y1, z1);
        } else {
            if (dx != 0) {
                rx = collideAxis(0, dx, x0, y0, z0, x1, y1, z1);
                x0 += rx;
                x1 += rx;
            }
            if (dz != 0) rz = collideAxis(2, dz, x0, y0, z0, x1, y1, z1);
        }
        return new double[]{rx, ry, rz};
    }

    /** Shapes.collide for one axis (0=x, 1=y, 2=z) against the gathered colliders. */
    private double collideAxis(int axis, double d, double x0, double y0, double z0, double x1, double y1, double z1) {
        int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
        double lo1 = pick(a1, x0, y0, z0), hi1 = pick(a1, x1, y1, z1);
        double lo2 = pick(a2, x0, y0, z0), hi2 = pick(a2, x1, y1, z1);
        double lo = pick(axis, x0, y0, z0), hi = pick(axis, x1, y1, z1);
        for (int i = 0; i < colCount * 6; i += 6) {
            if (col[i + a1] >= hi1 - EPS || col[i + 3 + a1] <= lo1 + EPS) continue;
            if (col[i + a2] >= hi2 - EPS || col[i + 3 + a2] <= lo2 + EPS) continue;
            if (d > 0) {
                if (col[i + axis] >= hi - EPS) d = Math.min(d, col[i + axis] - hi);
            } else {
                if (col[i + 3 + axis] <= lo + EPS) d = Math.max(d, col[i + 3 + axis] - lo);
            }
            if (Math.abs(d) < EPS) return 0;
        }
        return d;
    }

    private static double pick(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    /** Collects world-space collision boxes of every block intersecting the region. */
    private void gather(double x0, double y0, double z0, double x1, double y1, double z1) {
        colCount = 0;
        groupCount = 0;
        int bx0 = fx(x0 - EPS), by0 = fx(y0 - EPS) - 1, bz0 = fx(z0 - EPS); // y-1: fences and walls reach up 1.5
        int bx1 = fx(x1 + EPS), by1 = fx(y1 + EPS), bz1 = fx(z1 + EPS);
        for (int bx = bx0; bx <= bx1; bx++) {
            for (int bz = bz0; bz <= bz1; bz++) {
                for (int by = by0; by <= by1; by++) {
                    double[] b = world.physics(bx, by, bz).boxes;
                    if (b.length == 0) continue;
                    boolean any = false;
                    for (int i = 0; i < b.length; i += 6) {
                        double minX = bx + b[i], minY = by + b[i + 1], minZ = bz + b[i + 2];
                        double maxX = bx + b[i + 3], maxY = by + b[i + 4], maxZ = bz + b[i + 5];
                        if (maxX <= x0 || minX >= x1 || maxY <= y0 || minY >= y1 || maxZ <= z0 || minZ >= z1) continue;
                        if ((colCount + 1) * 6 > col.length) col = Arrays.copyOf(col, col.length * 2);
                        int o = colCount * 6;
                        col[o] = minX;
                        col[o + 1] = minY;
                        col[o + 2] = minZ;
                        col[o + 3] = maxX;
                        col[o + 4] = maxY;
                        col[o + 5] = maxZ;
                        colCount++;
                        any = true;
                    }
                    if (any) {
                        if (groupCount == groupEnd.length) groupEnd = Arrays.copyOf(groupEnd, groupCount * 2);
                        groupEnd[groupCount++] = colCount * 6;
                    }
                }
            }
        }
    }

    // ---- block effects ----

    private double speedFactor() {
        BlockPhysics at = world.physics(fx(x), fx(y), fx(z));
        if (at.has(BlockPhysics.WATER)) return at.speedFactor;
        return at.speedFactor == 1f ? supporting(0.500001).speedFactor : at.speedFactor;
    }

    private double jumpFactor() {
        float at = world.physics(fx(x), fx(y), fx(z)).jumpFactor;
        return at == 1f ? supporting(0.500001).jumpFactor : at;
    }

    /**
     * The block {@code depth} below the feet, in the column of the supporting block: the block under the hitbox
     * closest to the player's center (vanilla mainSupportingBlockPos).
     */
    private BlockPhysics supporting(double depth) {
        int by = fx(y - depth);
        int cx = fx(x), cz = fx(z);
        if (!onGround || !world.physics(cx, fx(y - 1e-5), cz).empty()) return world.physics(cx, by, cz);
        double best = Double.POSITIVE_INFINITY;
        int sx = cx, sz = cz;
        for (int bx = fx(x - HALF_WIDTH); bx <= fx(x + HALF_WIDTH - EPS); bx++) {
            for (int bz = fx(z - HALF_WIDTH); bz <= fx(z + HALF_WIDTH - EPS); bz++) {
                if (world.physics(bx, fx(y - 1e-5), bz).empty()) continue;
                double d = sq(bx + 0.5 - x) + sq(bz + 0.5 - z);
                if (d < best) {
                    best = d;
                    sx = bx;
                    sz = bz;
                }
            }
        }
        return world.physics(sx, by, sz);
    }

    private boolean onClimbable() {
        return world.physics(fx(x), fx(y), fx(z)).has(BlockPhysics.CLIMBABLE);
    }

    /** How deep the hitbox (deflated by 0.001) sits in water: 0 if not at all. */
    private double fluidHeight() {
        double x0 = x - HALF_WIDTH + 0.001, x1 = x + HALF_WIDTH - 0.001, y0 = y + 0.001, y1 = y + HEIGHT - 0.001;
        double z0 = z - HALF_WIDTH + 0.001, z1 = z + HALF_WIDTH - 0.001;
        double h = 0;
        for (int bx = fx(x0); bx <= fx(x1); bx++) {
            for (int by = fx(y0); by <= fx(y1); by++) {
                for (int bz = fx(z0); bz <= fx(z1); bz++) {
                    if (!world.physics(bx, by, bz).has(BlockPhysics.WATER)) continue;
                    double top = by + WATER_HEIGHT;
                    if (top >= y0) h = Math.max(h, top - y0);
                }
            }
        }
        return h;
    }

    /** Box moved by (dx, dy, dz) overlaps no collision box and no liquid (Entity.isFree). */
    /** Player.canFallAtLeast: nothing to collide with in the step-high slab under the box moved by (dx, dz). */
    private boolean canFall(double dx, double dz) {
        double x0 = x - HALF_WIDTH + 1e-7 + dx, x1 = x + HALF_WIDTH - 1e-7 + dx;
        double z0 = z - HALF_WIDTH + 1e-7 + dz, z1 = z + HALF_WIDTH - 1e-7 + dz;
        double y0 = y - STEP_HEIGHT - 1e-7, y1 = y;
        for (int bx = fx(x0); bx <= fx(x1); bx++) {
            for (int by = fx(y0) - 1; by <= fx(y1); by++) {
                for (int bz = fx(z0); bz <= fx(z1); bz++) {
                    double[] b = world.physics(bx, by, bz).boxes;
                    for (int i = 0; i < b.length; i += 6) {
                        if (bx + b[i + 3] > x0 && bx + b[i] < x1 && by + b[i + 4] > y0 && by + b[i + 1] < y1
                            && bz + b[i + 5] > z0 && bz + b[i + 2] < z1) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private boolean free(double dx, double dy, double dz) {
        double x0 = x - HALF_WIDTH + dx, y0 = y + dy, z0 = z - HALF_WIDTH + dz;
        double x1 = x0 + 2 * HALF_WIDTH, y1 = y0 + HEIGHT, z1 = z0 + 2 * HALF_WIDTH;
        for (int bx = fx(x0); bx <= fx(x1 - EPS); bx++) {
            for (int by = fx(y0) - 1; by <= fx(y1 - EPS); by++) {
                for (int bz = fx(z0); bz <= fx(z1 - EPS); bz++) {
                    BlockPhysics p = world.physics(bx, by, bz);
                    if (p.has(BlockPhysics.WATER) || p.has(BlockPhysics.LAVA)) {
                        if (by >= fx(y0)) return false;
                        continue;
                    }
                    double[] b = p.boxes;
                    for (int i = 0; i < b.length; i += 6) {
                        if (bx + b[i + 3] > x0 && bx + b[i] < x1 && by + b[i + 4] > y0 && by + b[i + 1] < y1
                            && bz + b[i + 5] > z0 && bz + b[i + 2] < z1) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static int fx(double v) {
        return (int) Math.floor(v);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
