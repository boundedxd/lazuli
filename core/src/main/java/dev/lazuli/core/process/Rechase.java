package dev.lazuli.core.process;

/** When a chase should ask for a new trip instead of keeping the one it has. */
public final class Rechase {
    /** The target moved more than six blocks from where the trip was planned. */
    public static final double MOVED_SQ = 36;
    /** Five seconds on one goal: whatever happens, think again. */
    public static final int MAX_TICKS = 100;

    private Rechase() {}

    /** True while the current trip still serves (keep walking); false when a new one is wanted. */
    public static boolean keep(boolean running, double targetMovedSq, int ticksSincePlan) {
        if (!running) return false;
        if (ticksSincePlan > MAX_TICKS) return false;
        return targetMovedSq <= MOVED_SQ;
    }
}
