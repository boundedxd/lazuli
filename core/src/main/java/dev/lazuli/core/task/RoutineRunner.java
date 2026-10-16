package dev.lazuli.core.task;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a list of commands one after another (a #queue, or a saved #routine), the whole list a number of times or
 * forever. A step is done once Lazuli has been busy with it and is idle again; one that never makes it busy (#set,
 * #drop...) counts as done after a moment.
 */
public final class RoutineRunner {
    /** Ticks a step may take to make Lazuli busy before it's taken as one that's over at once. */
    static final int START_TICKS = 10;

    private List<String> steps = List.of();
    private String name;
    /** Rounds still to go (negative: forever). */
    private int rounds;
    private int step = -1, waited, round;
    private boolean started;

    /** Starts {@code steps}, {@code times} times over (0 or less: forever). */
    public void start(String name, List<String> steps, int times) {
        this.name = name;
        this.steps = List.copyOf(steps);
        this.rounds = times <= 0 ? -1 : times;
        this.step = -1;
        this.round = 1;
        this.started = false;
    }

    public void stop() {
        steps = List.of();
        step = -1;
    }

    public boolean running() {
        return !steps.isEmpty();
    }

    /** "mine_iron 2/5 (round 3)" for the HUD, or null. */
    public String status() {
        if (!running()) return null;
        return (name == null ? "Queue" : name) + " step " + Math.max(1, step + 1) + "/" + steps.size()
            + (rounds < 0 ? " (round " + round + ", forever)" : rounds > 1 || round > 1 ? " (round " + round + ")" : "");
    }

    /**
     * Once a tick.
     *
     * @param busy Lazuli is pathing or a process is running
     * @param run  runs one command line
     * @return a message when the whole thing is finished, else null
     */
    public String tick(boolean busy, Consumer<String> run) {
        if (!running()) return null;
        if (step >= 0) {
            if (busy) {
                started = true;
                return null;
            }
            if (!started && ++waited < START_TICKS) return null;
        }
        // on to the next step (or the next round)
        step++;
        if (step >= steps.size()) {
            if (rounds > 0 && --rounds == 0) {
                String done = (name == null ? "Queue" : name) + " done";
                stop();
                return done;
            }
            step = 0;
            round++;
        }
        started = false;
        waited = 0;
        run.accept(steps.get(step));
        return null;
    }
}
