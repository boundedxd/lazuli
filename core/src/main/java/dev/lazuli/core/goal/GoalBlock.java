package dev.lazuli.core.goal;

import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

public record GoalBlock(int x, int y, int z) implements Goal {
    @Override
    public boolean isGoal(int x, int y, int z) {
        return x == this.x && y == this.y && z == this.z;
    }

    @Override
    public void forEachCandidate(int px, int py, int pz, int minY, int maxY, LongConsumer out) {
        out.accept(Pos.pack(x, y, z));
    }

    @Override
    public double distance(int x, int y, int z) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public String describe() {
        return "block " + x + " " + y + " " + z;
    }
}
