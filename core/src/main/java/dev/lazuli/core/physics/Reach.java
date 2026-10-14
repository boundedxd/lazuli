package dev.lazuli.core.physics;

import dev.lazuli.core.world.BlockKind;

/**
 * How far a player can possibly get from a standstill in a given number of ticks, found by simulating the
 * fastest ways to move on open flat ground (sprinting, and sprint-jumping with every first take-off tick,
 * re-jumping on each landing). Gives planners an admissible yet tight lower bound on travel time.
 */
public final class Reach {
    private static final int TICKS = 200;
    /** MAX[t] = farthest horizontal distance reachable in t ticks. */
    private static final double[] MAX = compute();

    private Reach() {}

    /** Fewest ticks in which {@code distance} blocks could possibly be covered from a standstill. */
    public static int minTicks(double distance) {
        if (distance <= 0) return 0;
        for (int t = 1; t < TICKS; t++) {
            if (MAX[t] >= distance) return t;
        }
        return (int) Math.ceil(TICKS + (distance - MAX[TICKS - 1]) / 0.4);
    }

    private static double[] compute() {
        double[] max = new double[TICKS];
        dev.lazuli.core.world.BlockView flat = (x, y, z) -> y < 1 ? BlockKind.SOLID : BlockKind.AIR;
        for (int firstJump = -1; firstJump < 12; firstJump++) {
            PlayerSim s = new PlayerSim(flat, 0.5, 1, 0.5, 0, 0, 0, true, false);
            for (int t = 1; t < TICKS; t++) {
                boolean jump = firstJump >= 0 && t - 1 >= firstJump;
                s.step(1, jump, true, -90f);
                max[t] = Math.max(max[t], s.x - 0.5);
            }
        }
        // keep it monotone and a hair generous
        for (int t = 1; t < TICKS; t++) max[t] = Math.max(max[t], max[t - 1]) + 1e-6;
        return max;
    }
}
