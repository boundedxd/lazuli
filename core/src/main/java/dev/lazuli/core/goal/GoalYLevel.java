package dev.lazuli.core.goal;

import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

public record GoalYLevel(int y) implements Goal {
    public static final int CANDIDATE_RADIUS = 64;

    @Override
    public boolean isGoal(int x, int y, int z) {
        return y == this.y;
    }

    @Override
    public void forEachCandidate(int px, int py, int pz, int minY, int maxY, LongConsumer out) {
        if (y < minY || y > maxY) return;
        for (int x = px - CANDIDATE_RADIUS; x <= px + CANDIDATE_RADIUS; x++)
            for (int z = pz - CANDIDATE_RADIUS; z <= pz + CANDIDATE_RADIUS; z++) out.accept(Pos.pack(x, y, z));
    }

    @Override
    public double distance(int x, int y, int z) {
        return Math.abs(y - this.y);
    }

    @Override
    public String describe() {
        return "y " + y;
    }
}
