package dev.lazuli.core.physics;

import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;

import java.util.Arrays;

/**
 * Tick-accurate port of a player-driven boat (vanilla AbstractBoat.tick on the client): status from the blocks around
 * it (on land, in the air, in water), {@code floatBoat} (friction of the blocks under it, also slowing the spin; 0.9 in
 * the air; gravity 0.04), {@code controlBoat} (A/D add to the spin, W pushes 0.04 along the heading) and
 * {@code Entity.move} (block collisions with no step-up, slime bounces, speed factors).
 * <p>
 * On ice the friction is close to 1, so speed builds up to 0.04 / (1 - friction) blocks a tick (blue ice: 3.6) and
 * the spin keeps going: that's what makes ice boats drift.
 */
public final class BoatSim {
    public static final double HALF_WIDTH = 1.375 / 2, HEIGHT = 0.5625;
    private static final double GRAVITY = 0.04, PUSH = 0.04, TURN_PUSH = 0.005, BRAKE = 0.005;
    private static final double WATER_HEIGHT = 8.0 / 9.0;
    private static final double EPS = 1e-7;

    public enum Status { IN_WATER, UNDER_WATER, ON_LAND, IN_AIR }

    /** Bottom centre, velocity (blocks a tick), heading and spin (degrees, degrees a tick). */
    public double x, y, z, vx, vy, vz;
    public float yaw, spin;
    public Status status = Status.IN_AIR, oldStatus = Status.IN_AIR;
    public boolean horizontalCollision;
    private float landFriction;
    private double waterLevel;
    private final BlockView world;

