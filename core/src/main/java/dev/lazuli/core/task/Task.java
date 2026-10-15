package dev.lazuli.core.task;

import dev.lazuli.core.session.PathingSession;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * One job Lazuli works on (a trip, a mining run, a craft...): whoever started it can wait on {@link #done()} to learn
 * how it ended. {@link #tick()} checks on it once (on the client thread); it's finished as soon as that gives a result.
 */
public final class Task {
    public enum Result { DONE, FAILED, CANCELLED }

    private final String name;
    /** The result once it's over, null while it's still going. */
    private final Supplier<Result> poll;
    private final Runnable cancel;
    private final CompletableFuture<Result> done = new CompletableFuture<>();

    public Task(String name, Supplier<Result> poll, Runnable cancel) {
        this.name = name;
        this.poll = poll;
        this.cancel = cancel;
    }

    public String name() {
        return name;
    }

    public CompletableFuture<Result> done() {
        return done;
    }

    public boolean finished() {
        return done.isDone();
    }

    /** Checks whether it has finished; true once it has. */
    public boolean tick() {
        if (done.isDone()) return true;
        Result r = poll.get();
        if (r == null) return false;
        done.complete(r);
        return true;
    }

    /** Stops it, unless it already finished. */
    public void cancel() {
        if (done.isDone()) return;
        cancel.run();
        done.complete(Result.CANCELLED);
    }

    /** A trip, by the number {@link PathingSession#start} gave it: done on arrival, cancelled by any later start or stop. */
    public static Task trip(String name, PathingSession session, long trip) {
        return new Task(name, () -> {
            PathingSession.Snapshot s = session.snapshot();
            if (s.trip() > trip) return Result.CANCELLED;
            if (s.trip() < trip) return null; // the planner hasn't got to it yet
            return switch (s.state()) {
                case ARRIVED -> Result.DONE;
                case FAILED -> Result.FAILED;
                case IDLE -> Result.CANCELLED;
                case PATHING -> null;
            };
        }, () -> {
            if (session.snapshot().trip() <= trip) session.stop(true);
        });
    }
}
