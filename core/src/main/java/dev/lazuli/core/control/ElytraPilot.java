package dev.lazuli.core.control;

import dev.lazuli.core.physics.ElytraSim;

/**
 * Flies an elytra to a spot: each tick it tries a few pitches (with and without firing a rocket), simulates each a
 * second and a half ahead and takes the best: getting closer, at a cruising height over the terrain ahead, never near
 * the ground, rockets only when they're worth it. Close to the target it glides down onto it.
 */
public final class ElytraPilot {
    /** Top of the ground at (x, z): the y a player would stand at, or {@link #UNKNOWN} (not loaded). */
    @FunctionalInterface
    public interface Ground {
        int at(int x, int z);
    }

    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** What to do this tick: where to look, and whether to fire a rocket. */
    public record Command(float yaw, float pitch, boolean fire) {}

    private static final float[] PITCHES = {-50, -35, -22, -12, -6, -2, 2, 6, 12, 20, 30, 45};
    private static final int HORIZON = 30;
    /** Blocks of ground ahead the cruising height is set over. */
    private static final int LOOK_AHEAD = 80;
    /** Below this many blocks over the ground is a crash (outside the landing). */
    private static final double CRASH = 1.5;

    private final double cruise;
    private int boostLeft;

    /** @param cruise how high over the highest ground ahead to fly (blocks) */
    public ElytraPilot(double cruise) {
        this.cruise = cruise;
    }

    /** A rocket was just fired (it pushes for about {@link ElytraSim#BOOST_TICKS}). */
    public void fired() {
        boostLeft = ElytraSim.BOOST_TICKS;
    }

    public boolean boosting() {
        return boostLeft > 0;
    }

    /** Whether it's down to the last glide onto the target (no more rockets). */
    public boolean landing(ElytraSim.State s, double tx, double tz, int landY) {
        double dh = Math.hypot(tx - s.x, tz - s.z);
        return dh < Math.max(24, (s.y - landY) * 3.5);
    }

    /**
     * @param rockets whether there are rockets to fire
     * @return the look and whether to fire this tick
     */
    public Command tick(ElytraSim.State s, double tx, double tz, int landY, Ground ground, boolean rockets) {
        if (boostLeft > 0) boostLeft--;
        float yaw = (float) (Math.toDegrees(Math.atan2(tz - s.z, tx - s.x)) - 90.0);
        boolean land = landing(s, tx, tz, landY);
        double alt = land ? landY : cruiseHeight(s, tx, tz, ground);
        double best = Double.POSITIVE_INFINITY;
        Command pick = new Command(yaw, 0, false);
        for (int f = 0; f < 2; f++) {
            boolean fire = f == 1;
            if (fire && (!rockets || boostLeft > 0 || land)) continue;
            for (float pitch : PITCHES) {
                double c = cost(s, yaw, pitch, fire, tx, tz, landY, alt, land, ground);
                if (c < best) {
                    best = c;
                    pick = new Command(yaw, pitch, fire);
                }
            }
        }
        if (pick.fire()) fired();
        return pick;
    }

    /** The height to fly at: {@link #cruise} over the highest known ground between here and the target (a way ahead). */
    private double cruiseHeight(ElytraSim.State s, double tx, double tz, Ground ground) {
        double dx = tx - s.x, dz = tz - s.z, d = Math.hypot(dx, dz);
        int top = UNKNOWN;
        boolean unknown = false;
        for (int i = 0; i <= LOOK_AHEAD; i += 4) {
            double f = Math.min(i, d) / Math.max(d, 1e-9);
            for (int side = -4; side <= 4; side += 4) {
                // a little to each side too: a gust of steering shouldn't clip a hill's shoulder
                int x = (int) Math.floor(s.x + dx * f - dz / Math.max(d, 1e-9) * side);
                int z = (int) Math.floor(s.z + dz * f + dx / Math.max(d, 1e-9) * side);
                int g = ground.at(x, z);
                if (g == UNKNOWN) unknown = true;
                else top = Math.max(top, g);
            }
            if (i >= d) break;
        }
        double want = top == UNKNOWN ? s.y : top + cruise;
        // flying blind over chunks not loaded yet: don't come down into them
        return unknown ? Math.max(want, s.y) : want;
    }

    private double cost(ElytraSim.State s0, float yaw, float pitch, boolean fire, double tx, double tz, int landY,
                        double alt, boolean land, Ground ground) {
        ElytraSim.State s = s0.copy();
        double d0 = Math.hypot(tx - s.x, tz - s.z);
        double c = 0;
        int boost = fire ? ElytraSim.BOOST_TICKS : boostLeft;
        for (int t = 0; t < HORIZON; t++) {
            ElytraSim.step(s, yaw, pitch, boost-- > 0);
            int g = ground.at((int) Math.floor(s.x), (int) Math.floor(s.z));
            if (g == UNKNOWN) continue;
            double over = s.y - g;
            double dh = Math.hypot(tx - s.x, tz - s.z);
            boolean atTarget = land && dh < 6;
            if (over < CRASH && !atTarget) return 1e9 - t; // later is less bad
            double safe = land ? 3 : 8;
            if (over < safe && !atTarget) c += (safe - over) * 40;
        }
        double dh = Math.hypot(tx - s.x, tz - s.z);
        if (land) {
            // onto the target, slowly at the end: aim for a point over it, then flare
            double drop = s.y - (landY + 1);
            c += Math.hypot(dh, drop) * 6;
            if (dh < 12) c += s.speed() * 25;
            return c;
        }
        c -= (d0 - dh) * 8; // getting there
        c += Math.abs(s.y - alt) * 2.5;
        if (s.y < alt - 4 && s.vy < 0) c += -s.vy * 30; // sinking while too low
        if (fire) c += 12;
        if (s.horizontalSpeed() < 0.6) c += (0.6 - s.horizontalSpeed()) * 60; // a stall
        return c;
    }
}
