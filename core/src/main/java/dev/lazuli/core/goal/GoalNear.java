package dev.lazuli.core.goal;

import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

/** Any block within {@code radius} (euclidean) of the center. */
public record GoalNear(int x, int y, int z, int radius) implements Goal {
    @Override
    public boolean isGoal(int x, int y, int z) {
        long dx = x - this.x, dy = y - this.y, dz = z - this.z;
        return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
    }

    @Override
    public void forEachCandidate(int px, int py, int pz, int minY, int maxY, LongConsumer out) {
        for (int cx = x - radius; cx <= x + radius; cx++)
            for (int cy = Math.max(minY, y - radius); cy <= Math.min(maxY, y + radius); cy++)
                for (int cz = z - radius; cz <= z + radius; cz++)
                    if (isGoal(cx, cy, cz)) out.accept(Pos.pack(cx, cy, cz));
    }

    @Override
    public double distance(int x, int y, int z) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - radius);
    }

    @Override
    public String describe() {
        return "within " + radius + " of " + x + " " + y + " " + z;
    }
}