    /** Scratch colliders, shared per thread: a search makes thousands of copies and steps one at a time. */
    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[][]{new double[6 * 64]});
    private int colCount;

    public BoatSim(BlockView world, double x, double y, double z, double vx, double vy, double vz, float yaw, float spin) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.yaw = yaw;
        this.spin = spin;
    }

    public BoatSim copy() {
        BoatSim s = new BoatSim(world, x, y, z, vx, vy, vz, yaw, spin);
        s.status = status;
        s.oldStatus = oldStatus;
        s.landFriction = landFriction;
        s.waterLevel = waterLevel;
        s.horizontalCollision = horizontalCollision;
        return s;
    }

    public void copyFrom(BoatSim o) {
        x = o.x;
        y = o.y;
        z = o.z;
        vx = o.vx;
        vy = o.vy;
        vz = o.vz;
        yaw = o.yaw;
        spin = o.spin;
        status = o.status;
        oldStatus = o.oldStatus;
        landFriction = o.landFriction;
        waterLevel = o.waterLevel;
        horizontalCollision = o.horizontalCollision;
    }

    public double speed() {
        return Math.sqrt(vx * vx + vz * vz);
    }

    /** One tick with these keys held. */
    public void step(boolean left, boolean right, boolean forward, boolean back) {
        oldStatus = status;
        status = status();
        floatBoat();
        // controlBoat
        double f = 0;
        if (left) spin -= 1;
        if (right) spin += 1;
        if (right != left && !forward && !back) f += TURN_PUSH;
        yaw += spin;
        if (forward) f += PUSH;
        if (back) f -= BRAKE;
        double rad = Math.toRadians(yaw);
        vx += Math.sin(-rad) * f;
        vz += Math.cos(rad) * f;
        move();
    }

    // ---- status

    private Status status() {
        if (underWater()) {
            waterLevel = y + HEIGHT;
            return Status.UNDER_WATER;
        }
        if (inWater()) return Status.IN_WATER;
        float f = groundFriction();
        if (f > 0) {
            landFriction = f;
            return Status.ON_LAND;
        }
        return Status.IN_AIR;
    }

    private boolean underWater() {
        double top = y + HEIGHT + 0.001;
        for (int bx = fx(x - HALF_WIDTH); bx < cx(x + HALF_WIDTH); bx++)
            for (int by = fx(y + HEIGHT); by < cx(top); by++)
                for (int bz = fx(z - HALF_WIDTH); bz < cx(z + HALF_WIDTH); bz++)
                    if (world.physics(bx, by, bz).has(BlockPhysics.WATER) && top < by + WATER_HEIGHT) return true;
        return false;
    }

    private boolean inWater() {
        boolean in = false;
        waterLevel = -Double.MAX_VALUE;
        for (int bx = fx(x - HALF_WIDTH); bx < cx(x + HALF_WIDTH); bx++)
            for (int by = fx(y); by < cx(y + 0.001); by++)
                for (int bz = fx(z - HALF_WIDTH); bz < cx(z + HALF_WIDTH); bz++) {
                    if (!world.physics(bx, by, bz).has(BlockPhysics.WATER)) continue;
                    double top = by + WATER_HEIGHT;
                    waterLevel = Math.max(waterLevel, top);
                    in |= y < top;
                }
        return in;
    }

    /** Average friction of the blocks the boat's bottom rests on (0: none). */
    private float groundFriction() {
        double x0 = x - HALF_WIDTH, x1 = x + HALF_WIDTH, z0 = z - HALF_WIDTH, z1 = z + HALF_WIDTH;
        double y0 = y - 0.001, y1 = y;
        float sum = 0;
        int n = 0;
        for (int bx = fx(x0) - 1; bx < cx(x1) + 1; bx++)
            for (int bz = fx(z0) - 1; bz < cx(z1) + 1; bz++)
                for (int by = fx(y0) - 1; by < cx(y1) + 1; by++) {
                    BlockPhysics p = world.physics(bx, by, bz);
                    double[] b = p.boxes;
                    for (int i = 0; i < b.length; i += 6) {
                        if (bx + b[i + 3] > x0 && bx + b[i] < x1 && by + b[i + 4] > y0 && by + b[i + 1] < y1
                            && bz + b[i + 5] > z0 && bz + b[i + 2] < z1) {
                            sum += p.friction;
                            n++;
                            break;
                        }
                    }
                }
        return n == 0 ? 0 : sum / n;
    }

    // ---- floatBoat

    private void floatBoat() {
        double gravity = -GRAVITY, buoy = 0;
        float f = 0.05f;
        if (oldStatus == Status.IN_AIR && status != Status.IN_AIR && status != Status.ON_LAND) {
            // dropped into water: vanilla puts it on the surface and stops the fall
            if (waterLevel > -Double.MAX_VALUE) y = Math.max(y, waterLevel - HEIGHT + 0.101);
            vy = 0;
            status = Status.IN_WATER;
            return;
        }
        switch (status) {
            case IN_WATER -> {
                buoy = (waterLevel - y) / HEIGHT;
                f = 0.9f;
            }
            case UNDER_WATER -> {
                buoy = 0.01f;
                f = 0.45f;
            }
            case IN_AIR -> f = 0.9f;
            case ON_LAND -> f = landFriction;
        }
        vx *= f;
        vy += gravity;
        vz *= f;
        spin *= f;
        if (buoy > 0) vy = (vy + buoy * (GRAVITY / 0.65)) * 0.75;
    }

    // ---- Entity.move

    private void move() {
        double dx = vx, dy = vy, dz = vz;
        double x0 = x - HALF_WIDTH, y0 = y, z0 = z - HALF_WIDTH, x1 = x + HALF_WIDTH, y1 = y + HEIGHT, z1 = z + HALF_WIDTH;
        gather(Math.min(x0, x0 + dx), Math.min(y0, y0 + dy), Math.min(z0, z0 + dz),
            Math.max(x1, x1 + dx), Math.max(y1, y1 + dy), Math.max(z1, z1 + dz));
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
        x += rx;
        y += ry;
        z += rz;
        boolean hitX = Math.abs(dx - rx) > 1e-5, hitZ = Math.abs(dz - rz) > 1e-5;
        horizontalCollision = hitX || hitZ;
        if (hitX) vx = 0;
        if (hitZ) vz = 0;
        if (dy != ry) {
            BlockPhysics on = world.physics(fx(x), fx(y - 0.2), fx(z));
            if (on.has(BlockPhysics.SLIME) && vy < 0) vy = -vy * 0.8;
            else vy = 0;
        }
        double sf = speedFactor();
        vx *= sf;
        vz *= sf;
    }

    private double speedFactor() {
        BlockPhysics at = world.physics(fx(x), fx(y), fx(z));
        if (at.has(BlockPhysics.WATER) || at.speedFactor != 1f) return at.speedFactor;
        return world.physics(fx(x), fx(y - 0.500001), fx(z)).speedFactor;
    }

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

    private double[] col;

    private void gather(double x0, double y0, double z0, double x1, double y1, double z1) {
        double[][] holder = SCRATCH.get();
        col = holder[0];
        colCount = 0;
        for (int bx = fx(x0 - EPS); bx <= fx(x1 + EPS); bx++)
            for (int bz = fx(z0 - EPS); bz <= fx(z1 + EPS); bz++)
                for (int by = fx(y0 - EPS) - 1; by <= fx(y1 + EPS); by++) {
                    double[] b = world.physics(bx, by, bz).boxes;
                    for (int i = 0; i < b.length; i += 6) {
                        double minX = bx + b[i], minY = by + b[i + 1], minZ = bz + b[i + 2];
                        double maxX = bx + b[i + 3], maxY = by + b[i + 4], maxZ = bz + b[i + 5];
                        if (maxX <= x0 || minX >= x1 || maxY <= y0 || minY >= y1 || maxZ <= z0 || minZ >= z1) continue;
                        if ((colCount + 1) * 6 > col.length) col = holder[0] = Arrays.copyOf(col, col.length * 2);
                        int o = colCount * 6;
                        col[o] = minX;
                        col[o + 1] = minY;
                        col[o + 2] = minZ;
                        col[o + 3] = maxX;
                        col[o + 4] = maxY;
                        col[o + 5] = maxZ;
                        colCount++;
                    }
                }
    }

    private static double pick(int axis, double x, double y, double z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }

    private static int fx(double v) {
        return (int) Math.floor(v);
    }

    private static int cx(double v) {
        return (int) Math.ceil(v);
    }
}
