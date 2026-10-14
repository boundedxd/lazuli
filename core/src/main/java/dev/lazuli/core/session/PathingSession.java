package dev.lazuli.core.session;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.move.Interaction;
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
import java.util.function.Supplier;

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
    /** Longest one (re)plan may search before giving up. */
    public static final long MAX_PLAN_NANOS = 8_000_000_000L;
    /**
     * Far goals are reached in segments: plan this far (horizontally) toward the goal, walk, and plan the next
     * segment once the player is {@link #HANDOFF} from the segment's end. Within a segment the planner repairs
     * incrementally as usual; only the search radius is bounded, so the first path comes fast.
     */
    public static final int SEGMENT = 64;
    public static final int HANDOFF = 24;
    /** Goals at most this far away (horizontally) are planned directly, which can also prove them unreachable. */
    public static final int DIRECT = 96;
    public static final long SEGMENT_NANOS = 1_500_000_000L;
    private static final int SEGMENT_RADIUS = 4;

    private final BlockView world;
    private final IntSupplier minY;
    private final IntSupplier maxY;
    private final Settings settings;
    private final Executor worker;
    private final Consumer<String> messages;
    private final Supplier<Interaction> interaction;
    private final AtomicBoolean replanQueued = new AtomicBoolean();
    private final AtomicBoolean dirtyQueued = new AtomicBoolean();
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> dirty = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile Snapshot snapshot = new Snapshot(State.IDLE, List.of(), null, 0);
    /** Bumped by every start and stop (on the caller's thread): work for an older trip is dropped. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong versions = new java.util.concurrent.atomic.AtomicLong();
    /** The planner currently searching, so a stop from another thread can cut its search short. */
    private volatile LazyDStarLite searching;

    // worker-confined
    /** The generation of the trip the worker is running. */
    private long tripGeneration;
    private LazyDStarLite planner;
    private MoveGraph graph;
    private Goal goal;
    /** The current segment's end, or null while planning straight to {@link #goal}. */
    private GoalNear segment;
    private boolean fallback;
    /** Whether this trip keeps its own progress out of chat. */
    private boolean quiet;
    private long player;
    /** When the player's reported node stopped being a node (nanoTime), or -1. */
    private long invalidStartSince = -1;
    /** How long a replan keeps the current path while the start isn't a node (mid-pillar, falling down a shaft). */
    private static final long TRANSIENT_NANOS = 3_000_000_000L;

    public PathingSession(BlockView world, IntSupplier minY, IntSupplier maxY, Settings settings,
                          Executor worker, Consumer<String> messages) {
        this(world, minY, maxY, settings, worker, messages, () -> Interaction.NONE);
    }

    /** @param interaction the player's tools and blocks, read on the calling thread when a path starts */
    public PathingSession(BlockView world, IntSupplier minY, IntSupplier maxY, Settings settings,
                          Executor worker, Consumer<String> messages, Supplier<Interaction> interaction) {
        this.interaction = interaction;
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
        start(goal, fallback, playerPos, false);
    }

    /** @param quiet no "Pathing to" / "Arrived" chat (for trips a process makes on its own, like mining) */
    public void start(Goal goal, boolean fallback, long playerPos, boolean quiet) {
        MoveGraph g = newGraph();
        long gen = generation.incrementAndGet();
        cancelSearch();
        worker.execute(() -> {
            if (generation.get() != gen) return; // stopped or replaced before it began
            tripGeneration = gen;
            this.goal = goal;
            this.fallback = fallback;
            this.player = playerPos;
            this.graph = g;
            this.quiet = quiet;
            if (!quiet) messages.accept(Chat.info("Pathing to {}", goal.describe()));
            begin();
        });
    }

    /** Re-plans the current trip from scratch with fresh settings and inventory (e.g. after running out of blocks). */
    public void rebuild() {
        MoveGraph g = newGraph();
        worker.execute(() -> {
            if (planner == null) return;
            this.graph = g;
            begin();
        });
    }

    private MoveGraph newGraph() {
        Interaction in = interaction.get();
        in = new Interaction(in.allowBreak() && settings.allowBreak.get(), in.allowPlace() && settings.allowPlace.get(),
            in.throwaways(), in.breakTime());
        return new MoveGraph(world, settings.maxFallHeight.get(), settings.allowSprint.get(),
            settings.heuristicWeight.get(), settings.allowParkour.get(), in);
    }

    public void stop() {
        stop(false);
    }

    /** Stops at once: the executor sees IDLE right away and any search under way is cut short. */
    public void stop(boolean quiet) {
        long gen = generation.incrementAndGet();
        cancelSearch();
        snapshot = new Snapshot(State.IDLE, List.of(), snapshot.goal(), versions.incrementAndGet());
        if (!quiet) messages.accept(Chat.warn("Stopped"));
        worker.execute(() -> {
            if (generation.get() != gen) return;
            tripGeneration = gen;
            planner = null;
        });
    }

    private void cancelSearch() {
        LazyDStarLite p = searching;
        if (p != null) p.cancel();
    }

    /** The worker's trip has been stopped or replaced: nothing it finds may be published. */
    private boolean stale() {
        return generation.get() != tripGeneration;
    }

    public void playerMoved(long playerPos) {
        worker.execute(() -> {
            player = playerPos;
            if (planner == null) return;
            planner.moveStart(playerPos);
            scheduleReplan();
        });
    }

    /** The executor couldn't get along u->v (stuck, or a jump that landed elsewhere): plan around it. */
    public void edgeFailed(long u, long v) {
        worker.execute(() -> {
            if (planner == null) return;
            planner.edgeFailed(u, v);
            scheduleReplan();
        });
    }

    /**
     * Blocks changed (a chunk loaded, a block was placed...). Changes pile up and are applied together the next time
     * the worker gets to them; only changes to blocks the search actually used cause a replan.
     */
    public void regionChanged(int minX, int minYb, int minZ, int maxX, int maxYb, int maxZ) {
        dirty.add(new int[]{minX, minYb, minZ, maxX, maxYb, maxZ});
        if (dirtyQueued.compareAndSet(false, true)) {
            worker.execute(() -> {
                dirtyQueued.set(false);
                List<int[]> boxes = new ArrayList<>();
                for (int[] b; (b = dirty.poll()) != null; ) boxes.add(b);
                if (planner == null || boxes.isEmpty()) return;
                if (planner.regionsChanged(boxes) > 0) scheduleReplan();
            });
        }
    }

    private void scheduleReplan() {
        if (replanQueued.compareAndSet(false, true)) {
            worker.execute(() -> {
                replanQueued.set(false);
                replan();
            });
        }
    }

    /** Plans from the player: straight to the goal when it's near, otherwise the next segment toward it. */
    private void begin() {
        segment = null;
        int[] t = goalXZ();
        if (t != null) {
            double dx = t[0] - Pos.x(player), dz = t[1] - Pos.z(player), d = Math.sqrt(dx * dx + dz * dz);
            if (d > DIRECT) {
                // straight ahead first, then fanning out, then nearer, in case the way ahead is a lake or a cliff
                double[][] tries = {{0, SEGMENT}, {0.6, SEGMENT}, {-0.6, SEGMENT}, {1.2, SEGMENT}, {-1.2, SEGMENT}, {0, SEGMENT / 2.0}};
                for (double[] tr : tries) {
                    double c = Math.cos(tr[0]), sn = Math.sin(tr[0]);
                    double ux = (dx * c - dz * sn) / d, uz = (dx * sn + dz * c) / d;
                    int wx = (int) Math.round(Pos.x(player) + ux * tr[1]), wz = (int) Math.round(Pos.z(player) + uz * tr[1]);
                    int wy = Pos.y(player);
                    if (goal instanceof GoalBlock b) wy += (int) Math.round((b.y() - wy) * tr[1] / d);
                    else if (goal instanceof GoalNear n) wy += (int) Math.round((n.y() - wy) * tr[1] / d);
                    segment = new GoalNear(wx, surface(wx, wy, wz), wz, SEGMENT_RADIUS);
                    createPlanner();
                    PlanResult r = search(SEGMENT_NANOS);
                    if (stale()) return;
                    if (r.status() == PlanResult.Status.FOUND) {
                        publish(State.PATHING, r.path());
                        return;
                    }
                }
                segment = null; // nothing ahead worked; search the whole way and report honestly
            }
        }
        createPlanner();
        replan();
    }

    /** The goal's horizontal target, or null for goals without one (y levels). */
    private int[] goalXZ() {
        if (goal instanceof GoalBlock b) return new int[]{b.x(), b.z()};
        if (goal instanceof GoalXZ g) return new int[]{g.x(), g.z()};
        if (goal instanceof GoalNear n) return new int[]{n.x(), n.z()};
        return null;
    }

    /** The standable y in column (x, z) nearest {@code y}, or {@code y} if none is known nearby. */
    private int surface(int x, int y, int z) {
        for (int d = 0; d <= 48; d++) {
            if (MoveGraph.isStandable(world, x, y - d, z)) return y - d;
            if (MoveGraph.isStandable(world, x, y + d, z)) return y + d;
        }
        return y;
    }

    private void createPlanner() {
        Goal goal = segment != null ? segment : this.goal;
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
            if (!quiet) messages.accept(Chat.ok("Arrived at {}", goal.describe()));
            return;
        }
        if (segment != null && Math.hypot(segment.x() - Pos.x(player), segment.z() - Pos.z(player)) <= HANDOFF
            && graph.isNode(player)) {
            begin(); // the current path stays published while the next segment is planned
            return;
        }
        if (!graph.isNode(player) && snapshot.state() == State.PATHING) {
            // the block just placed under the jumping player, or mined from under them: they'll land on a node soon
            long now = System.nanoTime();
            if (invalidStartSince < 0) invalidStartSince = now;
            if (now - invalidStartSince < TRANSIENT_NANOS) return;
        } else {
            invalidStartSince = -1;
        }
        PlanResult r = search(MAX_PLAN_NANOS);
        if (stale()) return;
        if (r.status() == PlanResult.Status.FOUND) {
            publish(State.PATHING, r.path());
            return;
        }
        if (segment != null) {
            begin(); // this segment's end turned out unreachable: pick another
            return;
        }
        if (fallback) {
            OptionalLong spot = SafeSpotFinder.find(graph, world, player, goal, FALLBACK_NODES);
            if (spot.isPresent()) {
                long s = spot.getAsLong();
                goal = new GoalBlock(Pos.x(s), Pos.y(s), Pos.z(s));
                fallback = false;
                messages.accept(Chat.warn("Goal unreachable; heading to nearest safe spot {}", Pos.toString(s)));
                createPlanner();
                replan();
                return;
            }
        }
        planner = null;
        publish(State.FAILED, List.of());
        if (!quiet) messages.accept(Chat.err(r.status() == PlanResult.Status.NO_PATH ? "Goal unreachable" : "Gave up: search budget exceeded"));
    }

    private PlanResult search(long nanos) {
        searching = planner;
        if (stale()) planner.cancel(); // a stop that came before this search started
        try {
            return planner.plan(settings.evalHorizon.get(), MAX_EXPANSIONS, MAX_PATH_LENGTH, nanos);
        } finally {
            searching = null;
        }
    }

    private void publish(State state, List<Long> path) {
        if (stale()) return;
        snapshot = new Snapshot(state, List.copyOf(path), goal, versions.incrementAndGet());
    }
}
