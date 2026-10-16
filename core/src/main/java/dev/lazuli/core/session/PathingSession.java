package dev.lazuli.core.session;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.move.Hazards;
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

    /** @param trip the {@link #start} (or stop) this snapshot belongs to */
    public record Snapshot(State state, List<Long> path, Goal goal, long version, long trip) {}

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
    /**
     * Digging, goals further than this up or down are also reached in segments, this far at a time: through solid
     * ground every cell is a node and one search all the way (surface to diamonds) floods the rock.
     */
    public static final int DEEP = 32;

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
    private volatile Snapshot snapshot = new Snapshot(State.IDLE, List.of(), null, 0, 0);
    /** Bumped by every start and stop (on the caller's thread): work for an older trip is dropped. */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong versions = new java.util.concurrent.atomic.AtomicLong();
    /** The planner currently searching, so a stop from another thread can cut its search short. */
    private volatile LazyDStarLite searching;

    // worker-confined
    /** The generation of the trip the worker is running. */
    private long tripGeneration;
    /** The trip number snapshots carry: the generation that started it, kept through a {@link #restart}. */
    private long tripId;
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

    /** A trip was asked for and its first path isn't out yet (still searching). */
    private volatile boolean planning;

    public boolean planning() {
        return planning;
    }

    public long start(Goal goal, boolean fallback, long playerPos) {
        return start(goal, fallback, playerPos, false);
    }

    /**
     * @param quiet no "Pathing to" / "Arrived" chat (for trips a process makes on its own, like mining)
     * @return the trip's number: its snapshots carry it, and a later start or stop gets a higher one
     */
    public long start(Goal goal, boolean fallback, long playerPos, boolean quiet) {
        MoveGraph g = newGraph();
        long gen = generation.incrementAndGet();
        request = new Request(goal, fallback, quiet, gen);
        planning = true;
        cancelSearch();
        worker.execute(() -> {
            if (generation.get() != gen) return; // stopped or replaced before it began
            tripGeneration = gen;
            tripId = gen;
            this.goal = goal;
            this.fallback = fallback;
            this.graph = g;
            this.player = snap(playerPos);
            this.quiet = quiet;
            if (!quiet) messages.accept(Chat.info("Pathing to {}", goal.describe()));
            begin();
        });
        return gen;
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

    /**
     * The trip seems stuck (the player hasn't moved in a while): drop whatever is being searched and plan the same
     * trip again from scratch from {@code playerPos}, keeping its number so whoever asked for it doesn't notice.
     */
    public void unstick(long playerPos) {
        MoveGraph g = newGraph();
        long gen = generation.get();
        cancelSearch();
        worker.execute(() -> {
            if (gen != tripGeneration || generation.get() != gen || goal == null) return;
            if (snapshot.state() != State.PATHING) return;
            graph = g;
            player = snap(playerPos);
            invalidStartSince = -1;
            begin();
        });
    }

    /** What the last start asked for, kept on the caller's side so a {@link #restart} doesn't need the old worker. */
    private record Request(Goal goal, boolean fallback, boolean quiet, long trip) {}

    private volatile Request request;

    /**
     * Stuck for real (what used to take #kill): call once the worker runs tasks again (the mod swaps in a fresh thread
     * when the old one hung). Forgets all trip state, as {@link #kill} does, and plans the last trip again from scratch
     * under its own trip number, so the processes waiting on it carry on as if nothing happened.
     */
    public void restart(long playerPos) {
        Request r = request;
        if (r == null || snapshot.trip() > r.trip() || snapshot.state() != State.PATHING && !planning) return;
        long gen = generation.incrementAndGet();
        cancelSearch();
        replanQueued.set(false);
        dirtyQueued.set(false);
        dirty.clear();
        planning = true;
        MoveGraph g = newGraph();
        worker.execute(() -> {
            if (generation.get() != gen) return;
            tripGeneration = gen;
            tripId = r.trip();
            goal = r.goal();
            fallback = r.fallback();
            quiet = r.quiet();
            graph = g;
            player = snap(playerPos);
            planner = null;
            segment = null;
            invalidStartSince = -1;
            begin();
        });
    }

    private MoveGraph newGraph() {
        Interaction in = interaction.get();
        in = new Interaction(in.allowBreak() && settings.allowBreak.get(), in.allowPlace() && settings.allowPlace.get(),
            in.throwaways(), in.breakTime());
        return new MoveGraph(world, settings.maxFallHeight.get(), settings.allowSprint.get(),
            settings.heuristicWeight.get(), settings.allowParkour.get(), in).withClutch(clutchFall)
            .withHazards(settings.avoidMobs.get() ? hazards : Hazards.NONE, settings.avoidLava.get() ? LAVA_EDGE : 0);
    }

    /** Extra ticks for a cell right beside lava. */
    static final double LAVA_EDGE = 6;
    private volatile Hazards hazards = Hazards.NONE;

    /**
     * The mobs to keep away from now (see {@link Hazards}). The trip under way re-prices the cells around the old and
     * new ones and replans if that changed anything it used.
     */
    public void setHazards(Hazards h) {
        Hazards old = hazards;
        if (old.sameAs(h)) return;
        hazards = h;
        if (!settings.avoidMobs.get()) return;
        worker.execute(() -> {
            if (graph == null || planner == null) return;
            Hazards before = graph.hazards();
            graph.setHazards(h);
            List<int[]> boxes = new ArrayList<>(before.boxes());
            boxes.addAll(h.boxes());
            if (!boxes.isEmpty() && planner.regionsChanged(boxes) > 0) scheduleReplan();
        });
    }

    private volatile int clutchFall;

    /**
     * Drops up to this many blocks are fine (0: just maxFallHeight): there's a water bucket to clutch with. A change
     * re-plans the trip under way, so it never counts on a bucket that's gone.
     */
    public void setClutchFall(int blocks) {
        if (blocks == clutchFall) return;
        clutchFall = blocks;
        if (snapshot.state() == State.PATHING) rebuild();
    }

    public void stop() {
        stop(false);
    }

    /** Stops at once: the executor sees IDLE right away and any search under way is cut short. */
    public void stop(boolean quiet) {
        long gen;
        synchronized (this) { // with publish: a path found just as the stop came can't be published after it
            gen = generation.incrementAndGet();
            planning = false;
            snapshot = new Snapshot(State.IDLE, List.of(), snapshot.goal(), versions.incrementAndGet(), gen);
        }
        cancelSearch();
        if (!quiet) messages.accept(Chat.warn("Stopped"));
        worker.execute(() -> {
            if (generation.get() != gen) return;
            tripGeneration = gen;
            planner = null;
        });
    }

    /**
     * Last resort (#kill) after the worker hung or died: stops everything and forgets every bit of trip state, including
     * work a lost task left marked as queued. Call it once the worker the session uses runs tasks again (a new thread).
     */
    public void kill() {
        stop(true);
        replanQueued.set(false);
        dirtyQueued.set(false);
        dirty.clear();
        long gen = generation.get();
        worker.execute(() -> {
            if (generation.get() != gen) return;
            tripGeneration = gen;
            planner = null;
            segment = null;
            invalidStartSince = -1;
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
            player = graph == null ? playerPos : snap(playerPos);
            if (planner == null) return;
            planner.moveStart(player);
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

    /** u->v can't be done at all (a bridge with no blocks): plan around it for good. */
    public void edgeImpossible(long u, long v) {
        worker.execute(() -> {
            if (planner == null) return;
            planner.edgeImpossible(u, v);
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
        int[] p = goalPoint(goal);
        if (p != null && graph.digs() && Math.abs(p[1] - Pos.y(player)) > DEEP) {
            // a dig toward it, DEEP blocks up or down at a time
            double f = (double) DEEP / Math.abs(p[1] - Pos.y(player));
            segment = new GoalNear((int) Math.round(Pos.x(player) + (p[0] - Pos.x(player)) * f),
                (int) Math.round(Pos.y(player) + (p[1] - Pos.y(player)) * f),
                (int) Math.round(Pos.z(player) + (p[2] - Pos.z(player)) * f), SEGMENT_RADIUS);
            createPlanner();
            PlanResult r = search(SEGMENT_NANOS);
            if (stale()) return;
            if (r.status() == PlanResult.Status.FOUND) {
                publish(State.PATHING, r.path());
                return;
            }
            segment = null;
        }
        createPlanner();
        replan();
    }

    /** Where a goal is (the nearest of several), or null for goals without a point (y levels, x/z). */
    private int[] goalPoint(Goal g) {
        if (g instanceof GoalBlock b) return new int[]{b.x(), b.y(), b.z()};
        if (g instanceof GoalNear n) return new int[]{n.x(), n.y(), n.z()};
        if (g instanceof dev.lazuli.core.goal.GoalAdjacent a) return new int[]{a.x(), a.y(), a.z()};
        if (g instanceof dev.lazuli.core.goal.GoalAny any) {
            int[] best = null;
            double bd = Double.POSITIVE_INFINITY;
            for (Goal sub : any.goals()) {
                int[] q = goalPoint(sub);
                if (q == null) continue;
                double d = sq(q[0] - Pos.x(player)) + sq(q[1] - Pos.y(player)) + sq(q[2] - Pos.z(player));
                if (d < bd) {
                    bd = d;
                    best = q;
                }
            }
            return best;
        }
        return null;
    }

    private static double sq(double d) {
        return d * d;
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

    /**
     * The node the player stands at: the reported block, or when that isn't one (standing on the edge of a hole just
     * mined, half over a slab...), the nearest node around it. Unchanged if there's none (mid-air: they'll land).
     */
    private long snap(long p) {
        if (graph.isNode(p)) return p;
        int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
        for (int dy : new int[]{0, -1, 1}) {
            if (dy != 0 && graph.isNode(Pos.pack(x, y + dy, z))) return Pos.pack(x, y + dy, z);
        }
        for (int dy : new int[]{0, -1, 1}) {
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
                long q = Pos.pack(x + d[0], y + dy, z + d[1]);
                if (graph.isNode(q)) return q;
            }
        }
        return p;
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
        if (segment != null && Math.sqrt(sq(segment.x() - Pos.x(player)) + sq(segment.y() - Pos.y(player))
            + sq(segment.z() - Pos.z(player))) <= HANDOFF
            && graph.isNode(player)) {
            begin(); // the current path stays published while the next segment is planned
            return;
        }
        if (!graph.isNode(player) && snapshot.state() == State.PATHING && snapshot.trip() == tripId
            && !snapshot.path().isEmpty()) {
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

    private synchronized void publish(State state, List<Long> path) {
        if (stale()) return;
        snapshot = new Snapshot(state, List.copyOf(path), goal, versions.incrementAndGet(), tripId);
        planning = false;
    }
}
