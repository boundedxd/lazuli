package dev.lazuli.core.race;

import dev.lazuli.core.physics.BoatSim;
import dev.lazuli.core.util.LongMap;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drives a boat round an ice track by looking ahead: every tick it plays many key sequences forward in
 * {@link BoatSim} (a beam search: each round, every kept run tries each key combination for a few ticks, and the
 * runs furthest along the track are kept), then presses the first keys of the best one. Because the sim is the game's
 * own physics, it drifts, brakes for hairpins, threads tight gaps and takes drops the way they actually play out,
 * not by rules about how a boat should turn.
 * <p>
 * Runs are ranked by how far they still are from the finish along the track ({@link RaceLine}), where their momentum
 * is taking them (a run about to slam into a wall at full speed ranks low), and runs that leave the track are dropped.
 * The last plan, a tick on, is always tried again, so the line doesn't wobble from one tick to the next.
 * <p>
 * Keys pressed now only reach the boat a tick later (the game hands them over after the boat has moved this tick), so
 * the first imagined tick replays the keys already handed over.
 */
public final class IceRacer {
    /** Keys for one tick: A, D, W, S. */
    public record Keys(boolean left, boolean right, boolean forward, boolean back) {
        public static final Keys NONE = new Keys(false, false, false, false);
    }

    /** What's worth trying: full throttle straight or turning, turning off the throttle, coasting, braking into a turn. */
    static final Keys[] ACTIONS = {
        new Keys(false, false, true, false), new Keys(true, false, true, false), new Keys(false, true, true, false),
        new Keys(true, false, false, false), new Keys(false, true, false, false), Keys.NONE,
        new Keys(true, false, false, true), new Keys(false, true, false, true)};

    /** Ticks each round of the search holds its keys: short first (precise now), longer later (seeing further). */
    private int[] rounds = {1, 2, 2, 3, 3, 4, 4, 5};
    private int beam = 48;
    private long budgetNanos = 25_000_000L;
    /** Ticks ahead the momentum is followed when ranking a run. */
    private static final int MOMENTUM = 6;
    private static final double OFF_TRACK = 1e6;

    private Keys[] plan = new Keys[0];
    private final BlockView world;

    /** @param world the blocks (read through a cache: the search asks about the same few thousand many times over) */
    public IceRacer(BlockView world) {
        this.world = new Cache(world);
    }

    public IceRacer beam(int width) {
        this.beam = width;
        return this;
    }

    public IceRacer budget(long nanos) {
        this.budgetNanos = nanos;
        return this;
    }

    public IceRacer rounds(int... ticks) {
        this.rounds = ticks.clone();
        return this;
    }

    /** Forgets the cached blocks (they changed) and the last plan. */
    public void reset() {
        ((Cache) world).clear();
        plan = new Keys[0];
    }

    /** The view the sims should use (cached). */
    public BlockView view() {
        return world;
    }

    private static final class Node {
        BoatSim sim;
        RaceLine.Cursor cur;
        Keys first;
        double toGo, score;
        /** Ticks from now it crossed the finish (it's done: nothing after counts), or -1. */
        int finishedAt = -1;
        List<Keys> keys;
    }

    /**
     * The keys to press now.
     *
     * @param boat    the boat as it is (a copy is used)
     * @param pending the keys the boat will use next tick (already handed over)
     * @param cursor  where along the line the boat is (not moved)
     */
    public Keys next(BoatSim boat, Keys pending, RaceLine line, RaceLine.Cursor cursor) {
        long now = System.nanoTime();
        long deadline = budgetNanos > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + budgetNanos;
        Node root = new Node();
        root.sim = rebind(boat);
        root.cur = cursor.copy();
        root.sim.step(pending.left(), pending.right(), pending.forward(), pending.back());
        root.toGo = line.toGo(root.cur, root.sim.x, root.sim.y, root.sim.z);
        if (Double.isNaN(root.toGo)) root.toGo = OFF_TRACK;
        root.keys = new ArrayList<>();
        List<Node> frontier = List.of(root);
        Node best = null;
        // the last plan, a tick on, played out in full: the bar to beat
        Node warm = replay(root, line);
        for (int r = 0; r < rounds.length; r++) {
            List<Node> children = new ArrayList<>(frontier.size() * ACTIONS.length);
            for (Node p : frontier) {
                if (p.finishedAt >= 0) {
                    children.add(p); // done: carried along as it is
                    continue;
                }
                for (Keys k : ACTIONS) {
                    Node c = new Node();
                    c.sim = p.sim.copy();
                    c.cur = p.cur.copy();
                    c.first = p.first == null ? k : p.first;
                    c.keys = new ArrayList<>(p.keys);
                    double toGo = Double.NaN;
                    for (int t = 0; t < rounds[r]; t++) {
                        c.sim.step(k.left(), k.right(), k.forward(), k.back());
                        c.keys.add(k);
                        toGo = line.toGo(c.cur, c.sim.x, c.sim.y, c.sim.z);
                        if (Double.isNaN(toGo)) break;
                        if (line.finished(c.cur, toGo)) {
                            c.finishedAt = c.keys.size();
                            break;
                        }
                    }
                    if (Double.isNaN(toGo)) continue; // off the track: dropped
                    c.toGo = toGo;
                    c.score = score(c, line);
                    children.add(c);
                }
                if (System.nanoTime() > deadline && best != null) break;
            }
            if (children.isEmpty()) break;
            children.sort((a, b) -> Double.compare(a.score, b.score));
            frontier = diverse(children);
            best = frontier.get(0);
            if (System.nanoTime() > deadline) break;
        }
        // the last plan wins ties, and wins outright over a search the time ran out on (it sees further)
        if (warm != null && (best == null || warm.score <= best.score + 1e-9
            || best.finishedAt < 0 && warm.finishedAt < 0 && best.keys.size() < warm.keys.size())) best = warm;
        if (best == null || best.first == null) {
            plan = new Keys[0];
            return new Keys(false, false, true, false);
        }
        plan = best.keys.toArray(new Keys[0]);
        return best.first;
    }

