package dev.lazuli.core.control;

/**
 * Aiming a bow: vanilla arrows move by their velocity, then lose 1% of it and 0.05 of upward speed each tick, and leave
 * a fully drawn bow (20 ticks) at 3 blocks a tick.
 */
public final class Archery {
    /** Ticks for a full draw. */
    public static final int FULL_DRAW = 20;
    public static final double SPEED = 3.0;
    private static final double DRAG = 0.99, GRAVITY = 0.05;
    private static final int MAX_TICKS = 100;

    private Archery() {}

    /** A shot: the view pitch to fire at (degrees, negative is up, like the game's) and ticks until it gets there. */
    public record Shot(float pitch, int ticks) {}

    /** Arrow speed from a bow drawn {@code ticks} ticks (vanilla's power curve, times 3). */
    public static double speed(int ticks) {
        double f = ticks / 20.0;
        f = (f * f + f * 2) / 3;
        return Math.min(1, f) * SPEED;
    }

    /**
     * The flattest shot that hits a point {@code dist} blocks away horizontally and {@code dy} above the eyes, at full
     * draw; null if it's out of range.
     */
    public static Shot aim(double dist, double dy) {
        return aim(dist, dy, SPEED);
    }

    public static Shot aim(double dist, double dy, double speed) {
        Shot best = null;
        double bestErr = 0.3;
        // up from slightly down: the first angle that lands on it is the flat one
        for (double deg = -60; deg <= 60; deg += 0.25) {
            double a = Math.toRadians(deg);
            double vx = Math.cos(a) * speed, vy = Math.sin(a) * speed, x = 0, y = 0;
            for (int t = 1; t <= MAX_TICKS; t++) {
                double nx = x + vx, ny = y + vy;
                if (nx >= dist) {
                    double yAt = y + (ny - y) * (dist - x) / (nx - x);
                    double err = Math.abs(yAt - dy);
                    if (err < bestErr) {
                        bestErr = err;
                        best = new Shot((float) -deg, t);
                    }
                    break;
                }
                x = nx;
                y = ny;
                vx *= DRAG;
                vy = vy * DRAG - GRAVITY;
                if (vy < 0 && y < dy - 20) break;
            }
            if (best != null && bestErr < 0.05) break;
        }
        return best;
    }
}
