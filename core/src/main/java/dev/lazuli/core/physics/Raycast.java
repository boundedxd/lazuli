package dev.lazuli.core.physics;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;

/**
 * What the crosshair is on: walks the cells along the view ray and hits the first collision box, the way the game
 * picks the block to break or click. Blocks outside the cache count as full, so nothing is aimed through them.
 */
public final class Raycast {
    /** Faces in the game's {@code Direction} order. */
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    /** Survival block interaction range. */
    public static final double REACH = 4.5;

    /** The block hit, the face the ray came in through, and where on it. */
    public record Hit(int x, int y, int z, int face, double hx, double hy, double hz) {}

    private Raycast() {}

    /** View direction for Minecraft yaw/pitch degrees. */
    public static double[] direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /** Yaw and pitch that look from the eye at a point. */
    public static float[] angles(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    public static Hit cast(BlockView w, double ex, double ey, double ez, float yaw, float pitch, double range) {
        double[] d = direction(yaw, pitch);
        return cast(w, ex, ey, ez, d[0], d[1], d[2], range);
    }

    /** First box hit along the (unit) direction within {@code range}, or null. */
    public static Hit cast(BlockView w, double ex, double ey, double ez, double dx, double dy, double dz, double range) {
        int x = (int) Math.floor(ex), y = (int) Math.floor(ey), z = (int) Math.floor(ez);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tdx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tdy = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tdz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : ((dx > 0 ? x + 1 - ex : ex - x) * tdx);
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : ((dy > 0 ? y + 1 - ey : ey - y) * tdy);
        double tz = dz == 0 ? Double.POSITIVE_INFINITY : ((dz > 0 ? z + 1 - ez : ez - z) * tdz);
        for (int i = 0; i < 64; i++) {
            Hit h = inCell(w, x, y, z, ex, ey, ez, dx, dy, dz, range);
            if (h != null) return h;
            double t = Math.min(tx, Math.min(ty, tz));
            if (t > range) return null;
            if (tx == t) {
                x += sx;
                tx += tdx;
            } else if (ty == t) {
                y += sy;
                ty += tdy;
            } else {
                z += sz;
                tz += tdz;
            }
        }
        return null;
    }

    private static Hit inCell(BlockView w, int x, int y, int z, double ex, double ey, double ez, double dx, double dy,
                              double dz, double range) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.AIR || k == BlockKind.WATER) return null;
        double[] b = k == BlockKind.UNKNOWN ? BlockPhysics.FULL.boxes : w.physics(x, y, z).boxes;
        Hit best = null;
        double bestT = range;
        for (int i = 0; i + 5 < b.length; i += 6) {
            double t0 = 0, t1 = bestT;
            int face = -1;
            double[] lo = {x + b[i], y + b[i + 1], z + b[i + 2]}, hi = {x + b[i + 3], y + b[i + 4], z + b[i + 5]};
            double[] o = {ex, ey, ez}, d = {dx, dy, dz};
            boolean miss = false;
            for (int a = 0; a < 3 && !miss; a++) {
                if (d[a] == 0) {
                    if (o[a] < lo[a] || o[a] > hi[a]) miss = true;
                    continue;
                }
                double ta = (lo[a] - o[a]) / d[a], tb = (hi[a] - o[a]) / d[a];
                int fa = a == 0 ? WEST : a == 1 ? DOWN : NORTH; // entering through the low side
                int fb = a == 0 ? EAST : a == 1 ? UP : SOUTH;
                if (ta > tb) {
                    double s = ta;
                    ta = tb;
                    tb = s;
                    int f = fa;
                    fa = fb;
                    fb = f;
                }
                if (ta > t0) {
                    t0 = ta;
                    face = fa;
                }
                if (tb < t1) t1 = tb;
                if (t0 > t1) miss = true;
            }
            if (miss || face < 0) continue; // a ray starting inside a box doesn't hit it
            bestT = t0;
            best = new Hit(x, y, z, face, ex + dx * t0, ey + dy * t0, ez + dz * t0);
        }
        return best;
    }

    /** The face of block {@code a} that touches block {@code b} next to it. */
    public static int faceToward(int ax, int ay, int az, int bx, int by, int bz) {
        if (by > ay) return UP;
        if (by < ay) return DOWN;
        if (bx > ax) return EAST;
        if (bx < ax) return WEST;
        return bz > az ? SOUTH : NORTH;
    }
}
