package dev.lazuli.core.race;

/**
 * How far a point still is from the finish, along the track (not as the crow flies): what the racer minimises. A
 * {@link Cursor} follows one boat (or one imagined run of it) along the track, so a lap course knows which lap it's on
 * and a recorded line which stretch it's near.
 */
public interface RaceLine {
    /** Where along the track one boat is. Copied for each imagined run. */
    final class Cursor {
        /** Stretch of the line it's near (recorded lines), or -1. */
        public int seg = -1;
        /** Blocks taken off for laps done (lap courses count down by a lap each time the line is crossed). */
        public double offset;
        /** Laps finished. */
        public int laps;
        public double lastX = Double.NaN, lastZ = Double.NaN;

        public Cursor copy() {
            Cursor c = new Cursor();
            c.copyFrom(this);
            return c;
        }

        public void copyFrom(Cursor o) {
            seg = o.seg;
            offset = o.offset;
            laps = o.laps;
            lastX = o.lastX;
            lastZ = o.lastZ;
        }
    }

    /** A cursor for a boat starting at (x, y, z). */
    Cursor start(double x, double y, double z);

    /**
     * Blocks still to go from (x, y, z), moving the cursor along (laps crossed, stretch reached); NaN off the track
     * (over the void, outside the walls).
     */
    double toGo(Cursor c, double x, double y, double z);

    /** Whether a boat with this cursor and distance to go has finished (the goal reached, or the laps done). */
    boolean finished(Cursor c, double toGo);

    /** Same, without moving the cursor. */
    default double peek(Cursor c, double x, double y, double z) {
        return toGo(c.copy(), x, y, z);
    }
}
