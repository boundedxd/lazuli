package dev.lazuli.core.control;

import dev.lazuli.core.move.Standing;
import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Local input search (model-predictive control). Each tick it beam-searches input sequences (W, W+A, W+D,
 * none, S, jump timing, yaw) through {@link PlayerSim}, scores them by progress along the planner's path, and returns
 * the first input of the best sequence that can still land and come to a stop safely. The path is a corridor,
 * not a track: any block within one of a path node at the same height is fair game, so it cuts corners,
 * sprint-jumps flats, brakes in the air and times jumps on its own.
 */
public final class InputPlanner {
    private static final int[] SEGMENTS = {1, 2, 3, 4, 5, 6};
    private static final int WINDOW = 28;
    private static final int BEAM = 16;
    private static final int LOOKAHEAD = 16;
    private static final int SAFE_TICKS = 60;
    private static final float PITCH = 10f;
    private static final double MOMENTUM_WEIGHT = 3.0;
    private static final double TURN_WEIGHT = 0.004;
    private static final double LATERAL_WEIGHT = 0.6;
    /** Per segment spent looking off the aim lines: corrections come from strafing, like a player would. */
    private static final double OFF_AIM_PENALTY = 0.03;
    /** {forward, strafe} combinations searched. */
    private static final int[][] ACTIONS = {{1, 0}, {1, 1}, {1, -1}, {0, 0}, {-1, 0}};

    private final dev.lazuli.core.world.LocalCache world;
    /** Longest safe fall (blocks); a landing that falls further is never planned, unless it's into water. */
    private int maxFall = 3;

    public void setMaxFall(int blocks) {
        maxFall = blocks;
    }

    public InputPlanner(BlockView world) {
        this.world = new dev.lazuli.core.world.LocalCache(world);
    }

    private static final class Cand {
        final PlayerSim s;
        float yaw;
        Cand parent;
        int fwd, strafe, len;
        boolean jump;
        double offAim;
        float segYaw;
        double score;
        int cont;

        Cand(PlayerSim s, float yaw) {
            this.s = s;
            this.yaw = yaw;
        }

        Cand first() {
            Cand c = this;
            while (c.parent != null && c.parent.parent != null) c = c.parent;
            return c;
        }
    }

    /** The chosen input sequence, one entry per tick, kept to warm-start the next tick's search. */
    private int[] warmFwd = new int[0];
    private int[] warmStrafe = new int[0];
    private boolean[] warmJump = new boolean[0];
    private float[] warmYaw = new float[0];
    private int warmCont;

    public void reset() {
        warmFwd = new int[0];
        warmStrafe = new int[0];
        warmJump = new boolean[0];
        warmYaw = new float[0];
        predicted = new double[0];
    }

    /** Path window as a polyline of node centers with cumulative length. */
    private static final class Corridor {
        final long[] nodes;
        /** Nodes plus the run-up blocks of gap jumps: everywhere it may stand. */
        final long[] cells;
        final double[] cx, cz, cum;
        final int[] y;
        final boolean includesEnd;
        final int minY;

        /** Each cell's standing level: its feet height rounded (water and air cells: their block y). */
        final long[] levels;

