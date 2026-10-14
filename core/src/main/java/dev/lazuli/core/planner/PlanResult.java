package dev.lazuli.core.planner;

import java.util.List;

/** Outcome of {@link LazyDStarLite#plan}. {@code path} starts at the start node and ends at a goal node. */
public record PlanResult(Status status, List<Long> path) {
    public enum Status { FOUND, NO_PATH, BUDGET_EXCEEDED }

    public static PlanResult found(List<Long> path) {
        return new PlanResult(Status.FOUND, List.copyOf(path));
    }

    public static PlanResult noPath() {
        return new PlanResult(Status.NO_PATH, List.of());
    }

    public static PlanResult budgetExceeded() {
        return new PlanResult(Status.BUDGET_EXCEEDED, List.of());
    }
}
