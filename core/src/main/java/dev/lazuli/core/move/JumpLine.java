package dev.lazuli.core.move;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

/**
 * What a jump's flight line crosses: whether the body fits all along it, where the gap is and how low the ceiling
 * gets. A straight line from take-off to landing, or (a neo) a bent one round the end of a wall.
 */
final class JumpLine {
    private static final double PLAYER_HALF_WIDTH = 0.3, STEP = 0.25;
    /** Farthest a neo reaches (center to center), and how far out to the side its bend may go. */
    static final double NEO_RANGE = 4.6;
    private static final double[] BENDS = {1.0, -1.0, 1.5, -1.5, 2.0, -2.0};

    boolean blocked;
    /** Distance along the line of the first and last gap columns, -1 if none. */
    double firstGap = -1, lastGap = -1;
    /** Lowest ceiling over the line, as height above the take-off's feet. */
    double ceiling = Double.POSITIVE_INFINITY;
    double length;

    private final BlockView w;
    private final int ux, uy, uz, vx, vy, vz, top;

    private JumpLine(BlockView w, long u, long v) {
        this.w = w;
        ux = Pos.x(u);
        uy = Pos.y(u);
        uz = Pos.z(u);
        vx = Pos.x(v);
        vy = Pos.y(v);
        vz = Pos.z(v);
        // room for the body along the way (head-hitters included: the physics decides how far a capped jump goes)
        top = Math.max(uy, vy) + 1;
    }

    /** The straight line between the two blocks' centers. */
    static JumpLine straight(BlockView w, long u, long v) {
        JumpLine l = new JumpLine(w, u, v);
        l.segment(l.ux + 0.5, l.uz + 0.5, l.vx + 0.5, l.vz + 0.5);
        return l;
    }

    /**
     * A neo: a line bent at a point beside the midpoint, clear of what blocks the straight one and over a gap; null if
     * there is none.
     */
    static JumpLine bent(BlockView w, long u, long v) {
        double ax = Pos.x(u) + 0.5, az = Pos.z(u) + 0.5, bx = Pos.x(v) + 0.5, bz = Pos.z(v) + 0.5;
        double d = Math.hypot(bx - ax, bz - az);
        double nx = -(bz - az) / d, nz = (bx - ax) / d;
        for (double k : BENDS) {
            double mx = (ax + bx) / 2 + nx * k, mz = (az + bz) / 2 + nz * k;
            JumpLine l = new JumpLine(w, u, v);
            l.segment(ax, az, mx, mz);
            if (l.blocked) continue;
            l.segment(mx, mz, bx, bz);
            if (!l.blocked && l.firstGap >= 0) return l;
        }
        return null;
    }

    /** Extends the line by a segment, checking the cells it and the body's sides cross. */
    private void segment(double x0, double z0, double x1, double z1) {
        double d = Math.hypot(x1 - x0, z1 - z0);
        if (d < 1e-9) return;
        double nx = (x1 - x0) / d, nz = (z1 - z0) / d, px = -nz * PLAYER_HALF_WIDTH, pz = nx * PLAYER_HALF_WIDTH;
        int steps = (int) Math.ceil(d / STEP);
        long prevCell = Long.MIN_VALUE;
        for (int i = 1; i < steps; i++) {
            double t = i * STEP, cx = x0 + nx * t, cz = z0 + nz * t;
            int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
            boolean ends = (bx == ux && bz == uz) || (bx == vx && bz == vz);
            boolean gap = gapColumn(w, bx, Math.min(uy, vy), bz);
            if (gap && !ends) {
                if (firstGap < 0) firstGap = length + t;
                lastGap = length + t;
            }
            long cell = Pos.pack(bx, 0, bz);
            if (!ends && cell != prevCell) {
                int low = gap ? Math.min(uy, vy) : uy;
                for (int y = low; y <= top; y++) {
                    if (!openCell(w, bx, y, bz)) {
                        blocked = true;
                        return;
                    }
                }
                if (!openCell(w, bx, top + 1, bz)) ceiling = Math.min(ceiling, top + 1 - uy - Standing.BODY);
            }
            prevCell = cell;
            for (int side = -1; side <= 1; side += 2) {
                int sx = (int) Math.floor(cx + px * side), sz = (int) Math.floor(cz + pz * side);
                if ((sx == ux && sz == uz) || (sx == vx && sz == vz)) continue;
                for (int y = uy; y <= top - 1; y++) {
                    if (!openCell(w, sx, y, sz)) {
                        blocked = true;
                        return;
                    }
                }
            }
        }
        length += d;
    }

    /** A cell the jump arc can pass through: no collision, no liquid (unknown counts as open). */
    static boolean openCell(BlockView w, int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        if (k == BlockKind.UNKNOWN) return true;
        if (k == BlockKind.WATER) return false;
        return w.physics(x, y, z).empty();
    }

    /**
     * A column a jump at this level has to clear: nothing to stand on at it or one below. Shallower dips are walked
     * (down and back up), and the input planner hops them on its own when that's faster, so they need no jump edges;
     * without this, hills would sprout thousands of jump edges the physics then has to reject.
     */
    static boolean gapColumn(BlockView w, int x, int y, int z) {
        if (Standing.hasSupport(w, x, y, z)) return false;
        return !Standing.hasSupport(w, x, y - 1, z) || hazard(w, x, y - 1, z) || hazard(w, x, y - 2, z);
    }

    /** Lava, fire, water...: a dip holding one is never walked through. */
    private static boolean hazard(BlockView w, int x, int y, int z) {
        BlockKind k = w.get(x, y, z);
        return k == BlockKind.DANGER || k == BlockKind.WATER;
    }
}
