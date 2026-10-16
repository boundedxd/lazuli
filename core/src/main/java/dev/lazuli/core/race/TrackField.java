package dev.lazuli.core.race;

import dev.lazuli.core.util.LongMap;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * A track worked out from the blocks: every cell a boat can sit in (on something solid, or on water) reachable from
 * the start, linked to the cells beside it on the same level and to where it lands dropping off an edge (boats can't
 * climb, not even a slab). The distance still to go is a shortest-path field back from the finish, so it follows the
 * track round every bend and down every drop. Cells against a wall cost a little more, keeping the line off them.
 * <p>
 * Point to point: the finish is the cells around the goal. A lap: the finish line runs across the track through the
 * start, square to the heading; no link may cross it, and the finish is the cells just behind it, so the start (just
 * in front) is a whole lap from the finish. Crossing it forward counts a lap.
 */
public final class TrackField implements RaceLine {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    /** Deepest drop followed. */
    private static final int MAX_DROP = 32;
    /** How far across the track the finish line reaches each way. */
    private static final double LINE_HALF = 40;
    /** Farthest gap over the void looked across for a landing (a fast boat flies far, dropping as it goes). */
    private static final int MAX_JUMP = 24;
    /** Extra cost per block of a cell with a wall beside it. */
    private static final double WALL = 0.6;

    private final BlockView w;
    private final LongMap<Integer> index = new LongMap<>(4096);
    private long[] cells = new long[1024];
    private int[][] next;
    private double[] dist;
    private int n;
    /** Jumps found (take-off cell, landing cell), and the void columns they fly over: {to go, top y, bottom y}. */
    private final java.util.List<long[]> jumps = new java.util.ArrayList<>();
    private final LongMap<double[]> air = new LongMap<>(256);
    /** Lap course: the line through (lx, lz) square to (hx, hz); length of a lap. */
    private final boolean lap;
    private double lx, lz, hx, hz, lapLength;
    /** How far the finish line reaches to each side of the start (to the walls, or the edge, on each side). */
    private double lineLeft = LINE_HALF, lineRight = LINE_HALF;

    /** Water only (a crossing by boat in the open): no land cells, no jumps, and the boat sits down in the water. */
    private boolean water;

    private TrackField(BlockView w, boolean lap) {
        this.w = w;
        this.lap = lap;
    }

    /**
     * Over water only, from the cell above the water block (x, y - 1, z) to within {@code radius} of (gx, gz) at any
     * height: a boat crossing a lake or river, where the shore at the water's own level is no use (it can't climb out).
     */
    public static TrackField water(BlockView w, int x, int y, int z, int gx, int gz, double radius, int maxCells) {
        TrackField f = new TrackField(w, false);
        f.water = true;
        if (!f.isCell(x, y, z)) return null;
        f.explore(x, y, z, maxCells);
        boolean any = false;
        double[] seed = new double[f.n];
        Arrays.fill(seed, Double.NaN);
        for (int i = 0; i < f.n; i++) {
            long c = f.cells[i];
            if (Math.hypot(Pos.x(c) - gx, Pos.z(c) - gz) <= radius) {
                seed[i] = 0;
                any = true;
            }
        }
        if (!any) return null;
        f.field(seed);
        return f.dist[f.index.get(Pos.pack(x, y, z))] < Double.MAX_VALUE ? f : null;
    }

    /**
     * A cell of this track. On water only, for a water crossing: and not in a one block wide channel (shore on both
     * sides), which a boat 1.375 wide can't get through; two wide it can, its middle on the line between them.
     */
    private boolean isCell(int x, int y, int z) {
        if (!water) return cell(w, x, y, z);
        return afloat(x, y, z) && (afloat(x - 1, y, z) || afloat(x + 1, y, z)) && (afloat(x, y, z - 1) || afloat(x, y, z + 1));
    }

