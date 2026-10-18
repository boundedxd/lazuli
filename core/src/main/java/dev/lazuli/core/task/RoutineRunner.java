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

    /** The turns of a while / repeat loop that's running: its header step, turns left (repeat) and turns started. */
    private static final class Frame {
        final int header;
        int remaining;
        long iter;

        Frame(int header, int remaining) {
            this.header = header;
            this.remaining = remaining;
        }
    }

    /** What conditions see while the routine runs: the game, plus the routine's variables, loop and last-step state. */
    private final class Env implements Cond.World {
        private final Cond.World base;

        Env(Cond.World base) {
            this.base = base;
        }

        public int items(String item) { return base.items(item); }
        public int chestItems(String item) { return base.chestItems(item); }
        public int freeSlots() { return base.freeSlots(); }
        public double health() { return base.health(); }
        public int hunger() { return base.hunger(); }
        public boolean night() { return base.night(); }
        public String dimension() { return base.dimension(); }
        public boolean busy() { return base.busy(); }
        public int anywhere(String item) { return base.anywhere(item); }
        public double armorDurability() { return base.armorDurability(); }
        public double toolDurability(String item) { return base.toolDurability(item); }
        public int xpLevel() { return base.xpLevel(); }
        public int timeOfDay() { return base.timeOfDay(); }
        public boolean raining() { return base.raining(); }
        public boolean thundering() { return base.thundering(); }
        public int y() { return base.y(); }
        public double distanceTo(String where) { return base.distanceTo(where); }
        public int playersNearby() { return base.playersNearby(); }
        public int hostilesNearby() { return base.hostilesNearby(); }
        public boolean holding(String item) { return base.holding(item); }
        public boolean hasEffect(String name) { return base.hasEffect(name); }
        public boolean lastFailed() { return lastResult == Outcome.FAIL; }
        public boolean lastSucceeded() { return lastResult == Outcome.OK; }
        public int loopCount() { return frames.isEmpty() ? 0 : (int) Math.min(Integer.MAX_VALUE, frames.get(frames.size() - 1).iter); }
        public boolean random(int percent) { return random.nextInt(100) < percent; }

        public double variable(String name) {
            return switch (name) {
                case "round" -> round;
                case "fails" -> fails;
                case "loop" -> loopCount();
                default -> vars.getOrDefault(name, 0.0);
            };
        }
    }

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
    private volatile boolean failRequested;
    private boolean handedOver, untilMet;
    private volatile String failReason = "";
    private int jumps;
    private Program program = Program.of(List.of(), null);
    private int[] lines;
    private final List<Frame> frames = new ArrayList<>();
    private final Map<String, Double> vars = new java.util.HashMap<>();
    private final Map<Integer, Cond> blockConds = new java.util.HashMap<>();
    private int fails;
    private Outcome lastResult = Outcome.NONE;
    /** Some step asks about the last step's result or the failure count, so every step's failure is noticed. */
    private boolean tracksFailure;
    private int loopLimit = 100000;
    private java.util.Random random = new java.util.Random();
    private Settings settings;

    /** Turns one while / repeat loop may take before the routine is stopped (the safety net against endless loops). */
    public void setLoopLimit(int turns) {
        this.loopLimit = Math.max(1, turns);
    }

    /** For tests: the dice behind {@code random <percent>}. */
    public void setRandom(java.util.Random random) {
        this.random = random;
    }

    /** A routine variable's value (0 when never set); for tests and the status line. */
    public double variable(String name) {
        return new Env(NO_WORLD).variable(name.toLowerCase(Locale.ROOT));
    }
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
        Routine.Compiled c = Routine.compile(lines);
        List<String> bad = Routine.labelProblems(c.steps());
        if (!bad.isEmpty()) throw new IllegalArgumentException(bad.get(0));
        bad = Routine.variableProblems(c.steps(), c.lines());
        if (!bad.isEmpty()) throw new IllegalArgumentException(bad.get(0));
        begin(name, c.steps(), c.program(), c.lines(), times);
    }

    /** Throws IllegalArgumentException when the steps' blocks don't match up. */
    public void startSteps(String name, List<Step> steps, int times) {
        begin(name, steps, Program.of(steps, null), null, times);
    }

    private void begin(String name, List<Step> steps, Program program, int[] lines, int times) {
        restoreSettings();
        this.program = program;
        this.lines = lines;
        frames.clear();
        vars.clear();
        blockConds.clear();
        fails = 0;
        lastResult = Outcome.NONE;
        tracksFailure = false;
        for (Step s : steps) {
            String t = Cond.normalize(s.text());
            if (t.contains("last.failed") || t.contains("last.ok") || t.toLowerCase(Locale.ROOT).contains("$fails")) tracksFailure = true;
        }
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
        frames.clear();
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
        if (phase == Phase.RUN && c != null && cares(c)) {
            failReason = why == null ? "failed" : why;
            failRequested = true;
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
        if (busy && s.untilCond() != null && !handedOver && s.untilCond().eval(env(host))) {
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
                if (s.untilCond() != null && !untilMet && !s.untilCond().eval(env(host))) {
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
            lastResult = Outcome.OK;
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
        lastResult = Outcome.FAIL;
        fails++;
        switch (s.onFail()) {
            case STOP -> {
                String msg = who + " stopped: step " + (step + 1) + " '" + s.command() + "' " + why;
                stop();
                return msg;
            }
            case SKIP_REST -> {
                notice.accept(who + ": '" + s.command() + "' " + why + "; skipping the rest of the round");
                step = steps.size() - 1;
                frames.clear();
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
                pruneFrames(at);
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
            ok = host.run(subst(s.command()));
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok && cares(s)) {
            failReason = "didn't start";
            failRequested = true;
        }
    }

    /** Picks the next step to run (handling the step words, conditions, and the end of a round). */
    private String next(Host host) {
        String who = name == null ? "Queue" : name;
        int spins = 0;
        while (true) {
            if (!running()) return null;
            if (++spins > SPINS_PER_TICK) {
                // a loop of steps that run nothing: let the game have its tick, go on in the next
                pause = 1;
                return null;
            }
            step++;
            if (step >= steps.size()) {
                frames.clear();
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
            Cond.World env = env(host);
            if (s.ifCond() != null && !s.ifCond().eval(env)) continue;
            if (s.unlessCond() != null && s.unlessCond().eval(env)) continue;
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
                    pruneFrames(at);
                    continue;
                }
                case SKIP -> {
                    step = steps.size() - 1;
                    frames.clear();
                    continue;
                }
                case IF -> {
                    if (blockCond(step).eval(env)) continue;
                    int t = program.nextClause(step);
                    // the else-if chain: the first clause that holds (or the plain else, or the end)
                    while (t >= 0 && steps.get(t).kind() == Step.Kind.ELSE && !steps.get(t).args().isEmpty() && !blockCond(t).eval(env))
                        t = program.nextClause(t);
                    step = t < 0 ? steps.size() - 1 : t;
                    continue;
                }
                case ELSE -> {
                    // the branch before it ran: on to the end of the if
                    step = program.endOf(step);
                    continue;
                }
                case WHILE -> {
                    Frame f = frameOf(step);
                    if (f == null) {
                        f = new Frame(step, 0);
                        frames.add(f);
                    }
                    if (!blockCond(step).eval(env)) {
                        frames.remove(f);
                        step = program.endOf(step);
                        continue;
                    }
                    String over = turn(f, who);
                    if (over != null) return over;
                    continue;
                }
                case REPEAT -> {
                    Frame f = frameOf(step);
                    if (f == null) {
                        f = new Frame(step, (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.round(Expr.eval(s.expression(), env)))));
                        frames.add(f);
                    }
                    if (f.remaining <= 0) {
                        frames.remove(f);
                        step = program.endOf(step);
                        continue;
                    }
                    String over = turn(f, who);
                    if (over != null) return over;
                    continue;
                }
                case BREAK -> {
                    int h = program.loopOf(step);
                    while (!frames.isEmpty() && frames.get(frames.size() - 1).header >= h) frames.remove(frames.size() - 1);
                    step = program.endOf(h);
                    continue;
                }
                case CONTINUE -> {
                    step = program.endOf(program.loopOf(step)) - 1; // the loop's end is next
                    continue;
                }
                case SET -> {
                    vars.put(s.variable(), Expr.eval(s.expression(), env));
                    continue;
                }
                case ADD -> {
                    vars.merge(s.variable(), Expr.eval(s.expression(), env), Double::sum);
                    continue;
                }
                case END -> {
                    if (program.closes(step)) {
                        int h = program.headOf(step);
                        Step.Kind k = steps.get(h).kind();
                        if (k == Step.Kind.WHILE) {
                            step = h - 1; // the condition is looked at again
                        } else if (k == Step.Kind.REPEAT) {
                            Frame f = frameOf(h);
                            if (f != null) f.remaining--;
                            step = h - 1;
                        }
                        continue;
                    }
                    String done = who + " done";
                    stop();
                    return done;
                }
                case SAY -> {
                    host.say(subst(s.args()));
                    anyBusy = true;
                    continue;
                }
                case NOTIFY -> {
                    host.notify(subst(s.args()));
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

    private static final int SPINS_PER_TICK = 5000;
    private static final java.util.regex.Pattern VAR = java.util.regex.Pattern.compile("\\$([A-Za-z_]\\w*)");

    private Cond.World env(Host host) {
        return new Env(host.world());
    }

    /** A step cares about failing when it asked to, or when the routine looks at how steps went. */
    private boolean cares(Step s) {
        return s.caresAboutFailure() || tracksFailure;
    }

    private Cond blockCond(int at) {
        return blockConds.computeIfAbsent(at, k -> steps.get(k).blockCond());
    }

    /** The loop frame of the header at {@code header} when it's the innermost one running. */
    private Frame frameOf(int header) {
        if (frames.isEmpty()) return null;
        Frame f = frames.get(frames.size() - 1);
        return f.header == header ? f : null;
    }

    /** Starts a turn of a loop; a message (the routine stopped) when it has gone round too often. */
    private String turn(Frame f, String who) {
        if (++f.iter > loopLimit) {
            int at = lines == null ? f.header + 1 : lines[f.header];
            String why = who + " stopped: the loop at step " + at + " ('" + steps.get(f.header).text() + "') went round more than "
                + loopLimit + " times (setting routineLoopLimit)";
            stop();
            return why;
        }
        return null;
    }

    /** Leaving by a jump: loops the target isn't inside are over. */
    private void pruneFrames(int target) {
        while (!frames.isEmpty()) {
            Frame f = frames.get(frames.size() - 1);
            int end = program.endOf(f.header);
            if (target > f.header && target < end) break;
            frames.remove(frames.size() - 1);
        }
    }

    /** Puts the variables' values in for {@code $name} (a name that was never set stays as typed). */
    private String subst(String text) {
        if (text.indexOf('$') < 0) return text;
        Cond.World env = new Env(NO_WORLD);
        java.util.regex.Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String n = m.group(1).toLowerCase(Locale.ROOT);
            boolean known = vars.containsKey(n) || Step.BUILT_IN_VARIABLES.contains(n);
            String rep = m.group();
            if (known) {
                double d = env.variable(n);
                rep = d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(out);
        return out.toString();
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