        Corridor(BlockView world, List<Long> path, int from) {
            int start = Math.max(0, from - 2);
            int end = Math.min(path.size(), from + WINDOW);
            int n = end - start;
            nodes = new long[n];
            cx = new double[n];
            cz = new double[n];
            cum = new double[n];
            y = new int[n];
            int lo = Integer.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                long p = path.get(start + i);
                nodes[i] = p;
                cx[i] = Pos.x(p) + 0.5;
                cz[i] = Pos.z(p) + 0.5;
                y[i] = Pos.y(p);
                lo = Math.min(lo, y[i]);
                if (i > 0) cum[i] = cum[i - 1] + Math.hypot(cx[i] - cx[i - 1], cz[i] - cz[i - 1]);
            }
            minY = lo;
            includesEnd = end == path.size();
            List<Long> all = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                all.add(nodes[i]);
                if (i + 1 < n) {
                    int dx = Pos.x(nodes[i + 1]) - Pos.x(nodes[i]), dz = Pos.z(nodes[i + 1]) - Pos.z(nodes[i]);
                    if (Math.max(Math.abs(dx), Math.abs(dz)) >= 2) {
                        // a jump: every block its straight line crosses may be run-up
                        double len = Math.hypot(dx, dz);
                        for (double t = 0.25; t < len; t += 0.25) {
                            long cell = Pos.pack((int) Math.floor(cx[i] + dx * t / len), y[i],
                                (int) Math.floor(cz[i] + dz * t / len));
                            if (!all.contains(cell)) all.add(cell);
                        }
                    }
                }
            }
            cells = new long[all.size()];
            levels = new long[all.size()];
            for (int i = 0; i < cells.length; i++) {
                cells[i] = all.get(i);
                double f = Standing.feet(world, Pos.x(cells[i]), Pos.y(cells[i]), Pos.z(cells[i]));
                levels[i] = Math.round(Double.isNaN(f) ? Pos.y(cells[i]) : f);
            }
        }

        /**
         * Whether a player whose feet are at {@code feetY} in column (bx, bz) is beside a corridor cell at that height.
         * Heights compare by level (feet rounded), so a dirt path (15/16 high) or a slab next to full blocks still
         * counts as the same walk.
         */
        boolean contains(int bx, double feetY, int bz) {
            long level = Math.round(feetY);
            for (int i = 0; i < cells.length; i++) {
                long n = cells[i];
                if (levels[i] == level && Math.abs(Pos.x(n) - bx) <= 1 && Math.abs(Pos.z(n) - bz) <= 1) return true;
            }
            return false;
        }
    }

    /**
     * @param from    index of the player's node in {@code path}
     * @param maxTurn yaw change per tick the executor applies (degrees)
     * @return the input for the next tick, or null if no safe sequence was found
     */
    public Controls plan(PlayerState p, List<Long> path, int from, boolean allowSprint, float maxTurn) {
        if (path.size() < 2 || from >= path.size() - 1 && atEnd(p, path)) {
            reset();
            return null;
        }
        world.recenter((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
        Corridor c = new Corridor(world, path, from);
        float[] yaws = yawOptions(p, path, from);
        int aimed = aimedCount;
        PlayerSim root = p.sim(world);

        List<Cand> beam = new ArrayList<>();
        beam.add(new Cand(root, p.yaw()));
        List<List<Cand>> levels = new ArrayList<>();
        for (int depth = 0; depth < SEGMENTS.length; depth++) {
            int len = SEGMENTS[depth];
            List<Cand> nextLevel = new ArrayList<>();
            for (Cand parent : beam) {
                for (int[] a : ACTIONS) {
                    int fwd = a[0], strafe = a[1];
                    for (int jumpI = 0; jumpI < 2; jumpI++) {
                        boolean jump = jumpI == 1;
                        for (int yi = 0; yi < yaws.length; yi++) {
                            float segYaw = yaws[yi];
                            Cand ch = new Cand(parent.s.copy(), parent.yaw);
                            if (!rollout(ch, fwd, strafe, jump, segYaw, len, allowSprint, maxTurn, c)) continue;
                            ch.parent = parent;
                            ch.fwd = fwd;
                            ch.strafe = strafe;
                            ch.jump = jump;
                            ch.segYaw = segYaw;
                            ch.len = len;
                            ch.offAim = parent.offAim + (yi < aimed ? 0 : OFF_AIM_PENALTY);
                            ch.score = score(ch.s, c) - turnPenalty(ch.first().segYaw, p.yaw()) - ch.offAim;
                            nextLevel.add(ch);
                        }
                    }
                }
            }
            if (nextLevel.isEmpty()) break;
            nextLevel.sort(Comparator.comparingDouble((Cand x) -> -x.score));
            beam = new ArrayList<>(nextLevel.subList(0, Math.min(BEAM, nextLevel.size())));
            levels.add(beam);
        }
        Cand best = null;
        this.maxTurn = maxTurn;
        for (int lvl = levels.size() - 1; lvl >= 0 && best == null; lvl--) {
            for (Cand cand : levels.get(lvl)) {
                int cont = safe(cand, c, allowSprint);
                if (cont != UNSAFE) {
                    cand.cont = cont;
                    best = cand;
                    break;
                }
            }
        }
        // last tick's plan, shifted by one tick: if it was safe it still is, so a plan never just vanishes
        Cand warm = warm(root, p, c, allowSprint, maxTurn);
        if (warm != null && (best == null || warm.score >= best.score - 0.1)) {
            return remember(warm, root, p.yaw(), c, allowSprint);
        }
        if (best == null) {
            reset();
            return root.onGround ? null : recover(root, p, c, allowSprint, maxTurn);
        }
        return remember(best, root, p.yaw(), c, allowSprint);
    }

    /**
     * Airborne with no plan that stays on the path (it was replanned mid-jump, or the jump went wrong): steer for the
     * landing nearest the path that is still safe, on firm ground no further down than a safe fall, anywhere.
     */
    private Controls recover(PlayerSim root, PlayerState p, Corridor c, boolean allowSprint, float maxTurn) {
        Controls bestC = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 16; k++) {
            float yaw = p.yaw() + k * 22.5f;
            for (int fwd = -1; fwd <= 1; fwd++) {
                PlayerSim s = root.copy();
                float y = p.yaw();
                boolean ok = false;
                for (int t = 0; t < SAFE_TICKS; t++) {
                    y = approach(y, yaw, maxTurn);
                    s.step(fwd, 0, false, fwd > 0 && allowSprint, y);
                    if (s.inWater) {
                        ok = true;
                        break;
                    }
                    if (s.onGround) {
                        int bx = (int) Math.floor(s.x), by = (int) Math.floor(s.y + 1e-6), bz = (int) Math.floor(s.z);
                        ok = firm(s) && s.fallen() <= maxFall + 0.05 && world.get(bx, by, bz) != BlockKind.DANGER
                            && world.get(bx, by - 1, bz) != BlockKind.DANGER;
                        break;
                    }
                }
                if (!ok) continue;
                double sc = score(s, c);
                if (sc > bestScore) {
                    bestScore = sc;
                    bestC = new Controls(fwd > 0, fwd < 0, false, fwd > 0 && allowSprint, yaw, PITCH);
                }
            }
        }
        return bestC != null ? bestC : airBrake(p);
    }

    private boolean rollout(Cand ch, int fwd, int strafe, boolean jump, float segYaw, int len, boolean allowSprint,
                            float maxTurn, Corridor c) {
        boolean sprint = fwd > 0 && allowSprint;
        for (int k = 0; k < len; k++) {
            ch.yaw = approach(ch.yaw, segYaw, maxTurn);
            boolean air = !ch.s.onGround;
            ch.s.step(fwd, strafe, jump, sprint, ch.yaw); // held, like the key: re-jumps on landing
            if (!alive(ch.s, c, air)) return false;
        }
        return true;
    }

    private static double turnPenalty(float yaw0, float yaw) {
        return TURN_WEIGHT * Math.abs(RotationController.wrap(yaw0 - yaw));
    }

    private Cand warm(PlayerSim root, PlayerState p, Corridor c, boolean allowSprint, float maxTurn) {
        int n = warmFwd.length;
        if (n < 2) return null;
        Cand cur = new Cand(root.copy(), p.yaw());
        for (int i = 1; i <= n; i++) {
            // shift by one; the new last tick follows the continuation that proved the old plan safe
            boolean extra = i == n;
            boolean pursue = extra && warmCont == PURSUE;
            int fwd = pursue ? 1 : extra ? (cur.s.onGround ? 0 : warmCont) : warmFwd[i];
            int strafe = extra ? 0 : warmStrafe[i];
            boolean jump = pursue || !extra && warmJump[i];
            float yaw = pursue ? pursuitYaw(cur.s, c) : extra ? cur.yaw : warmYaw[i];
            Cand ch = new Cand(cur.s, cur.yaw);
            if (!rollout(ch, fwd, strafe, jump, yaw, 1, allowSprint, maxTurn, c)) return null;
            ch.parent = cur;
            ch.fwd = fwd;
            ch.strafe = strafe;
            ch.jump = jump;
            ch.segYaw = yaw;
            ch.len = 1;
            cur = ch;
        }
        int cont = safe(cur, c, allowSprint);
        if (cont == UNSAFE) return null;
        cur.cont = cont;
        cur.score = score(cur.s, c) - turnPenalty(cur.first().segYaw, p.yaw());
        return cur;
    }

    /** Where the chosen plan takes the player, as x, y, z triples (for rendering); empty when there is none. */
    private volatile double[] predicted = new double[0];
    private static final int PREDICT_CONTINUATION = 20;

    public double[] predicted() {
        return predicted;
    }

    private Controls remember(Cand best, PlayerSim root, float yaw0, Corridor c, boolean allowSprint) {
        List<Cand> chain = new ArrayList<>();
        for (Cand x = best; x.parent != null; x = x.parent) chain.add(0, x);
        int total = 0;
        for (Cand x : chain) total += x.len;
        warmFwd = new int[total];
        warmStrafe = new int[total];
        warmJump = new boolean[total];
        warmYaw = new float[total];
        warmCont = best.cont;
        int t = 0;
        for (Cand x : chain) {
            for (int k = 0; k < x.len; k++, t++) {
                warmFwd[t] = x.fwd;
                warmStrafe[t] = x.strafe;
                warmJump[t] = x.jump;
                warmYaw[t] = x.segYaw;
            }
        }
        predict(root, yaw0, c, allowSprint);
        Cand f = chain.get(0);
        return new Controls(f.fwd > 0, f.fwd < 0, f.jump, f.fwd > 0 && allowSprint, f.segYaw, PITCH, f.strafe);
    }

    private void predict(PlayerSim root, float yaw0, Corridor c, boolean allowSprint) {
        PlayerSim s = root.copy();
        float yaw = yaw0;
        int n = warmFwd.length;
        double[] out = new double[(n + PREDICT_CONTINUATION + 1) * 3];
        out[0] = s.x;
        out[1] = s.y;
        out[2] = s.z;
        for (int t = 0; t < n + PREDICT_CONTINUATION; t++) {
            if (t < n) {
                yaw = approach(yaw, warmYaw[t], maxTurn);
                s.step(warmFwd[t], warmStrafe[t], warmJump[t], warmFwd[t] > 0 && allowSprint, yaw);
            } else if (warmCont == PURSUE) {
                yaw = approach(yaw, pursuitYaw(s, c), maxTurn);
                s.step(1, 0, true, true, yaw);
            } else {
                int fwd = s.onGround ? 0 : warmCont;
                s.step(fwd, 0, false, fwd > 0 && allowSprint, yaw);
            }
            out[(t + 1) * 3] = s.x;
            out[(t + 1) * 3 + 1] = s.y;
            out[(t + 1) * 3 + 2] = s.z;
        }
        predicted = out;
    }

    private static boolean atEnd(PlayerState p, List<Long> path) {
        long e = path.get(path.size() - 1);
        return Math.hypot(Pos.x(e) + 0.5 - p.x(), Pos.z(e) + 0.5 - p.z()) < 0.25 && p.onGround();
    }

    /** How far inside a block's edge a landing must be: real-game error of a few hundredths can't turn it into a miss. */
    private static final double LANDING_MARGIN = 0.06;

    /**
     * Still on track: standing in the corridor away from danger, or in the air above its lowest level. A landing
     * (this tick, after being in the air) must be firm: supported even with the hitbox shrunk by
     * {@link #LANDING_MARGIN}.
     */
    private boolean alive(PlayerSim s, Corridor c, boolean wasInAir) {
        if (s.onGround && wasInAir && (!firm(s) || s.fallen() > maxFall + 0.05)) return false;
        if (s.onGround) {
            int bx = (int) Math.floor(s.x), by = (int) Math.floor(s.y + 1e-6), bz = (int) Math.floor(s.z);
            if (!c.contains(bx, s.y, bz)) return false;
            return world.get(bx, by, bz) != BlockKind.DANGER && world.get(bx, by - 1, bz) != BlockKind.DANGER;
        }
        if (s.inWater) return s.y >= c.minY - 1 - Standing.SWIM_DEPTH; // a drop into water sinks deep first
        return s.y >= c.minY - 0.6;
    }

    private boolean firm(PlayerSim s) {
        double hw = 0.3 - LANDING_MARGIN;
        int by = (int) Math.floor(s.y - 1e-3);
        for (int bx = (int) Math.floor(s.x - hw); bx <= (int) Math.floor(s.x + hw); bx++) {
            for (int bz = (int) Math.floor(s.z - hw); bz <= (int) Math.floor(s.z + hw); bz++) {
                double[] b = world.physics(bx, by, bz).boxes;
                for (int i = 0; i < b.length; i += 6) {
                    if (Math.abs(by + b[i + 4] - s.y) < 1e-3 && bx + b[i + 3] > s.x - hw && bx + b[i] < s.x + hw
                        && bz + b[i + 5] > s.z - hw && bz + b[i + 2] < s.z + hw) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final int UNSAFE = Integer.MIN_VALUE;
    /** Continuation: keep sprint-jumping along the path, as a player chaining jumps does. */
    private static final int PURSUE = 2;
    private float maxTurn = 45f;

    /**
     * The continuation that keeps the player in the corridor: holding nothing, S or W in the air (nothing on the
     * ground) until at rest, or {@link #PURSUE}; {@link #UNSAFE} if none does. Chains of jumps between blocks too
     * small to stop on are only safe by carrying on.
     */
    private int safe(Cand cand, Corridor c, boolean allowSprint) {
        for (int fwd : new int[]{0, -1, 1}) {
            if (rests(cand, c, fwd, allowSprint)) return fwd;
        }
        if (allowSprint && pursues(cand, c)) return PURSUE;
        return UNSAFE;
    }

    /** Sprint-jumping toward the path's next node stays in the corridor until it leaves the window or 3 s pass. */
    private boolean pursues(Cand cand, Corridor c) {
        PlayerSim s = cand.s.copy();
        float yaw = cand.yaw;
        double total = c.cum[c.nodes.length - 1];
        for (int t = 0; t < SAFE_TICKS; t++) {
            yaw = approach(yaw, pursuitYaw(s, c), maxTurn);
            boolean air = !s.onGround;
            s.step(1, 0, true, true, yaw);
            if (!alive(s, c, air)) return false;
            if (s.onGround && !c.includesEnd && progress(s, c) >= total - 1.5) return true;
        }
        return true;
    }

    /** Yaw toward the first path node more than half a block ahead of the player's progress. */
    private static float pursuitYaw(PlayerSim s, Corridor c) {
        double prog = progress(s, c);
        int n = c.nodes.length;
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            if (c.cum[i] > prog + 0.5) {
                j = i;
                break;
            }
        }
        return PathFollower.yawTo(s.x, s.z, c.cx[j], c.cz[j]);
    }

    /** Distance along the corridor polyline of the point nearest the player. */
    private static double progress(PlayerSim s, Corridor c) {
        double bestD = Double.POSITIVE_INFINITY, prog = 0;
        for (int i = 0; i + 1 < c.nodes.length; i++) {
            double ax = c.cx[i], az = c.cz[i], dx = c.cx[i + 1] - ax, dz = c.cz[i + 1] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((s.x - ax) * dx + (s.z - az) * dz) / len2));
            double d = Math.hypot(s.x - (ax + dx * t), s.z - (az + dz * t));
            if (d < bestD) {
                bestD = d;
                prog = c.cum[i] + t * Math.sqrt(len2);
            }
        }
        return prog;
    }

    private boolean rests(Cand cand, Corridor c, int airFwd, boolean allowSprint) {
        PlayerSim s = cand.s.copy();
        for (int t = 0; t < SAFE_TICKS; t++) {
            if (s.onGround && Math.hypot(s.vx, s.vz) < 0.01) return true;
            int fwd = s.onGround ? 0 : airFwd;
            boolean air = !s.onGround;
            s.step(fwd, false, fwd > 0 && allowSprint, cand.yaw);
            if (!alive(s, c, air)) return false;
        }
        return false;
    }

    private static double score(PlayerSim s, Corridor c) {
        double bestD = Double.POSITIVE_INFINITY, prog = 0, dirX = 0, dirZ = 0;
        int n = c.nodes.length;
        for (int i = 0; i + 1 < n; i++) {
            double ax = c.cx[i], az = c.cz[i], dx = c.cx[i + 1] - ax, dz = c.cz[i + 1] - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-9 ? 0 : Math.max(0, Math.min(1, ((s.x - ax) * dx + (s.z - az) * dz) / len2));
            double d = Math.hypot(s.x - (ax + dx * t), s.z - (az + dz * t));
            if (s.y < Math.min(c.y[i], c.y[i + 1]) - 0.5) d += 3;
            if (d < bestD) {
                bestD = d;
                double len = Math.sqrt(len2);
                prog = c.cum[i] + t * len;
                dirX = len > 0 ? dx / len : 0;
                dirZ = len > 0 ? dz / len : 0;
            }
        }
        if (c.includesEnd) {
            double total = c.cum[n - 1];
            double dEnd = Math.hypot(s.x - c.cx[n - 1], s.z - c.cz[n - 1]);
            if (prog > total - 2.0) {
                return total + 2 - 1.5 * dEnd - Math.abs(s.y - c.y[n - 1]);
            }
        }
        // velocity as next tick's movement (undo this tick's drag) so ground and air speeds compare fairly
        double drag = s.onGround ? 0.546 : 0.91;
        return prog - LATERAL_WEIGHT * bestD + MOMENTUM_WEIGHT * (s.vx * dirX + s.vz * dirZ) / drag;
    }

    /** The first this many of the last {@link #yawOptions} look along the path; the rest are small offsets. */
    private int aimedCount;

    private float[] yawOptions(PlayerState p, List<Long> path, int from) {
        List<Float> out = new ArrayList<>();
        long aim = path.get(lookahead(p, path, from));
        float base = PathFollower.yawTo(p.x(), p.z(), Pos.x(aim) + 0.5, Pos.z(aim) + 0.5);
        add(out, base);
        for (int k = 1; k <= 3 && from + k < path.size(); k++) {
            long n = path.get(from + k);
            add(out, PathFollower.yawTo(p.x(), p.z(), Pos.x(n) + 0.5, Pos.z(n) + 0.5));
        }
        aimedCount = out.size();
        add(out, base + 12f);
        add(out, base - 12f);
        float[] a = new float[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    private static void add(List<Float> out, float yaw) {
        for (float f : out) {
            if (Math.abs(RotationController.wrap(f - yaw)) < 3f) return;
        }
        out.add(yaw);
    }

    private int lookahead(PlayerState p, List<Long> path, int from) {
        int y = Pos.y(path.get(from));
        int best = Math.min(from + 1, path.size() - 1);
        for (int j = from + 1; j < Math.min(path.size(), from + LOOKAHEAD + 1); j++) {
            long n = path.get(j), prev = path.get(j - 1);
            if (Pos.y(n) != y) break;
            if (Math.max(Math.abs(Pos.x(n) - Pos.x(prev)), Math.abs(Pos.z(n) - Pos.z(prev))) >= 2) break;
            if (clearLine(p.x(), p.z(), Pos.x(n) + 0.5, Pos.z(n) + 0.5, y)) best = j;
        }
        return best;
    }

    private boolean clearLine(double x0, double z0, double x1, double z1, int y) {
        double dx = x1 - x0, dz = z1 - z0;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        double ux = dx / len, uz = dz / len, px = -uz * 0.3, pz = ux * 0.3;
        int steps = (int) Math.ceil(len / 0.25);
        for (int i = 0; i <= steps; i++) {
            double t = Math.min(len, i * 0.25);
            double cx = x0 + ux * t, cz = z0 + uz * t;
            for (int side = -1; side <= 1; side++) {
                int bx = (int) Math.floor(cx + px * side), bz = (int) Math.floor(cz + pz * side);
                if (!Standing.walkableAt(world, bx, y, bz)) return false;
            }
        }
        return true;
    }

    /** Turns {@code yaw} toward {@code target} by at most {@code maxTurn} degrees. */
    public static float approach(float yaw, float target, float maxTurn) {
        float d = RotationController.wrap(target - yaw);
        return yaw + Math.max(-maxTurn, Math.min(maxTurn, d));
    }

    /**
     * In the air with no safe plan: face the way the player is moving and hold S, so the landing comes as short as it
     * can. Steering for the path instead is how a missed jump turns into a fall off the side.
     */
    public static Controls airBrake(PlayerState p) {
        if (Math.hypot(p.vx(), p.vz()) < 1e-3) return new Controls(false, false, false, p.yaw(), PITCH);
        float yaw = PathFollower.yawTo(0, 0, p.vx(), p.vz());
        return new Controls(false, true, false, false, yaw, PITCH);
    }
}