    private boolean afloat(int x, int y, int z) {
        return cell(w, x, y, z) && w.physics(x, y - 1, z).has(BlockPhysics.WATER);
    }

    /**
     * A course from (x, y, z) to within {@code radius} of the goal (gy {@link Integer#MIN_VALUE}: at any height); null
     * if the goal can't be reached.
     */
    public static TrackField toGoal(BlockView w, int x, int y, int z, int gx, int gy, int gz, double radius, int maxCells) {
        TrackField f = new TrackField(w, false);
        f.explore(x, y, z, maxCells);
        boolean any = false;
        double[] seed = new double[f.n];
        Arrays.fill(seed, Double.NaN);
        for (int i = 0; i < f.n; i++) {
            long c = f.cells[i];
            double dy = gy == Integer.MIN_VALUE ? 0 : Pos.y(c) - gy;
            double d = Math.sqrt(sq(Pos.x(c) - gx) + sq(dy) * 0.25 + sq(Pos.z(c) - gz));
            if (d <= radius) {
                seed[i] = 0;
                any = true;
            }
        }
        if (!any) return null;
        f.field(seed);
        return f.dist[f.index.get(Pos.pack(x, y, z))] < Double.MAX_VALUE ? f : null;
    }

    /** A lap course starting at (x, y, z) heading {@code yaw}; null if no way round back to the start. */
    public static TrackField lap(BlockView w, int x, int y, int z, float yaw, int maxCells) {
        TrackField f = new TrackField(w, true);
        f.lx = x + 0.5;
        f.lz = z + 0.5;
        f.hx = -Math.sin(Math.toRadians(yaw));
        f.hz = Math.cos(Math.toRadians(yaw));
        // the line only spans this stretch of track: out to the wall (or edge) each side, not across other parts of it
        f.lineLeft = f.reach(x, y, z, -1);
        f.lineRight = f.reach(x, y, z, 1);
        f.explore(x, y, z, maxCells);
        double[] seed = new double[f.n];
        Arrays.fill(seed, Double.NaN);
        boolean any = false;
        for (int i = 0; i < f.n; i++) {
            long c = f.cells[i];
            double s = f.side(Pos.x(c) + 0.5, Pos.z(c) + 0.5), l = f.lateral(Pos.x(c) + 0.5, Pos.z(c) + 0.5);
            if (s < 0 && s > -2 && f.onLine(l) && Pos.y(c) == y) {
                seed[i] = 0;
                any = true;
            }
        }
        if (!any) return null;
        f.field(seed);
        Integer si = f.index.get(Pos.pack(x, y, z));
        if (si == null || f.dist[si] >= Double.MAX_VALUE) return null;
        f.lapLength = f.dist[si];
        return f;
    }

    private int laps = 1;

    /** Laps to finish (lap courses). */
    public TrackField laps(int n) {
        this.laps = Math.max(1, n);
        return this;
    }

    @Override
    public boolean finished(Cursor c, double toGo) {
        return lap ? c.laps >= laps : toGo <= 0.5;
    }

    public double lapLength() {
        return lapLength;
    }

    public int cellCount() {
        return n;
    }

    // ---- cells and links

    /** A boat fits at (x, y, z): resting on something (or floating on water), with room for it. */
    static boolean cell(BlockView w, int x, int y, int z) {
        BlockPhysics at = w.physics(x, y, z);
        if (!at.empty() && at.top > 0.01 || at.has(BlockPhysics.WATER) || at.has(BlockPhysics.LAVA)) return false;
        BlockPhysics below = w.physics(x, y - 1, z);
        return below.top >= 0.5 || below.has(BlockPhysics.WATER);
    }

    private static boolean open(BlockView w, int x, int y, int z) {
        BlockPhysics at = w.physics(x, y, z);
        return (at.empty() || at.top <= 0.01) && !at.has(BlockPhysics.LAVA);
    }

