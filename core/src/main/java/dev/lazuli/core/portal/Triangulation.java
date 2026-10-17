package dev.lazuli.core.portal;

/**
 * Finding a stronghold from eyes of ender: each thrown eye flies (over the ground) straight toward it, so two throws
 * from spots far enough apart cross there.
 */
public final class Triangulation {
    /** Throws closer than this to parallel (sine of the angle between them) can't be told apart from noise. */
    private static final double MIN_SIN = Math.sin(Math.toRadians(1.5));

    private Triangulation() {}

    /** Where an eye was thrown from and which way it flew (a unit vector over the ground). */
    public record Throw(double x, double z, double dx, double dz) {
        public Throw {
            double len = Math.hypot(dx, dz);
            if (len > 0) {
                dx /= len;
                dz /= len;
            }
        }

        /** From two points along the eye's flight; null if it hardly moved (sideways). */
        public static Throw of(double x0, double z0, double x1, double z1) {
            if (Math.hypot(x1 - x0, z1 - z0) < 0.05) return null;
            return new Throw(x0, z0, x1 - x0, z1 - z0);
        }
    }

    /** Where two throws' lines cross, ahead of both: {x, z}; null when nearly parallel or behind. */
    public static double[] meet(Throw a, Throw b) {
        double cross = a.dx * b.dz - a.dz * b.dx;
        if (Math.abs(cross) < MIN_SIN) return null;
        double ox = b.x - a.x, oz = b.z - a.z;
        double t = (ox * b.dz - oz * b.dx) / cross, u = (ox * a.dz - oz * a.dx) / cross;
        if (t <= 0 || u <= 0) return null;
        return new double[]{a.x + a.dx * t, a.z + a.dz * t};
    }

    /** A spot {@code dist} blocks square to the throw's line from where it was thrown: where to throw the next one. */
    public static double[] sideStep(Throw a, double dist) {
        return new double[]{a.x + -a.dz * dist, a.z + a.dx * dist};
    }
}
