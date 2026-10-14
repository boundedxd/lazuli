package dev.lazuli.core.goal;

import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

public record GoalXZ(int x, int z) implements Goal {
    @Override
    public boolean isGoal(int x, int y, int z) {
        return x == this.x && z == this.z;
    }

    @Override
    public void forEachCandidate(int px, int py, int pz, int minY, int maxY, LongConsumer out) {
        for (int y = minY; y <= maxY; y++) out.accept(Pos.pack(x, y, z));
    }

    @Override
    public double distance(int x, int y, int z) {
        double dx = x - this.x, dz = z - this.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String describe() {
        return "x " + x + " z " + z;
    }
}
