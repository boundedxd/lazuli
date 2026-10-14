package dev.lazuli.core.move;

/** Movement costs in ticks. */
public final class Costs {
    public static final double WALK_ONE = 20 / 4.317;
    public static final double SPRINT_ONE = 20 / 5.612;
    public static final double JUMP_PENALTY = 2.0;
    public static final double SQRT2 = Math.sqrt(2);

    private static final double[] FALL = new double[257];

    static {
        // vanilla vertical motion from rest: v = (v - 0.08) * 0.98 each tick
        double v = 0, d = 0;
        int t = 0;
        for (int n = 1; n < FALL.length; n++) {
            while (d < n) {
                v = (v - 0.08) * 0.98;
                d -= v;
                t++;
            }
            FALL[n] = t;
        }
    }

    private Costs() {}

    public static double fallTicks(int blocks) {
        return FALL[blocks];
    }
}
