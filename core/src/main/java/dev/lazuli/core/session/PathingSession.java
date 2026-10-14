package dev.lazuli.core.session;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.move.SafeSpotFinder;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.planner.PlanResult;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Owns one Lazy D* Lite planner. All mutation happens on {@code worker} (which must run tasks in order);
 * the latest result is published as an immutable snapshot readable from any thread.
 */
public final class PathingSession {
    public enum State { IDLE, PATHING, ARRIVED, FAILED }

    public record Snapshot(State state, List<Long> path, Goal goal, long version) {}

    public static final long MAX_EXPANSIONS = 1_000_000;
    public static final int MAX_PATH_LENGTH = 512;
    public static final int FALLBACK_NODES = 200_000;

    private final BlockView world;
    private final IntSupplier minY;
    private final IntSupplier maxY;
    private final Settings settings;
    private final Executor worker;
    private final Consumer<String> messages;
    private final AtomicBoolean replanQueued = new AtomicBoolean();
    private volatile Snapshot snapshot = new Snapshot(State.IDLE, List.of(), null, 0);

    // worker-confined
    private long version;
    private LazyDStarLite planner;
    private MoveGraph graph;
    private Goal goal;
    private boolean fallback;
    private long player;

    public PathingSession(BlockView world, IntSupplier minY, IntSupplier maxY, Settings settings,
                          Executor worker, Consumer<String> messages) {
        this.world = world;
        this.minY = minY;
        this.maxY = maxY;
        this.settings = settings;
        this.worker = worker;
        this.messages = messages;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void start(Goal goal, boolean fallback, long playerPos) {
        worker.execute(() -> {
            this.goal = goal;
            this.fallback = fallback;
            this.player = playerPos;
            this.graph = new MoveGraph(world, settings.maxFallHeight.get(), settings.allowSprint.get());
            createPlanner();
            messages.accept("Pathing to " + goal.describe());
            replan();
        });
    }

    public void stop() {
        worker.execute(() -> {
            planner = null;
            publish(State.IDLE, List.of());
            messages.accept("Stopped");
        });
    }

    public void playerMoved(long playerPos) {
        worker.execute(() -> {
            player = playerPos;
            if (planner == null) return;
            planner.moveStart(playerPos);
            scheduleReplan();
        });
    }

    public void regionChanged(int minX, int minYb, int minZ, int maxX, int maxYb, int maxZ) {
        worker.execute(() -> {
            if (planner == null) return;
            planner.regionChanged(minX, minYb, minZ, maxX, maxYb, maxZ);
            scheduleReplan();
        });
    }

    private void scheduleReplan() {
        if (replanQueued.compareAndSet(false, true)) {
            worker.execute(() -> {
                replanQueued.set(false);
                replan();
            });
        }
    }

    private void createPlanner() {
        List<Long> candidates = new ArrayList<>();
        goal.forEachCandidate(Pos.x(player), Pos.y(player), Pos.z(player), minY.getAsInt(), maxY.getAsInt(), candidates::add);
        long[] arr = new long[candidates.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = candidates.get(i);
        Goal g = goal;
        planner = new LazyDStarLite(graph, p -> g.isGoal(Pos.x(p), Pos.y(p), Pos.z(p)), arr, player);
    }

    private void replan() {
        if (planner == null) return;
        if (goal.isGoal(Pos.x(player), Pos.y(player), Pos.z(player))) {
            planner = null;
            publish(State.ARRIVED, List.of());
            messages.accept("Arrived at " + goal.describe());
            return;
        }
        PlanResult r = planner.plan(settings.evalHorizon.get(), MAX_EXPANSIONS, MAX_PATH_LENGTH);
        if (r.status() == PlanResult.Status.FOUND) {
            publish(State.PATHING, r.path());
            return;
        }
        if (fallback) {
            OptionalLong spot = SafeSpotFinder.find(graph, world, player, goal, FALLBACK_NODES);
            if (spot.isPresent()) {
                long s = spot.getAsLong();
                goal = new GoalBlock(Pos.x(s), Pos.y(s), Pos.z(s));
                fallback = false;
                messages.accept("Goal unreachable; heading to nearest safe spot " + Pos.toString(s));
                createPlanner();
                replan();
                return;
            }
        }
        planner = null;
        publish(State.FAILED, List.of());
        messages.accept(r.status() == PlanResult.Status.NO_PATH ? "Goal unreachable" : "Gave up: search budget exceeded");
    }

    private void publish(State state, List<Long> path) {
        snapshot = new Snapshot(state, List.copyOf(path), goal, ++version);
    }
}
