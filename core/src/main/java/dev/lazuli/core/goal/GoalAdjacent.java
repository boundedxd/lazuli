package dev.lazuli.core.goal;

import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

/**
 * Standing where block (x, y, z) is right next to the body, so it can be broken from there: on top of it, under it,
 * beside it at foot or head height, or in its place (having dug through it).
 */
public record GoalAdjacent(int x, int y, int z) implements Goal {
    private static final int[][] SPOTS = {{0, 1, 0}, {0, -2, 0}, {0, 0, 0}, {0, -1, 0},
        {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1}};

    @Override
    public boolean isGoal(int x, int y, int z) {
        for (int[] s : SPOTS) if (x == this.x + s[0] && y == this.y + s[1] && z == this.z + s[2]) return true;
        return false;
    }

    @Override
    public void forEachCandidate(int px, int py, int pz, int minY, int maxY, LongConsumer out) {
        for (int[] s : SPOTS) {
            int sy = y + s[1];
            if (sy >= minY && sy <= maxY) out.accept(Pos.pack(x + s[0], sy, z + s[2]));
        }
    }

    @Override
    public double distance(int x, int y, int z) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - 1.5);
    }

    @Override
    public String describe() {
        return "next to " + x + " " + y + " " + z;
    }
}
