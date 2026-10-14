package dev.lazuli.core.goal;

import java.util.List;
import java.util.function.LongConsumer;

/** Any of several goals: the planner heads for whichever is cheapest to reach. */
public record GoalAny(List<Goal> goals, String what) implements Goal {
    public GoalAny {
        goals = List.copyOf(goals);
    }

    @Override
    public boolean isGoal(int x, int y, int z) {
        for (Goal g : goals) if (g.isGoal(x, y, z)) return true;
        return false;
    }

    @Override
    public void forEachCandidate(int px, int py, int pz, int minY, int maxY, LongConsumer out) {
        for (Goal g : goals) g.forEachCandidate(px, py, pz, minY, maxY, out);
    }

    @Override
    public double distance(int x, int y, int z) {
        double best = Double.POSITIVE_INFINITY;
        for (Goal g : goals) best = Math.min(best, g.distance(x, y, z));
        return best;
    }

    @Override
    public String describe() {
        return what;
    }
}
