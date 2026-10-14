package dev.lazuli.core.control;

import dev.lazuli.core.move.JumpEvaluator;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Hard jumps (long gaps, gaps up a level) aren't left to the input planner, whose short horizon can commit to a
 * take-off that comes up short. Their edge was proven by {@link JumpEvaluator} from standing still at the take-off
 * block's center, so that's what happens: line up there, standing still and facing the jump, check the script still
 * lands from exactly here, then play it tick for tick.
 */
public final class Jumper {
    /** Center distance (blocks) from which a gap jump is hard; going up, a shorter one already is. */
    private static final double HARD = 4.0, HARD_UP = 3.0;
    /** Facing the jump: yaw this close (degrees). */
    private static final float AIMED = 0.5f;
    /** Lining up sneaks this close to the center (blocks). */
    private static final double FINE = 0.3;
    private static final float PITCH = 10f;

    private final BlockView world;
    private long edgeU = Long.MIN_VALUE, edgeV = Long.MIN_VALUE;
    private JumpEvaluator.Script script;
    private boolean sprint;
    /** Ticks into the script, -1 while lining up. */
    private int t = -1;
    /** Air ticks so far (scripts count braking from leaving the ground), and whether it has left the ground. */
    private int air;
    private boolean flew;
    /** The yaw played last tick (a neo turns in the air). */
    private float facing;
    /** Ticks spent lining up; past {@link #PREP_LIMIT} the input planner gets the jump after all. */
    private int prep;
    private static final int PREP_LIMIT = 60;

    public Jumper(BlockView world) {
        this.world = world;
    }

    public void reset() {
        edgeU = edgeV = Long.MIN_VALUE;
        script = null;
        t = -1;
    }