    private void explore(int x, int y, int z, int maxCells) {
        ArrayDeque<Long> q = new ArrayDeque<>();
        long s = Pos.pack(x, y, z);
        add(s);
        q.add(s);
        int[][] links = new int[1024][];
        while (!q.isEmpty() && n < maxCells) {
            long c = q.poll();
            int ci = index.get(c);
            int[] out = new int[16];
            int k = 0;
            int cx = Pos.x(c), cy = Pos.y(c), cz = Pos.z(c);
            boolean edge = false;
            for (int[] d : DIRS) {
                int nx = cx + d[0], nz = cz + d[1];
                if (d[0] != 0 && d[1] != 0 && (!open(w, cx + d[0], cy, cz) || !open(w, cx, cy, cz + d[1]))) continue;
                if (!open(w, nx, cy, nz)) continue;
                long m = Long.MIN_VALUE;
                if (isCell(nx, cy, nz)) m = Pos.pack(nx, cy, nz);
                else {
                    for (int dy = 1; dy <= MAX_DROP; dy++) {
                        if (!open(w, nx, cy - dy, nz)) break;
                        if (isCell(nx, cy - dy, nz)) {
                            m = Pos.pack(nx, cy - dy, nz);
                            break;
                        }
                    }
                }
                if (m == Long.MIN_VALUE) {
                    edge |= !water; // nothing below there: the void, worth a look across (on water: just the shore)
                    continue;
                }
                if (lap && crosses(cx + 0.5, cz + 0.5, Pos.x(m) + 0.5, Pos.z(m) + 0.5) != 0) continue;
                Integer mi = index.get(m);
                if (mi == null) {
                    mi = add(m);
                    q.add(m);
                }
                if (k == out.length) out = Arrays.copyOf(out, k * 2);
                out[k++] = mi;
            }
            if (edge) {
                // across the gap: the first track on the far side, level or lower, in each of 16 directions
                for (int a = 0; a < 16; a++) {
                    double ang = a * Math.PI / 8, ux = Math.cos(ang), uz = Math.sin(ang);
                    long m = jump(cx, cy, cz, ux, uz);
                    if (m == Long.MIN_VALUE) continue;
                    jumps.add(new long[]{c, m});
                    if (lap && crosses(cx + 0.5, cz + 0.5, Pos.x(m) + 0.5, Pos.z(m) + 0.5) != 0) continue;
                    Integer mi = index.get(m);
                    if (mi == null) {
                        mi = add(m);
                        q.add(m);
                    }
                    if (k == out.length) out = Arrays.copyOf(out, k * 2);
                    out[k++] = mi;
                }
            }
            if (ci >= links.length) links = Arrays.copyOf(links, Math.max(ci + 1, links.length * 2));
            links[ci] = Arrays.copyOf(out, k);
        }
        next = new int[n][];
        for (int i = 0; i < n; i++) next[i] = i < links.length && links[i] != null ? links[i] : new int[0];
    }

    /**
     * Flying off (x, y, z) along (ux, uz): the first cell beyond a stretch of void, at most {@link #MAX_DROP} lower;
     * MIN_VALUE if a wall's in the way, there's no void to cross, or nothing to land on in range.
     */
    private long jump(int x, int y, int z, double ux, double uz) {
        boolean overVoid = false;
        int lastX = x, lastZ = z;
        for (double d = 1; d <= MAX_JUMP; d += 0.5) {
            int nx = (int) Math.floor(x + 0.5 + ux * d), nz = (int) Math.floor(z + 0.5 + uz * d);
            if (nx == lastX && nz == lastZ) continue;
            lastX = nx;
            lastZ = nz;
            if (!open(w, nx, y, nz)) return Long.MIN_VALUE;
            for (int dy = 0; dy <= MAX_DROP; dy++) {
                if (!open(w, nx, y - dy, nz) && dy > 0) break;
                if (isCell(nx, y - dy, nz)) {
                    return overVoid && d >= 2 ? Pos.pack(nx, y - dy, nz) : Long.MIN_VALUE;
                }
            }
            overVoid = true;
        }
        return Long.MIN_VALUE;
    }

