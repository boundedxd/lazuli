package dev.lazuli.mod.process;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.session.PathingSession;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import static dev.lazuli.core.world.Pos.pack;

/** One quiet trip a process asked the session for, and whether it's still going. */
final class Trip {
    private final PathingSession session;
    /** Snapshot version when the trip was asked for; -1 for none. */
    private long start = -1;
    private int startedTick;

    Trip(PathingSession session) {
        this.session = session;
    }

    void go(Goal goal, LocalPlayer player) {
        start = session.snapshot().version();
        startedTick = player.tickCount;
        BlockPos f = player.blockPosition();
        session.start(goal, false, pack(f.getX(), f.getY(), f.getZ()), true);
    }

    /** Asked for and not finished (the session may not have picked it up yet). */
    boolean running() {
        if (start < 0) return false;
        PathingSession.Snapshot s = session.snapshot();
        return s.version() == start || s.state() == PathingSession.State.PATHING;
    }

    /** How the last trip ended (ARRIVED, FAILED or IDLE), or null while one is running or none was made. */
    PathingSession.State ended() {
        if (start < 0 || running()) return null;
        return session.snapshot().state();
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
