package dev.lazuli.core.task;

import dev.lazuli.core.settings.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs a list of steps one after another (a #queue, or a saved #routine), the whole list a number of times or
 * forever. A step is done once Lazuli has been busy with it and is idle again; one that never makes it busy (#set,
 * #drop...) counts as done after a moment. A job with no end (#farm, #mine with no count...) is done when the
 * inventory is full: it's stopped and the next step (a #deposit, say) runs.
 *
 * <p>Steps carry modifiers (see {@link Step}): retries, timeout, what to do on failure, repeats, waits, conditions
 * and settings that hold for that step only.
 */
public final class RoutineRunner {
    /** Ticks a step may take to make Lazuli busy before it's taken as one that's over at once. */
    static final int START_TICKS = 10;
    /** Ticks between polls of an {@code @until} that isn't true yet, and before a retry. */
    static final int POLL_TICKS = 20;
    private static final int RETRY_TICKS = 10;

    /** What the runner needs from the game. */
    public interface Host {
        /** Runs one command line; false when it was refused (an error reply, an exception). */
        boolean run(String line);

        /** Stops the step running now. */
        default void stop() {
        }

        /** For conditions. */
        default Cond.World world() {
            return NO_WORLD;
        }

        /** The {@code say} step: chat (or a /command). */
        default void say(String text) {
        }

        /** The {@code notify} step: a message to the player. */
        default void notify(String text) {
        }
    }

    private static final Cond.World NO_WORLD = new Cond.World() {
        public int items(String item) { return 0; }
        public int chestItems(String item) { return 0; }
        public int freeSlots() { return 36; }
        public double health() { return 20; }
        public int hunger() { return 20; }
        public boolean night() { return false; }
        public String dimension() { return "overworld"; }
        public boolean busy() { return false; }
    };

    private enum Phase { NEXT, LAUNCH, RUN }

    private enum Outcome { NONE, OK, FAIL }

    private List<Step> steps = List.of();
    private String name;
    /** Rounds still to go (negative: forever). */
    private int rounds;
    private int step = -1, waited, round;
    private Phase phase = Phase.NEXT;
    private boolean started;
    /** Some step of this round kept Lazuli busy. */
    private boolean anyBusy;
    /** A forever routine's idle rounds: ticks left to wait, rounds in a row, wait length, limit, where to tell the player. */
    private int idleWait, idleRounds, idleWaitTicks, idleLimit = 20;
    private Consumer<String> notice = m -> {};
    /** Ticks to do nothing (waits, polls, retry pauses). */
    private int pause;
    /** The step running: how many times it ran, retries used, a failure the game reported, ended because of the inventory. */
    private int iter, attempt;
    private boolean failRequested, handedOver, untilMet;
    private String failReason = "";
    private int jumps;
    private Settings settings;
    private final Map<String, String> saved = new LinkedHashMap<>();

    /** What a forever routine does when a whole round did nothing: wait {@code waitTicks} (0: stop) and retry, up to {@code maxRounds} in a row. */
    public void setIdlePolicy(int waitTicks, int maxRounds, Consumer<String> notice) {
        this.idleWaitTicks = waitTicks;
        this.idleLimit = Math.max(1, maxRounds);
        this.notice = notice == null ? m -> {} : notice;
    }

    /** Ticks a step that isn't endless may stay busy before it's stopped and the routine moves on (0: no limit). */
    private int stepTimeout, stepTicks;

    public void setStepTimeout(int ticks) {
        this.stepTimeout = Math.max(0, ticks);
    }

    /** The settings {@code @with} changes (for the step only). */
    public void setSettings(Settings settings) {
        this.settings = settings;
    }

    /**
     * Starts {@code lines}, {@code times} times over (0 or less: forever). Throws IllegalArgumentException (the
     * message says which step and why) when one doesn't parse or a jump has no label.
     */
    public void start(String name, List<String> lines, int times) {
        List<Step> parsed = new ArrayList<>();
        int n = 0;
        for (String l : lines) {
            n++;
            if (l.isBlank()) continue;
            try {
                parsed.add(Step.parse(l));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("step " + n + ": " + e.getMessage());
            }
        }
        List<String> bad = Routine.labelProblems(parsed);
        if (!bad.isEmpty()) throw new IllegalArgumentException(bad.get(0));
        startSteps(name, parsed, times);
    }

    public void startSteps(String name, List<Step> steps, int times) {
        restoreSettings();
        this.name = name;
        this.steps = List.copyOf(steps);
        this.rounds = times <= 0 ? -1 : times;
        this.step = -1;
        this.round = 1;
        this.phase = Phase.NEXT;
        this.started = false;
        this.anyBusy = false;
        this.idleWait = 0;
        this.idleRounds = 0;
        this.pause = 0;
        this.jumps = 0;
        this.failRequested = false;
    }

    public void stop() {
        restoreSettings();
        steps = List.of();
        step = -1;
        pause = 0;
    }

    public boolean running() {
        return !steps.isEmpty();
    }

    /** The step being run (or about to be), null when none. */
    public Step current() {
        return running() && step >= 0 && step < steps.size() ? steps.get(step) : null;
    }

    /** "mine_iron 2/5 (round 3)" for the HUD, or null. */
    public String status() {
        if (!running()) return null;
        String s = (name == null ? "Queue" : name) + " step " + Math.max(1, step + 1) + "/" + steps.size()
            + (rounds < 0 ? " (round " + round + ", forever)" : rounds > 1 || round > 1 ? " (round " + round + ")" : "");
        Step c = current();
        if (c != null) {
            if (c.retry() > 0 && attempt > 0) s += " retry " + attempt + "/" + c.retry();
            if (c.times() > 1) s += " x" + Math.min(iter + 1, c.times()) + "/" + c.times();
            if (pause > 0 && idleWait == 0) s += " waiting " + Math.max(1, pause / 20) + "s";
        }
        if (idleWait > 0) s += " waiting " + Math.max(1, idleWait / 20) + "s";
        return s;
    }

    /** A longer line for #routine status: the step text too. */
    public String detail() {
        String s = status();
        Step c = current();
        return s == null ? null : c == null ? s : s + ": " + c.text();
    }

    /**
     * The game saw the running step fail (a task ended FAILED, an error message). Only steps that asked to care
     * (@retry or @onfail) are affected, so plain routines go on as they always did.
     */
    public void failStep(String why) {
        Step c = current();
        if (phase == Phase.RUN && c != null && c.caresAboutFailure()) {
            failRequested = true;
            failReason = why == null ? "failed" : why;
        }
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
        return tick(busy, full, new Host() {
            @Override
            public boolean run(String line) {
                run.accept(line);
                return true;
            }

            @Override
            public void stop() {
                stopStep.run();
            }
        });
    }

    public String tick(boolean busy, boolean full, Host host) {
        if (!running()) return null;
        if (idleWait > 0) {
            idleWait--;
            return null;
        }
        if (pause > 0) {
            pause--;
            return null;
        }
        for (int guard = 0; guard < 100000; guard++) {
            if (!running()) return null;
            switch (phase) {
                case RUN -> {
                    Outcome o = runPhase(busy, full, host);
                    if (o == Outcome.NONE) return null;
                    String msg = finishAttempt(o, host);
                    if (msg != null || pause > 0) return msg;
                    busy = false; // a restarted step isn't running yet
                }
                case LAUNCH -> {
                    launch(host);
                    return null;
                }
                case NEXT -> {
                    String msg = next(host);
                    if (msg != null || pause > 0 || idleWait > 0) return msg;
                }
            }
        }
        String why = (name == null ? "Queue" : name) + " stopped: it loops without doing anything";
        stop();
        return why;
    }

    /** While a step runs: decides whether it's still going, done, or failed. */
    private Outcome runPhase(boolean busy, boolean full, Host host) {
        Step s = steps.get(step);
        boolean endless = endless(s.command());
        if (failRequested) {
            failRequested = false;
            if (busy) host.stop();
            return Outcome.FAIL;
        }
        if (busy && full && steps.size() > 1 && endless) {
            host.stop();
            busy = false;
            handedOver = true;
        }
        if (busy && s.untilCond() != null && !handedOver && s.untilCond().eval(host.world())) {
            host.stop();
            busy = false;
            untilMet = true;
            started = true;
        }
        int limit = s.timeoutTicks() > 0 ? s.timeoutTicks() : endless ? 0 : stepTimeout;
        if (busy && limit > 0 && ++stepTicks > limit) {
            host.stop();
            if (endless && s.timeoutTicks() > 0) {
                // "farm @timeout 10m": time's up is how that one ends
                started = true;
                anyBusy = true;
                return Outcome.OK;
            }
            failReason = "took too long";
            return Outcome.FAIL;
        }
        if (busy) {
            started = true;
            anyBusy = true;
            return Outcome.NONE;
        }
        if (!started && ++waited < START_TICKS) return Outcome.NONE;
        return Outcome.OK;
    }

    /** An attempt of the running step ended: repeat it, retry it, or go on. */
    private String finishAttempt(Outcome o, Host host) {
        Step s = steps.get(step);
        String who = name == null ? "Queue" : name;
        if (o == Outcome.OK) {
            iter++;
            boolean again = false;
            if (!handedOver) {
                if (s.untilCond() != null && !untilMet && !s.untilCond().eval(host.world())) {
                    again = true;
                    pause = POLL_TICKS;
                } else if (s.untilCond() == null && iter < s.times()) {
                    again = true;
                }
            }
            if (again) {
                phase = Phase.LAUNCH;
                attempt = 0;
                return null;
            }
            restoreSettings();
            phase = Phase.NEXT;
            pause = s.afterTicks();
            return null;
        }
        // failed
        String why = failReason.isEmpty() ? "failed" : failReason;
        failReason = "";
        if (attempt < s.retry()) {
            attempt++;
            notice.accept(who + ": '" + s.command() + "' " + why + "; retrying (" + attempt + "/" + s.retry() + ")");
            phase = Phase.LAUNCH;
            pause = RETRY_TICKS;
            return null;
        }
        restoreSettings();
        switch (s.onFail()) {
            case STOP -> {
                String msg = who + " stopped: step " + (step + 1) + " '" + s.command() + "' " + why;
                stop();
                return msg;
            }
            case SKIP_REST -> {
                notice.accept(who + ": '" + s.command() + "' " + why + "; skipping the rest of the round");
                step = steps.size() - 1;
            }
            case GOTO -> {
                int at = indexOfLabel(s.failLabel());
                if (at < 0) {
                    String msg = who + " stopped: step " + (step + 1) + " jumps to unknown label '" + s.failLabel() + "'";
                    stop();
                    return msg;
                }
                notice.accept(who + ": '" + s.command() + "' " + why + "; going to " + s.failLabel());
                step = at - 1;
            }
            default -> notice.accept(who + ": '" + s.command() + "' " + why + "; moving on");
        }
        phase = Phase.NEXT;
        pause = 0;
        return null;
    }

    /** Runs the command of the step chosen. */
    private void launch(Host host) {
        Step s = steps.get(step);
        started = false;
        waited = 0;
        stepTicks = 0;
        failRequested = false;
        handedOver = false;
        untilMet = false;
        jumps = 0;
        applySettings(s);
        phase = Phase.RUN;
        boolean ok;
        try {
            ok = host.run(s.command());
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok && s.caresAboutFailure()) {
            failReason = "didn't start";
            failRequested = true;
        }
    }

    /** Picks the next step to run (handling the step words, conditions, and the end of a round). */
    private String next(Host host) {
        String who = name == null ? "Queue" : name;
        while (true) {
            step++;
            if (step >= steps.size()) {
                if (!anyBusy && rounds < 0 && idleWaitTicks > 0) {
                    // forever, and nothing to do (an empty chest...): wait, then try the round again
                    if (++idleRounds >= idleLimit) {
                        String why = who + " stopped: nothing to do for " + idleRounds + " rounds";
                        stop();
                        return why;
                    }
                    if (idleRounds == 1) notice.accept(who + ": nothing to do, waiting " + Math.max(1, idleWaitTicks / 20) + " s");
                    idleWait = idleWaitTicks;
                    step = -1;
                    round++;
                    return null;
                }
                if (anyBusy) idleRounds = 0;
                if (!anyBusy && (rounds < 0 || rounds > 1)) {
                    // a whole round where no step got going (each failed straight away): don't spin through it again
                    String why = who + " stopped: none of its steps did anything";
                    stop();
                    return why;
                }
                anyBusy = false;
                if (rounds > 0 && --rounds == 0) {
                    String done = who + " done";
                    stop();
                    return done;
                }
                step = -1;
                round++;
                continue;
            }
            Step s = steps.get(step);
            if (!s.enabled()) continue;
            if (s.ifCond() != null && !s.ifCond().eval(host.world())) continue;
            if (s.unlessCond() != null && s.unlessCond().eval(host.world())) continue;
            switch (s.kind()) {
                case LABEL -> {
                    continue;
                }
                case JUMP -> {
                    int at = indexOfLabel(s.label());
                    if (at < 0 || ++jumps > 1000) {
                        String why = who + " stopped: " + (at < 0 ? "step " + (step + 1) + " jumps to unknown label '" + s.label() + "'"
                            : "jumps go round without anything running");
                        stop();
                        return why;
                    }
                    step = at; // the label itself is skipped on the next pass
                    continue;
                }
                case SKIP -> {
                    step = steps.size() - 1;
                    continue;
                }
                case END -> {
                    String done = who + " done";
                    stop();
                    return done;
                }
                case SAY -> {
                    host.say(s.args());
                    anyBusy = true;
                    continue;
                }
                case NOTIFY -> {
                    host.notify(s.args());
                    anyBusy = true;
                    continue;
                }
                case WAIT -> {
                    anyBusy = true;
                    jumps = 0;
                    iter = 0;
                    pause = s.waitTicks();
                    phase = Phase.NEXT;
                    return null;
                }
                default -> {
                    iter = 0;
                    attempt = 0;
                    phase = Phase.LAUNCH;
                    pause = s.beforeTicks();
                    return null;
                }
            }
        }
    }

    private int indexOfLabel(String label) {
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            if (s.kind() == Step.Kind.LABEL && s.label().equalsIgnoreCase(label)) return i;
        }
        return -1;
    }

    private void applySettings(Step s) {
        if (settings == null || s.with().isEmpty()) return;
        s.with().forEach((k, v) -> settings.byName(k).ifPresent(set -> {
            saved.putIfAbsent(set.name(), set.format());
            try {
                set.parse(v);
            } catch (IllegalArgumentException ignored) {
                // Routine.check reports bad values; here the setting stays as it was
            }
        }));
    }

    /** Puts back what {@code @with} changed. */
    private void restoreSettings() {
        if (settings == null || saved.isEmpty()) {
            saved.clear();
            return;
        }
        saved.forEach((k, v) -> settings.byName(k).ifPresent(set -> {
            try {
                set.parse(v);
            } catch (IllegalArgumentException ignored) {
                // it was valid when saved
            }
        }));
        saved.clear();
    }

    /** A job that does not end on its own: #farm, #tunnel, #explore, #follow, and #mine / #fish with no count. */
    public static boolean endless(String line) {
        String[] w = line.trim().replaceFirst("^[^a-zA-Z]+", "").split("\\s+");
        String cmd = w[0].toLowerCase(Locale.ROOT);
        boolean counted = false;
        for (int i = 1; i < w.length; i++) counted |= w[i].matches("[0-9]+");
        return switch (cmd) {
            case "farm", "tunnel", "explore", "follow" -> true;
            case "mine", "fish" -> !counted;
            default -> false;
        };
    }
}