    private int add(long c) {
        if (n == cells.length) cells = Arrays.copyOf(cells, n * 2);
        cells[n] = c;
        index.put(c, n);
        return n++;
    }

    /** Dijkstra back from the seeded cells over the links reversed. */
    private void field(double[] seed) {
        dist = new double[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(seed[i])) continue;
            dist[i] = seed[i];
            open.add(new double[]{seed[i], i});
        }
        dijkstra(open);
        flights();
    }

    private void dijkstra(PriorityQueue<double[]> open) {
        int[][] prev = reverse();
        while (!open.isEmpty()) {
            double[] top = open.poll();
            int v = (int) top[1];
            if (top[0] > dist[v]) continue;
            for (int u : prev[v]) {
                double d = dist[v] + linkCost(cells[u], cells[v]);
                if (d < dist[u]) {
                    dist[u] = d;
                    open.add(new double[]{d, u});
                }
            }
        }
    }

    /** The void columns each jump flies over (and one either side), valued as the landing plus the way left to it. */
    private void flights() {
        for (long[] j : jumps) {
            Integer mi = index.get(j[1]);
            if (mi == null || dist[mi] >= Double.MAX_VALUE) continue;
            double ax = Pos.x(j[0]) + 0.5, az = Pos.z(j[0]) + 0.5, bx = Pos.x(j[1]) + 0.5, bz = Pos.z(j[1]) + 0.5;
            double len = Math.hypot(bx - ax, bz - az), ux = (bx - ax) / len, uz = (bz - az) / len;
            for (double d = 0.5; d < len; d += 0.5) {
                for (int side = -1; side <= 1; side++) {
                    double px = ax + ux * d - uz * side, pz = az + uz * d + ux * side;
                    long key = Pos.pack((int) Math.floor(px), 0, (int) Math.floor(pz));
                    double v = dist[mi] + Math.hypot(bx - px, bz - pz) + Math.abs(side) * 0.5;
                    double[] e = air.get(key);
                    if (e == null || v < e[0]) air.put(key, new double[]{v, Pos.y(j[0]), Pos.y(j[1])});
                }
            }
        }
    }

    private int[][] reverse() {
        int[] count = new int[n];
        for (int u = 0; u < n; u++) for (int v : next[u]) count[v]++;
        int[][] prev = new int[n][];
        for (int v = 0; v < n; v++) prev[v] = new int[count[v]];
        Arrays.fill(count, 0);
        for (int u = 0; u < n; u++) for (int v : next[u]) prev[v][count[v]++] = u;
        return prev;
    }

    private double linkCost(long u, long v) {
        double d = Math.hypot(Pos.x(v) - Pos.x(u), Pos.z(v) - Pos.z(u));
        return d * (1 + (walled(v) ? WALL : 0));
    }

    private boolean walled(long c) {
        int x = Pos.x(c), y = Pos.y(c), z = Pos.z(c);
        for (int[] d : DIRS) if (water ? !isCell(x + d[0], y, z + d[1]) : !open(w, x + d[0], y, z + d[1])) return true;
        return false;
    }

    // ---- the finish line

    /** Blocks of track from the start sideways ({@code dir} -1 or 1, along the line) before a wall or the edge. */
    private double reach(int x, int y, int z, int dir) {
        double px = -hz * dir, pz = hx * dir;
        for (double d = 0.5; d <= LINE_HALF; d += 0.5) {
            int cx = (int) Math.floor(lx + px * d), cz = (int) Math.floor(lz + pz * d);
            if (!isCell(cx, y, cz)) return d + 0.5;
        }
        return LINE_HALF;
    }

    private boolean onLine(double l) {
        return l >= 0 ? l <= lineRight : -l <= lineLeft;
    }

    private double side(double x, double z) {
        return (x - lx) * hx + (z - lz) * hz;
    }

    private double lateral(double x, double z) {
        return (x - lx) * -hz + (z - lz) * hx;
    }

    /** +1 crossing the line forward from (ax, az) to (bx, bz), -1 backward, 0 not at all. */
    int crosses(double ax, double az, double bx, double bz) {
        double sa = side(ax, az), sb = side(bx, bz);
        if (sa < 0 == sb < 0) return 0;
        double t = sa / (sa - sb);
        double l = lateral(ax + (bx - ax) * t, az + (bz - az) * t);
        if (!onLine(l)) return 0;
        return sa < 0 ? 1 : -1;
    }

    // ---- RaceLine

    @Override
    public Cursor start(double x, double y, double z) {
        Cursor c = new Cursor();
        c.lastX = x;
        c.lastZ = z;
        return c;
    }

    @Override
    public double toGo(Cursor c, double x, double y, double z) {
        if (lap && !Double.isNaN(c.lastX)) {
            int k = crosses(c.lastX, c.lastZ, x, z);
            if (k > 0) {
                c.laps++;
                c.offset += lapLength;
            } else if (k < 0) {
                c.laps--;
                c.offset -= lapLength;
            }
        }
        c.lastX = x;
        c.lastZ = z;
        double d = at(x, y, z);
        return Double.isNaN(d) ? d : d - c.offset;
    }

    /** The field at a point: blended from the cells around it on its level (or where it would land, in the air). */
    private double at(double x, double y, double z) {
        // afloat, a boat's bottom is about halfway down the water block: its cell is the one above that
        int by = (int) Math.floor(water ? y + 0.9 : y + 0.01);
        int fx = (int) Math.floor(x), fz = (int) Math.floor(z);
        if (water && !under(x, by, z)) return Double.NaN;
        if (!under(x, by, z)) {
            // in the air (or its middle just off the edge): the level it'll come down on, else nowhere
            int land = Integer.MIN_VALUE;
            for (int dy = 1; dy <= MAX_DROP + 2 && land == Integer.MIN_VALUE; dy++) if (under(x, by - dy, z)) land = by - dy;
            if (land == Integer.MIN_VALUE) {
                // mid-jump over the void: on the way to the landing while still above it, else lost
                double[] e = air.get(Pos.pack(fx, 0, fz));
                return e != null && by <= e[1] + 2 && y >= e[2] - 0.5 ? e[0] : Double.NaN;
            }
            by = land;
        }
        double gx = x - 0.5, gz = z - 0.5;
        int x0 = (int) Math.floor(gx), z0 = (int) Math.floor(gz);
        double tx = gx - x0, tz = gz - z0;
        double sum = 0, wsum = 0, nearest = Double.NaN;
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                Integer id = index.get(Pos.pack(x0 + i, by, z0 + j));
                if (id == null || dist[id] >= Double.MAX_VALUE) continue;
                double wgt = (i == 0 ? 1 - tx : tx) * (j == 0 ? 1 - tz : tz);
                sum += dist[id] * wgt;
                wsum += wgt;
            }
        }
        if (wsum > 0.05) return sum / wsum;
        // only the boat's edge is over track: the nearest of the cells it covers
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                Integer id = index.get(Pos.pack((int) Math.floor(x + i * 0.68), by, (int) Math.floor(z + j * 0.68)));
                if (id != null && dist[id] < Double.MAX_VALUE && (Double.isNaN(nearest) || dist[id] < nearest)) nearest = dist[id];
            }
        }
        return Double.isNaN(nearest) ? nearest : nearest + 1;
    }

    /** Some track cell at level y under the boat (its middle or its edges). */
    private boolean under(double x, int y, double z) {
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++)
                if (index.containsKey(Pos.pack((int) Math.floor(x + i * 0.68), y, (int) Math.floor(z + j * 0.68)))) return true;
        return false;
    }

    private static double sq(double d) {
        return d * d;
    }
}