    /** Whether u -> v is a jump the input planner shouldn't be trusted with: long, up a level, or a neo. */
    public static boolean hard(BlockView world, long u, long v) {
        int dx = Pos.x(v) - Pos.x(u), dz = Pos.z(v) - Pos.z(u);
        if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) return false;
        double d = Math.hypot(dx, dz);
        return d >= HARD - 1e-9 || Pos.y(v) > Pos.y(u) && d >= HARD_UP - 1e-9 || JumpEvaluator.neo(world, u, v);
    }

    /** Controls for a hard jump starting at the player's node, or null if the next edge isn't one (or it landed). */
    public Controls tick(PlayerState p, List<Long> path, int idx, boolean allowSprint) {
        if (t >= 0 && script != null) return play(p);
        if (idx < 0 || idx + 1 >= path.size()) return null;
        long u = path.get(idx), v = path.get(idx + 1);
        if (!hard(world, u, v)) {
            reset();
            return null;
        }
        if (u != edgeU || v != edgeV || sprint != allowSprint) {
            edgeU = u;
            edgeV = v;
            sprint = allowSprint;
            script = JumpEvaluator.script(world, u, feet(u), v, feet(v), allowSprint);
            t = -1;
            prep = 0;
        }
        if (script == null || !p.onGround() || ++prep > PREP_LIMIT) return null;
        if (Math.abs(RotationController.wrap(p.yaw() - script.yaw())) < AIMED) {
            // go as soon as some take-off tick lands from right here, as the player is (no need to stop first)
            JumpEvaluator.Script go = fromHere(p);
            if (go != null) {
                script = go;
                facing = go.yaw();
                t = 0;
                air = 0;
                flew = false;
                return play(p);
            }
        }
        // line up at the center, where the script was proven; sneaking near it, for small enough steps to stop there
        double d = Math.hypot(p.x() - (Pos.x(u) + 0.5), p.z() - (Pos.z(u) + 0.5));
        return Climber.seek(p, Pos.x(u), Pos.z(u), new int[]{0, 0}, script.yaw(), PITCH, d < FINE);
    }

    /** The script, or the same with an earlier take-off, that lands on the target from the player's state; or null. */
    private JumpEvaluator.Script fromHere(PlayerState p) {
        if (lands(p, script)) return script;
        if (script.jumpTick() < 0) return null;
        for (int jt = script.jumpTick() - 1; jt >= 0; jt--) {
            JumpEvaluator.Script s = script.withJumpTick(jt);
            if (lands(p, s)) return s;
        }
        return null;
    }

    private Controls play(PlayerState p) {
        JumpEvaluator.Script s = script;
        if (!p.onGround()) flew = true;
        if (flew && p.onGround()) {
            boolean onV = Math.floor(p.x()) == Pos.x(edgeV) && Math.floor(p.z()) == Pos.z(edgeV);
            if (s.jumpTick() != JumpEvaluator.HOP || onV) {
                reset(); // landed: the tracker and the edge watch take it from here
                return null;
            }
            flew = false; // a hop on the run-up
        }
        int[] in = input(s, t, air, p.onGround());
        facing = facing(s, facing, t, air, p.x(), p.z());
        int strafe = s.strafe(airIndex(s, t, air));
        if (flew || !p.onGround()) air++;
        t++;
        return new Controls(in[0] > 0, in[0] < 0, in[1] == 1, in[0] > 0 && sprint, facing, PITCH, strafe, false);
    }

    /** The yaw for script tick {@code t} ({@code air} ticks in the air), as {@link JumpEvaluator} played it. */
    private float facing(JumpEvaluator.Script s, float prev, int t, int air, double x, double z) {
        int a = airIndex(s, t, air);
        return a >= 0 ? JumpEvaluator.yaw(s, prev, a, x, z, edgeV) : s.yaw();
    }

    /** Which air tick of the script tick {@code t} is, -1 before take-off (or hopping). */
    private static int airIndex(JumpEvaluator.Script s, int t, int air) {
        return s.jumpTick() >= 0 ? t - s.jumpTick() - 1 : s.jumpTick() == JumpEvaluator.HOP ? -1 : air;
    }

    /** {forward, jump} for script tick {@code t}, {@code air} ticks after leaving the ground. */
    private static int[] input(JumpEvaluator.Script s, int t, int air, boolean onGround) {
        if (s.jumpTick() == JumpEvaluator.HOP) return new int[]{1, 1};
        if (s.jumpTick() < 0) {
            // run off the edge; braking counts from the first tick in the air
            if (onGround && air == 0) return new int[]{1, 0};
            return new int[]{air >= s.brakeAt() ? s.after() : 1, 0};
        }
        if (t < s.jumpTick()) return new int[]{1, 0};
        if (t == s.jumpTick()) return new int[]{1, 1};
        int a = t - s.jumpTick() - 1;
        return new int[]{a >= s.brakeAt() ? s.after() : 1, 0};
    }

    /** Playing {@code script} from the player's actual state lands on the target. */
    private boolean lands(PlayerState p, JumpEvaluator.Script script) {
        PlayerSim s = p.sim(world);
        long v = edgeV;
        double fv = feet(v);
        int air = 0;
        boolean flew = false;
        float yaw = script.yaw();
        for (int k = 0; k < 120; k++) {
            int[] in = input(script, k, air, s.onGround);
            yaw = facing(script, yaw, k, air, s.x, s.z);
            s.step(in[0], script.strafe(airIndex(script, k, air)), in[1] == 1, in[0] > 0 && sprint, false, yaw);
            if (!s.onGround) {
                flew = true;
                air++;
                if (s.y < fv - 1) return false;
                continue;
            }
            if (!flew) continue;
            boolean onV = Math.abs(s.y - fv) < 1e-6 && Math.floor(s.x) == Pos.x(v) && Math.floor(s.z) == Pos.z(v);
            if (onV) return true;
            if (script.jumpTick() != JumpEvaluator.HOP) return false;
            flew = false; // a hop on the run-up
        }
        return false;
    }

    private double feet(long n) {
        return Standing.feet(world, Pos.x(n), Pos.y(n), Pos.z(n));
    }
}
