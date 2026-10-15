package dev.lazuli.mod.process;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.session.PathingSession;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static dev.lazuli.core.world.Pos.pack;

/** One quiet trip a process asked the session for, and whether it's still going. */
final class Trip {
    private final PathingSession session;
    /** The trip's number from the session; -1 for none. */
    private long start = -1;
    private int startedTick;
    /** Where the player last stood: mid-jump, the block they're in is air, and no trip can start from air. */
    private static BlockPos lastGround;

    Trip(PathingSession session) {
        this.session = session;
    }

    void go(Goal goal, LocalPlayer player) {
        go(goal, player, false);
    }

    /** @param fallback the nearest safe spot when the goal can't be reached */
    void go(Goal goal, LocalPlayer player, boolean fallback) {
        startedTick = player.tickCount;
        BlockPos f = start(player);
        start = session.start(goal, fallback, pack(f.getX(), f.getY(), f.getZ()), true);
    }

    /** The block to start from: under the player, or where they last stood while in the air. */
    static BlockPos start(LocalPlayer player) {
        if (player.onGround() || player.isInWater() || player.onClimbable() || lastGround == null
            || lastGround.distSqr(player.blockPosition()) > 16) {
            lastGround = player.blockPosition();
        }
        return lastGround;
    }

    /** Each tick: remembers where the player stands. */
    static void track(LocalPlayer player) {
        if (player.onGround()) lastGround = player.blockPosition();
    }

    /** Asked for and not finished (the session may not have picked it up yet). */
    boolean running() {
        if (start < 0) return false;
        PathingSession.Snapshot s = session.snapshot();
        return s.trip() < start || s.trip() == start && s.state() == PathingSession.State.PATHING;
    }

    /** How the last trip ended (ARRIVED, FAILED or IDLE), or null while one is running or none was made. */
    PathingSession.State ended() {
        if (start < 0 || running()) return null;
        PathingSession.Snapshot s = session.snapshot();
        return s.trip() == start ? s.state() : PathingSession.State.IDLE; // replaced or stopped
    }

    int age(LocalPlayer player) {
        return player.tickCount - startedTick;
    }

    void cancel() {
        if (running()) session.stop(true);
        start = -1;
    }

    /** Forgets the last trip's outcome. */
    void clear() {
        start = -1;
    }
}
