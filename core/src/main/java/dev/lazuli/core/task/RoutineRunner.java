package dev.lazuli.core.task;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs a list of commands one after another (a #queue, or a saved #routine), the whole list a number of times or
 * forever. A step is done once Lazuli has been busy with it and is idle again; one that never makes it busy (#set,
 * #drop...) counts as done after a moment. A job with no end (#farm, #mine with no count...) is done when the
 * inventory is full: it's stopped and the next step (a #deposit, say) runs.
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
        return tick(busy, false, run, () -> {});
    }

    /**
     * @param full     the inventory has no room left
     * @param stopStep stops the step running now (an endless one, handing over)
     */
    public String tick(boolean busy, boolean full, Consumer<String> run, Runnable stopStep) {
        if (!running()) return null;
        if (step >= 0) {
            if (busy && full && steps.size() > 1 && endless(steps.get(step))) {
                stopStep.run();
                busy = false;
            }
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

    /** A job that doesn't end on its own: #farm, #tunnel, #explore, and #mine / #fish with no count. */
    public static boolean endless(String line) {
        String[] w = line.trim().replaceFirst("^[^a-zA-Z]+", "").split("\\s+");
        String cmd = w[0].toLowerCase(java.util.Locale.ROOT);
        boolean counted = false;
        for (int i = 1; i < w.length; i++) counted |= w[i].matches("[0-9]+");
        return switch (cmd) {
            case "farm", "tunnel", "explore" -> true;
            case "mine", "fish" -> !counted;
            default -> false;
        };
    }
}
