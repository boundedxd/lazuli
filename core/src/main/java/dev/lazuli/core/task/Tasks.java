package dev.lazuli.core.task;

import java.util.function.Supplier;

/** The one task running at a time: a new one cancels the old one before it starts. */
public final class Tasks {
    private Task current;

    /** Cancels the current task, then starts the next one ({@code start} begins the work and returns its task). */
    public Task run(Supplier<Task> start) {
        cancel();
        current = start.get();
        return current;
    }

    /** Once per client tick. */
    public void tick() {
        if (current != null && current.tick()) current = null;
    }

    public void cancel() {
        Task t = current;
        current = null;
        if (t != null) t.cancel();
    }

    /** The running task, or null. */
    public Task current() {
        return current;
    }
}
