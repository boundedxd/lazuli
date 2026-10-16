package dev.lazuli.core.race;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A track given as a line driven once (or typed in): where a point is along it is its projection onto the nearest
 * stretch near where it was last, so slime bounces, jumps and stacked levels the blocks alone don't explain are just
 * followed. Cutting a corner projects further along, which the racer takes as progress: the line is a guide, not
 * rails. A line that ends where it began is a lap.
 */
public final class RecordedLine implements RaceLine {
    /** Ends this close together make a loop. */
    private static final double LOOP = 6;
    /** Stretches looked at behind and ahead of the last one. */
    private static final int BACK = 8, AHEAD = 60;
    /** Further than this from the line counts as off the track. */
    private static final double OFF = 12;

    private final double[] xs, ys, zs, s;
    private final boolean loop;
    private final double length;

    /** A line whose ends meet up (within a few blocks) makes a lap. */
    public RecordedLine(List<double[]> points) {
        this(points, null);
    }

    /** @param loop whether it's a lap (null: when its ends meet up) */
    public RecordedLine(List<double[]> points, Boolean loop) {
        List<double[]> pts = thin(points);
        int n = pts.size();
        xs = new double[n];
        ys = new double[n];
        zs = new double[n];
        s = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = pts.get(i)[0];
            ys[i] = pts.get(i)[1];
            zs[i] = pts.get(i)[2];
            if (i > 0) s[i] = s[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        }
        this.loop = loop != null ? loop : n > 10 && Math.hypot(xs[0] - xs[n - 1], zs[0] - zs[n - 1]) < LOOP;
        length = this.loop ? s[n - 1] + Math.hypot(xs[0] - xs[n - 1], zs[0] - zs[n - 1]) : s[n - 1];
    }

    /** Points at least half a block apart (a recording standing still adds nothing). */
    private static List<double[]> thin(List<double[]> in) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : in) {
            if (out.isEmpty() || Math.hypot(p[0] - out.get(out.size() - 1)[0], p[2] - out.get(out.size() - 1)[2]) >= 0.5) out.add(p);
        }
        if (out.size() < 2) throw new IllegalArgumentException("a line needs two points at least");
        return out;
    }

    private int laps = 1;

    /** Laps to finish (a loop). */
    public RecordedLine laps(int n) {
        this.laps = Math.max(1, n);
        return this;
    }

    @Override
    public boolean finished(Cursor c, double toGo) {
        return loop ? c.laps >= laps : toGo <= 1;
    }

    public boolean loop() {
        return loop;
    }

    public double length() {
        return length;
    }

    public int size() {
        return xs.length;
    }

    public double[] point(int i) {
        return new double[]{xs[i], ys[i], zs[i]};
    }

    @Override
    public Cursor start(double x, double y, double z) {
        Cursor c = new Cursor();
        // nearest point of the whole line
        double best = Double.MAX_VALUE;
        for (int i = 0; i + 1 < xs.length; i++) {
            double d = segDist(i, x, y, z);
            if (d < best) {
                best = d;
                c.seg = i;
            }
        }
        return c;
    }

    @Override
    public double toGo(Cursor c, double x, double y, double z) {
        int n = xs.length;
        int segs = loop ? n : n - 1;
        int from = c.seg < 0 ? 0 : c.seg;
        double best = Double.MAX_VALUE;
        int bestSeg = from, bestK = 0;
        for (int k = -BACK; k <= AHEAD; k++) {
            int i = from + k;
            if (!loop && (i < 0 || i >= segs)) continue;
            int wrapped = Math.floorMod(i, segs);
            double d = segDist(wrapped, x, y, z);
            if (d < best) {
                best = d;
                bestSeg = wrapped;
                bestK = k;
            }
        }
        if (best > OFF) return Double.NaN;
        // went past the end of a loop (or back over it): a lap
        if (loop && from + bestK >= segs) {
            c.laps++;
            c.offset += length;
        } else if (loop && from + bestK < 0) {
            c.laps--;
            c.offset -= length;
        }
        c.seg = bestSeg;
        return length - along(bestSeg, x, z) - c.offset;
    }

    /** Distance along the line of the projection onto stretch i. */
    private double along(int i, double x, double z) {
        int j = (i + 1) % xs.length;
        double ax = xs[i], az = zs[i], bx = xs[j], bz = zs[j];
        double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
        double segLen = Math.sqrt(len2);
        return s[i] + t * segLen;
    }

    /** Distance from the point to stretch i (height counted, so a level above or below isn't mistaken for it). */
    private double segDist(int i, double x, double y, double z) {
        int j = (i + 1) % xs.length;
        double ax = xs[i], az = zs[i], bx = xs[j], bz = zs[j];
        double dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
        double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len2));
        double px = ax + t * dx, pz = az + t * dz, py = ys[i] + t * (ys[j] - ys[i]);
        double dy = Math.max(0, Math.abs(y - py) - 1.5) * 2;
        return Math.sqrt(sq(x - px) + sq(z - pz) + dy * dy);
    }

    // ---- files: one "x y z" per line

    public void save(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) lines.add(String.format(Locale.ROOT, "%.3f %.3f %.3f", xs[i], ys[i], zs[i]));
        Files.createDirectories(file.getParent());
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    public static RecordedLine load(Path file) throws IOException {
        List<double[]> pts = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] p = line.trim().split("\\s+");
            if (p.length != 3) continue;
            try {
                pts.add(new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])});
            } catch (NumberFormatException ignored) {
                // skip a broken line
            }
        }
        return new RecordedLine(pts);
    }

    private static double sq(double d) {
        return d * d;
    }
}
