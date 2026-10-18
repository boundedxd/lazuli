package dev.lazuli.core.control;

import dev.lazuli.core.move.Digging;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.physics.Raycast;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Carries out the breaking and placing an edge needs (see {@link Digging#actions}) while the player stands at its
 * start: mines, bridges by placing against the side of the floor, pillars by jumping and placing underneath. Movement
 * stops (sneaking, so nothing walks off an edge) until the edge can be walked. The game and the tests drive the same
 * logic through {@link Hands}.
 */
public final class Builder {
    /** What the player can do to the world. */
    public interface Hands {
        /** Selects a block to place; false if the hotbar has none. */
        boolean selectThrowaway();

        /** Places a block at {@code pos} against the face of {@code against} that touches it. */
        void place(long pos, long against);

        /** Keeps breaking {@code pos} this tick with the best tool. */
        void mine(long pos);

        /** Whether the game lets this block be broken. */
        boolean breakable(long pos);

        /** No blocks left to place: the trip has to be planned without placing. */
        void outOfBlocks();

        /** Eye height above the feet right now (lower while sneaking). */
        double eyeHeight();

        /** Edge u->v needs a block the player can't get the crosshair on: plan another way. */
        default void stuck(long u, long v) {}

        /** Edge u->v can't be done at all (it needs a block and there are none): never plan it again. */
        default void impossible(long u, long v) {
            stuck(u, v);
        }
    }

    private static final long NONE = Long.MIN_VALUE;
    /** How far from the middle of its block the player may start a pillar or a dig straight down. */
    static final double CENTER = 0.15;
    /** Horizontal speed (blocks/tick) below which a pillar jump stays over its block: air drag only takes 9% a tick. */
    static final double STILL_SPEED = 0.02;
    private static final int PILLAR_TIMEOUT = 40;
    /** Pitch (degrees down) a pillar jump waits for, so the block below is in the crosshair as the feet clear it. */
    static final float PILLAR_PITCH = 85f;
    /** Looking down while backing up to a bridge edge, before the face is in front of the eye. */
    static final float BRIDGE_PITCH = 80f;
    /** Ticks an action may wait without the crosshair on it before its edge counts as impossible. */
    static final int BLIND_TICKS = 100;
    private static final double WALK_DEAD = 0.05;
    static final double EYE = 1.62;
    static final double SNEAK_EYE = 1.27;

    private final BlockView world;
    private final Hands hands;
    private long pillar = NONE;
    private int pillarTicks;
    private boolean pillarLeftGround;
    private long pendingPos = NONE;
    private boolean pendingPlace;
    private int blind;
    /** The edge that wanted a block with none to place, and for how many ticks since. */
    private long outU = NONE, outV = NONE;
    private int outTicks;
    /** Ticks a re-plan without placing gets before the edge still asking for a block is ruled out. */
    static final int OUT_WAIT = 40;

    public Builder(BlockView world, Hands hands) {
        this.world = world;
        this.hands = hands;
    }

    /**
     * Eagle bridging: back up to the edge without sneaking and only crouch there, to place (faster). Off, it sneaks
     * the whole way (ninja bridging: slower, but the edge always holds).
     */
    private boolean eagle;
    /** Crouch while standing still breaking a block (mineSneak); off: stand normally. */
    private boolean mineSneak;

    public void setMineSneak(boolean on) {
        mineSneak = on;
    }

    public void setEagle(boolean on) {
        eagle = on;
    }

    /**
     * Eagle bridging crouches once the body would hang this far (blocks) past the edge within {@link #EAGLE_LEAD}
     * ticks at the current speed; the edge holds a sneaking player until 0.3 past.
     */
    static final double EAGLE_EDGE = 0.1;
    static final int EAGLE_LEAD = 2;

    public void reset() {
        pillar = NONE;
        pendingPos = NONE;
        blind = 0;
        outU = outV = NONE;
    }

    /**
     * Controls for this tick while breaking or placing, or null when the player should just move along the path (up to
     * {@link #walkableEnd}). Nothing is broken or placed unless the view ray is actually on that block (or face):
     * the builder turns first, and bridges the way players do, sneaking backwards to the edge and looking down at
     * the side of the block underfoot.
     *
     * @param block the player's node (the block under them while on the ground)
     */
    public Controls tick(PlayerState p, List<Long> path, int idx, long block, boolean allowBreak, boolean allowPlace) {
        if (pillar != NONE) {
            Controls c = pillarTick(p);
            if (c != null) return c;
        }
        if (!p.onGround() || idx < 0 || idx + 1 >= path.size() || path.get(idx) != block) return null;
        long u = path.get(idx), v = path.get(idx + 1);
        List<Digging.Action> acts = actions(world, u, v);
        if (acts.isEmpty()) return allowPlace ? approachBridge(p, path, idx) : null;
        Digging.Action a = acts.get(0);
        if (pendingPos != a.pos() || pendingPlace != a.place()) {
            pendingPos = a.pos();
            pendingPlace = a.place();
            blind = 0;
        }
        if (++blind > BLIND_TICKS) {
            // turning, walking to an edge and aiming found no way to see it: let the planner go another way
            blind = 0;
            hands.stuck(u, v);
            return null;
        }
        // only what the settings allow and the game can do; otherwise wait for the planner's next repair
        if (a.place() ? !allowPlace : !allowBreak || !hands.breakable(a.pos())) return still(p, null);
        boolean vertical = Pos.x(u) == Pos.x(v) && Pos.z(u) == Pos.z(v);
        double cx = Pos.x(u) + 0.5, cz = Pos.z(u) + 0.5;
        if (vertical && Math.hypot(p.x() - cx, p.z() - cz) > CENTER) {
            // mining down or pillaring: sneak to the middle of the block first (never looking up from straight down)
            float yaw = PathFollower.yawTo(p.x(), p.z(), cx, cz);
            return new Controls(true, false, false, false, yaw, Math.max(60f, p.pitch()), 0, true);
        }
        if (!a.place()) return mineOrClear(p, a.pos());
        if (!hands.selectThrowaway()) {
            if (outU != u || outV != v) {
                outU = u;
                outV = v;
                outTicks = 0;
                hands.outOfBlocks();
            } else if (++outTicks > OUT_WAIT) {
                // re-planning still came back this way: this edge is out
                outTicks = 0;
                hands.impossible(u, v);
            }
            return still(p, null);
        }
        if (vertical) {
            // looking straight down already, or the block can't go in while the feet pass over it
            if (Math.hypot(p.vx(), p.vz()) > STILL_SPEED || p.pitch() < PILLAR_PITCH) {
                return new Controls(false, false, false, false, p.yaw(), 90f, 0, true);
            }
            pillar = a.pos();
            pillarTicks = 0;
            pillarLeftGround = false;
            blind = 0;
            return new Controls(false, false, true, false, p.yaw(), 90f, 0, true);
        }
        return bridge(p, a);
    }

    /**
     * Break {@code pos}; if no part of it is in sight, first break whatever block is in the way to it (never one at or
     * below the feet), the way a player digs through to a block behind another.
     */
    Controls mineOrClear(PlayerState p, long pos) {
        double eye = p.y() + hands.eyeHeight();
        int x = Pos.x(pos), y = Pos.y(pos), z = Pos.z(pos);
        if (aimAt(p.x(), eye, p.z(), x, y, z, -1) == null) {
            float[] ang = Raycast.angles(p.x(), eye, p.z(), x + 0.5, y + 0.5, z + 0.5);
            Raycast.Hit h = Raycast.cast(world, p.x(), eye, p.z(), ang[0], ang[1], Raycast.REACH);
            if (h != null && h.y() >= Math.floor(p.y() + 1e-6) && !(h.x() == x && h.y() == y && h.z() == z)) {
                long in = Pos.pack(h.x(), h.y(), h.z());
                if (hands.breakable(in)) return mine(p, in);
            }
        }
        return mine(p, pos);
    }

    /** Break {@code pos} once the crosshair is on it; until then turn to a part of it the eye can see. */
    private Controls mine(PlayerState p, long pos) {
        int x = Pos.x(pos), y = Pos.y(pos), z = Pos.z(pos);
        double eye = p.y() + hands.eyeHeight();
        if (world.physics(x, y, z).has(dev.lazuli.core.world.BlockPhysics.SOFT)) {
            // a flower or leaf litter: no box for the sim's rays to hit, so look at its lower middle and let the game's
            // own crosshair (which sees its outline) decide when it's on it
            float[] ang = Raycast.angles(p.x(), eye, p.z(), x + 0.5, y + 0.15, z + 0.5);
            if (Math.abs(wrapDeg(ang[0] - p.yaw())) < 4 && Math.abs(ang[1] - p.pitch()) < 4) {
                hands.mine(pos);
                blind = 0;
            }
            return new Controls(false, false, false, false, ang[0], ang[1], 0, mineSneak);
        }
        Raycast.Hit now = Raycast.cast(world, p.x(), eye, p.z(), p.yaw(), p.pitch(), Raycast.REACH);
        if (now != null && now.x() == x && now.y() == y && now.z() == z) {
            hands.mine(pos);
            blind = 0;
        }
        float[] aim = aimAt(p.x(), eye, p.z(), x, y, z, -1);
        if (aim == null) {
            Controls s = still(p, center(pos));
            return new Controls(false, false, false, false, s.targetYaw(), s.targetPitch(), 0, mineSneak);
        }
        return new Controls(false, false, false, false, aim[0], aim[1], 0, mineSneak);
    }

    /**
     * Ninja bridging: back towards the gap, sneak (the edge holds the player), and place against the side of the block
     * underfoot once the crosshair is on it. Facing away from the gap, the next block is straight back again, so a
     * long bridge never turns.
     */
    private Controls bridge(PlayerState p, Digging.Action a) {
        long pos = a.pos(), against = a.against();
        int face = Raycast.faceToward(Pos.x(against), Pos.y(against), Pos.z(against), Pos.x(pos), Pos.y(pos), Pos.z(pos));
        double eye = p.y() + hands.eyeHeight();
        Raycast.Hit now = Raycast.cast(world, p.x(), eye, p.z(), p.yaw(), p.pitch(), Raycast.REACH);
        if (now != null && Pos.pack(now.x(), now.y(), now.z()) == against && now.face() == face) {
            hands.place(pos, against);
            blind = 0;
        }
        int dx = Pos.x(pos) - Pos.x(against), dz = Pos.z(pos) - Pos.z(against);
        float back = yawOf(-dx, -dz);
        // how far the eye is out past the edge: once over the gap, aim at the middle of the face; until then look down
        double[] fc = faceCenter(pos, against);
        double past = (p.x() - fc[0]) * dx + (p.z() - fc[2]) * dz;
        float pitch = past > 0.02 ? (float) Math.toDegrees(Math.atan2(eye - fc[1], past)) : BRIDGE_PITCH;
        Controls c = walkFacing(p, Pos.x(pos) + 0.5, Pos.z(pos) + 0.5, back, pitch);
        if (!eagle) return c;
        // the edge only holds while sneaking (and the key takes effect at once), so crouch just before it's needed
        double toward = p.vx() * dx + p.vz() * dz;
        boolean crouch = past + Math.max(0, toward) * EAGLE_LEAD > EAGLE_EDGE || !p.onGround();
        return new Controls(c.forward(), c.back(), false, false, c.targetYaw(), c.targetPitch(), c.strafe(), crouch);
    }

    /**
     * One flat step before a bridge: walk to its edge already facing back, instead of walking forward and turning
     * round at the edge every block.
     */
    private Controls approachBridge(PlayerState p, List<Long> path, int idx) {
        if (idx + 2 >= path.size()) return null;
        long u = path.get(idx), v = path.get(idx + 1), w = path.get(idx + 2);
        if (Pos.y(u) != Pos.y(v) || Math.abs(Pos.x(v) - Pos.x(u)) + Math.abs(Pos.z(v) - Pos.z(u)) != 1) return null;
        List<Digging.Action> next = actions(world, v, w);
        if (next.size() != 1 || !next.get(0).place() || Pos.y(v) != Pos.y(w)) return null;
        return bridge(p, next.get(0));
    }

    /** Sneak towards (tx, tz) while facing {@code yaw}, with W/S and strafing. */
    static Controls walkFacing(PlayerState p, double tx, double tz, float yaw, float pitch) {
        double ex = tx - p.x(), ez = tz - p.z();
        double r = Math.toRadians(yaw);
        double ahead = -ex * Math.sin(r) + ez * Math.cos(r), left = ex * Math.cos(r) + ez * Math.sin(r);
        int strafe = left > WALK_DEAD ? 1 : left < -WALK_DEAD ? -1 : 0;
        return new Controls(ahead > WALK_DEAD, ahead < -WALK_DEAD, false, false, yaw, pitch, strafe, true);
    }

    /** Minecraft yaw facing along (dx, dz). */
    static float yawOf(double dx, double dz) {
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /**
     * Yaw and pitch putting the crosshair on block (x, y, z) from the eye, on face {@code face} (or any face when
     * negative): the middle first, then points spread over the faces. Null if no part of it is in sight and reach.
     */
    float[] aimAt(double ex, double ey, double ez, int x, int y, int z, int face) {
        double[][] pts = {{0.5, 0.5, 0.5}, {0.5, 0.99, 0.5}, {0.5, 0.01, 0.5}, {0.01, 0.5, 0.5}, {0.99, 0.5, 0.5},
            {0.5, 0.5, 0.01}, {0.5, 0.5, 0.99}, {0.2, 0.5, 0.2}, {0.8, 0.5, 0.8}, {0.2, 0.5, 0.8}, {0.8, 0.5, 0.2},
            {0.5, 0.2, 0.5}, {0.5, 0.8, 0.5}};
        for (double[] q : pts) {
            float[] ang = Raycast.angles(ex, ey, ez, x + q[0], y + q[1], z + q[2]);
            Raycast.Hit h = Raycast.cast(world, ex, ey, ez, ang[0], ang[1], Raycast.REACH);
            if (h != null && h.x() == x && h.y() == y && h.z() == z && (face < 0 || h.face() == face)) return ang;
        }
        return null;
    }

    /** Jumping out of the pillar's cell: place under the feet once they clear its top, until landed on it. */
    private Controls pillarTick(PlayerState p) {
        int x = Pos.x(pillar), y = Pos.y(pillar), z = Pos.z(pillar);
        boolean placed = !Digging.open(world, x, y, z);
        pillarTicks++;
        if (!p.onGround()) pillarLeftGround = true;
        if (p.onGround() && (placed || pillarLeftGround) || pillarTicks > PILLAR_TIMEOUT) {
            pillar = NONE; // landed on it, or came back down without it: carry on from the path
            return null;
        }
        if (!placed && !p.onGround() && p.y() > y + 1 + 0.02) {
            Raycast.Hit h = Raycast.cast(world, p.x(), p.y() + hands.eyeHeight(), p.z(), p.yaw(), p.pitch(), Raycast.REACH);
            if (h != null && h.x() == x && h.y() == y - 1 && h.z() == z && h.face() == Raycast.UP) {
                hands.place(pillar, Pos.pack(x, y - 1, z));
            }
        }
        // hold the jump only for the take-off; steer nothing, so the arc stays over the block
        return new Controls(false, false, p.onGround() && !pillarLeftGround, false, p.yaw(), 90f, 0, true);
    }

    /** Whether a pillar jump is under way (the player is airborne on purpose). */
    public boolean busy() {
        return pillar != NONE;
    }

    /** What edge u->v needs done to the world first (nothing for edges that work as the world is). */
    public static List<Digging.Action> actions(BlockView world, long u, long v) {
        return new MoveGraph(world, 64, true, 1, false).actions(u, v);
    }

    /**
     * Index of the last node the player can reach just by moving, starting at {@code from}: the start of the first
     * edge that still needs breaking or placing (looking at most {@code horizon} edges ahead). Movement planning stops
     * there, so it never runs or jumps into a gap that isn't bridged yet.
     */
    public static int walkableEnd(BlockView world, List<Long> path, int from, int horizon) {
        int end = Math.min(path.size() - 1, from + horizon);
        for (int i = Math.max(0, from); i < end; i++) {
            if (!actions(world, path.get(i), path.get(i + 1)).isEmpty()) return i;
            if (dev.lazuli.core.move.Climbing.edge(world, path.get(i), path.get(i + 1))) return i; // the climber's
            if (Jumper.hard(world, path.get(i), path.get(i + 1))) return i; // the jumper's
        }
        return path.size() - 1;
    }

    private static double[] center(long pos) {
        return new double[]{Pos.x(pos) + 0.5, Pos.y(pos) + 0.5, Pos.z(pos) + 0.5};
    }

    /** Middle of the face of {@code against} that touches {@code pos}. */
    static double[] faceCenter(long pos, long against) {
        return new double[]{Pos.x(against) + 0.5 + (Pos.x(pos) - Pos.x(against)) * 0.5,
            Pos.y(against) + 0.5 + (Pos.y(pos) - Pos.y(against)) * 0.5,
            Pos.z(against) + 0.5 + (Pos.z(pos) - Pos.z(against)) * 0.5};
    }

    /** No movement, sneaking, looking at {@code at} (or straight ahead). */
    private static Controls still(PlayerState p, double[] at) {
        float yaw = p.yaw(), pitch = p.pitch();
        if (at != null) {
            double dx = at[0] - p.x(), dy = at[1] - (p.y() + SNEAK_EYE), dz = at[2] - p.z();
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        }
        return new Controls(false, false, false, false, yaw, pitch, 0, true);
    }

    private static float wrapDeg(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}