    /** The last plan, a tick on (the first key is being used now), played from {@code root}; null if none. */
    private Node replay(Node root, RaceLine line) {
        if (plan.length < 2) return null;
        Node n = new Node();
        n.sim = root.sim.copy();
        n.cur = root.cur.copy();
        n.keys = new ArrayList<>();
        for (int i = 1; i < plan.length; i++) {
            Keys k = plan[i];
            n.sim.step(k.left(), k.right(), k.forward(), k.back());
            n.keys.add(k);
            double toGo = line.toGo(n.cur, n.sim.x, n.sim.y, n.sim.z);
            if (Double.isNaN(toGo)) return null;
            n.toGo = toGo;
            if (line.finished(n.cur, toGo)) {
                n.finishedAt = n.keys.size();
                break;
            }
        }
        n.first = n.keys.get(0);
        if (n.finishedAt >= 0) {
            n.score = score(n, line);
            return n;
        }
        // pad to the full horizon coasting the last key, so it's compared like for like
        int horizon = 0;
        for (int r : rounds) horizon += r;
        Keys last = n.keys.get(n.keys.size() - 1);
        while (n.keys.size() < horizon) {
            n.sim.step(last.left(), last.right(), last.forward(), last.back());
            n.keys.add(last);
            double toGo = line.toGo(n.cur, n.sim.x, n.sim.y, n.sim.z);
            if (Double.isNaN(toGo)) return null;
            n.toGo = toGo;
        }
        n.score = score(n, line);
        return n;
    }

    /**
     * Lower is better: distance still to go where the run ends, judged by where its momentum takes it over the next
     * few ticks (into a wall or off an edge is costly at speed).
     */
    private double score(Node c, RaceLine line) {
        if (c.finishedAt >= 0) return -1e5 + c.finishedAt; // across the line: the sooner the better
        BoatSim s = c.sim.copy();
        RaceLine.Cursor cur = c.cur.copy();
        double toGo = c.toGo, crash = 0;
        for (int t = 0; t < MOMENTUM; t++) {
            double before = s.speed();
            s.step(false, false, false, false);
            double g = line.toGo(cur, s.x, s.y, s.z);
            if (Double.isNaN(g)) return c.toGo + 40 + before * 30 + crash; // heading off the track
            // a crash: speed lost into a wall is time lost
            if (s.horizontalCollision) crash += (before - s.speed()) * 8;
            toGo = g;
        }
        return toGo + crash;
    }

    /** The best runs, a few of each kind: no two ending in nearly the same place facing nearly the same way. */
    private List<Node> diverse(List<Node> sorted) {
        List<Node> out = new ArrayList<>(beam);
        Set<Long> seen = new HashSet<>();
        for (Node c : sorted) {
            long key = Pos.pack((int) Math.floor(c.sim.x * 2), (int) Math.floor(c.sim.y), (int) Math.floor(c.sim.z * 2)) * 31
                + Math.floorMod((int) Math.floor(c.sim.yaw / 12), 30) * 7 + Math.min(6, (int) (c.sim.speed() * 2));
            if (!seen.add(key)) continue;
            out.add(c);
            if (out.size() >= beam) break;
        }
        return out;
    }

    private BoatSim rebind(BoatSim b) {
        BoatSim s = new BoatSim(world, b.x, b.y, b.z, b.vx, b.vy, b.vz, b.yaw, b.spin);
        s.status = b.status;
        s.oldStatus = b.oldStatus;
        return s;
    }

    /** Remembers each block asked about. */
    static final class Cache implements BlockView {
        private final BlockView w;
        private final LongMap<BlockPhysics> phys = new LongMap<>(1 << 14);
        private final LongMap<BlockKind> kinds = new LongMap<>(1 << 12);

        Cache(BlockView w) {
            this.w = w;
        }

        void clear() {
            phys.clear();
            kinds.clear();
        }

        @Override
        public BlockKind get(int x, int y, int z) {
            long k = Pos.pack(x, y, z);
            BlockKind v = kinds.get(k);
            if (v == null) {
                v = w.get(x, y, z);
                kinds.put(k, v);
            }
            return v;
        }

        @Override
        public BlockPhysics physics(int x, int y, int z) {
            long k = Pos.pack(x, y, z);
            BlockPhysics v = phys.get(k);
            if (v == null) {
                v = w.physics(x, y, z);
                phys.put(k, v);
            }
            return v;
        }
    }

    static String describe(Keys[] keys) {
        return Arrays.toString(keys);
    }
}
