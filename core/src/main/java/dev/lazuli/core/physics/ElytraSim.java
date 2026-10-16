package dev.lazuli.core.physics;

/**
 * A tick of elytra gliding, as the game does it (LivingEntity#updateFallFlyingMovement), with a firework rocket's
 * push (FireworkRocketEntity#tick) when one is attached. No collisions: callers check the ground themselves.
 */
public final class ElytraSim {
    private ElytraSim() {}

    public static final double GRAVITY = 0.08;
    /** About how long one flight-1 rocket pushes (the game rolls 16 to 27 ticks). */
    public static final int BOOST_TICKS = 20;

    /** Position and motion, mutable so a sim can step it without allocating. */
    public static final class State {
        public double x, y, z, vx, vy, vz;

        public State(double x, double y, double z, double vx, double vy, double vz) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
        }

        public State copy() {
            return new State(x, y, z, vx, vy, vz);
        }

        public double speed() {
            return Math.sqrt(vx * vx + vy * vy + vz * vz);
        }

        public double horizontalSpeed() {
            return Math.sqrt(vx * vx + vz * vz);
        }
    }

    /** One tick looking along yaw/pitch (degrees, the game's convention: pitch up is negative). */
    public static void step(State s, float yaw, float pitch, boolean boosted) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double lx = -Math.sin(yr) * Math.cos(pr), ly = -Math.sin(pr), lz = Math.cos(yr) * Math.cos(pr);
        if (boosted) {
            s.vx += lx * 0.1 + (lx * 1.5 - s.vx) * 0.5;
            s.vy += ly * 0.1 + (ly * 1.5 - s.vy) * 0.5;
            s.vz += lz * 0.1 + (lz * 1.5 - s.vz) * 0.5;
        }
        double d = Math.sqrt(lx * lx + lz * lz);
        double e = Math.sqrt(s.vx * s.vx + s.vz * s.vz);
        double h = Math.cos(pr) * Math.cos(pr);
        s.vy += GRAVITY * (-1.0 + h * 0.75);
        if (s.vy < 0 && d > 0) {
            double i = s.vy * -0.1 * h;
            s.vx += lx * i / d;
            s.vy += i;
            s.vz += lz * i / d;
        }
        if (pr < 0 && d > 0) {
            double i = e * -Math.sin(pr) * 0.04;
            s.vx += -lx * i / d;
            s.vy += i * 3.2;
            s.vz += -lz * i / d;
        }
        if (d > 0) {
            s.vx += (lx / d * e - s.vx) * 0.1;
            s.vz += (lz / d * e - s.vz) * 0.1;
        }
        s.vx *= 0.99F;
        s.vy *= 0.98F;
        s.vz *= 0.99F;
        s.x += s.vx;
        s.y += s.vy;
        s.z += s.vz;
    }
}
