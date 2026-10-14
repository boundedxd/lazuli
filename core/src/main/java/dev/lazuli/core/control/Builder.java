package dev.lazuli.core.control;

import dev.lazuli.core.move.Digging;
import dev.lazuli.core.move.MoveGraph;
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
    }

    private static final long NONE = Long.MIN_VALUE;
    /** How far from the middle of its block the player may start a pillar or a dig straight down. */
    static final double CENTER = 0.15;
    /** Horizontal speed (blocks/tick) below which a pillar jump stays over its block: air drag only takes 9% a tick. */
    static final double STILL_SPEED = 0.02;
    private static final int PILLAR_TIMEOUT = 40;
    static final double EYE = 1.62;
    static final double SNEAK_EYE = 1.27;

    private final BlockView world;
    private final Hands hands;
    private long pillar = NONE;
    private int pillarTicks;
    private boolean pillarLeftGround;

    public Builder(BlockView world, Hands hands) {
        this.world = world;
        this.hands = hands;
    }

    public void reset() {
        pillar = NONE;
    }

    /**
     * Controls for this tick while breaking or placing, or null when the player should just move along the path (up to
     * {@link #walkableEnd}).
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
        if (acts.isEmpty()) return null;
        Digging.Action a = acts.get(0);
        // only what the settings allow and the game can do; otherwise wait for the planner's next repair
        if (a.place() ? !allowPlace : !allowBreak || !hands.breakable(a.pos())) return still(p, null);
        boolean vertical = Pos.x(u) == Pos.x(v) && Pos.z(u) == Pos.z(v);
        double cx = Pos.x(u) + 0.5, cz = Pos.z(u) + 0.5;
        if (vertical && Math.hypot(p.x() - cx, p.z() - cz) > CENTER) {
            // mining down or pillaring: sneak to the middle of the block first
            float yaw = PathFollower.yawTo(p.x(), p.z(), cx, cz);
            return new Controls(true, false, false, false, yaw, 60f, 0, true);
        }
        if (!a.place()) {
            hands.mine(a.pos());
            return still(p, center(a.pos()));
        }
        if (!hands.selectThrowaway()) {
            hands.outOfBlocks();
            return still(p, null);
        }
        if (vertical) {
            if (Math.hypot(p.vx(), p.vz()) > STILL_SPEED) return still(p, null); // or the jump lands beside the pillar
            pillar = a.pos();
            pillarTicks = 0;
            pillarLeftGround = false;
            return new Controls(false, false, true, false, p.yaw(), 90f, 0, true);
        }
        hands.place(a.pos(), a.against());
        return still(p, faceCenter(a.pos(), a.against()));
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
        if (!placed && !p.onGround() && p.y() > y + 1 + 0.02) hands.place(pillar, Pos.pack(x, y - 1, z));
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
}
