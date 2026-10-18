package dev.lazuli.core.task;

import dev.lazuli.core.settings.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Checking routines (#routine check) and the text that explains the routine language. */
public final class Routine {
    private Routine() {
    }

    private static final Pattern TARGET = Pattern.compile("(?<![\\w@])@([A-Za-z0-9_][A-Za-z0-9_.-]*)");

    /** Parses every line; null for blank ones. Throws IllegalArgumentException (step number, reason) on the first bad one. */
    public static List<Step> parseAll(List<String> lines) {
        List<Step> out = new ArrayList<>();
        int n = 0;
        for (String l : lines) {
            n++;
            if (l.isBlank()) continue;
            try {
                out.add(Step.parse(l));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("step " + n + ": " + e.getMessage());
            }
        }
        return out;
    }

    /** Parsed steps, the line each came from, and how their blocks nest. */
    public record Compiled(List<Step> steps, int[] lines, Program program) {
    }

    /** Parses the lines and checks their blocks; throws IllegalArgumentException ("step N: why", N the line) on the first problem. */
    public static Compiled compile(List<String> lines) {
        List<Step> out = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        int n = 0;
        for (String l : lines) {
            n++;
            if (l.isBlank()) continue;
            try {
                out.add(Step.parse(l));
                at.add(n);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("step " + n + ": " + e.getMessage());
            }
        }
        int[] nums = at.stream().mapToInt(Integer::intValue).toArray();
        return new Compiled(out, nums, Program.of(out, nums));
    }

    private static final Pattern VARIABLE = Pattern.compile("\\$([A-Za-z_]\\w*)");

    /** Variables used but never set (set or add), as problems with the line of the first use. */
    static List<String> variableProblems(List<Step> steps, int[] lines) {
        java.util.Set<String> known = new java.util.HashSet<>(Step.BUILT_IN_VARIABLES);
        for (Step s : steps) if (s.kind() == Step.Kind.SET || s.kind() == Step.Kind.ADD) known.add(s.variable());
        List<String> out = new ArrayList<>();
        java.util.Set<String> told = new java.util.HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            String text = s.kind() == Step.Kind.SET || s.kind() == Step.Kind.ADD ? s.expression() : s.text();
            Matcher m = VARIABLE.matcher(text);
            while (m.find()) {
                String v = m.group(1).toLowerCase(Locale.ROOT);
                if (known.contains(v) || !told.add(v)) continue;
                out.add("step " + (lines == null ? i + 1 : lines[i]) + ": unknown variable $" + v + " (give it a value first: set " + v + " = 0)");
            }
        }
        return out;
    }

    /** Problems with labels: duplicates, jumps and on-fail gotos to ones that don't exist. */
    public static List<String> labelProblems(List<Step> steps) {
        List<String> out = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Step s : steps) {
            if (s.kind() != Step.Kind.LABEL) continue;
            String l = s.label().toLowerCase(Locale.ROOT);
            if (labels.contains(l)) out.add("label '" + s.label() + "' is there twice");
            labels.add(l);
        }
        int n = 0;
        for (Step s : steps) {
            n++;
            if (s.kind() == Step.Kind.JUMP && !labels.contains(s.label().toLowerCase(Locale.ROOT)))
                out.add("step " + n + ": jumps to label '" + s.label() + "' which isn't in the routine" + labelHint(s.label(), labels));
            if (s.onFail() == Step.OnFail.GOTO && !labels.contains(s.failLabel().toLowerCase(Locale.ROOT)))
                out.add("step " + n + ": @onfail goto '" + s.failLabel() + "' but there's no such label" + labelHint(s.failLabel(), labels));
        }
        return out;
    }

    private static String labelHint(String want, List<String> labels) {
        if (labels.isEmpty()) return " (add a 'label " + want + "' step)";
        return " (labels: " + String.join(", ", labels) + ")";
    }

    /**
     * Everything wrong with these lines (an empty list: fine): parse errors, unknown commands, unknown settings or
     * bad values after {@code @with}, labels, targets that don't exist.
     *
     * @param knownCommand whether a command word exists (null: not checked)
     * @param knownTarget  whether a target name exists (null: not checked)
     */
    public static List<String> check(List<String> lines, Predicate<String> knownCommand, Predicate<String> knownTarget) {
        return check(lines, knownCommand, knownTarget, true);
    }

    /** {@code whole} false: just step by step (one edited line), without the block and variable checks that need the whole routine. */
    public static List<String> check(List<String> lines, Predicate<String> knownCommand, Predicate<String> knownTarget, boolean whole) {
        List<String> out = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        int n = 0;
        Settings scratch = new Settings();
        for (String l : lines) {
            n++;
            if (l.isBlank()) continue;
            Step s;
            try {
                s = Step.parse(l);
            } catch (IllegalArgumentException e) {
                out.add("step " + n + ": " + e.getMessage());
                continue;
            }
            steps.add(s);
            at.add(n);
            if (s.kind() == Step.Kind.COMMAND && knownCommand != null) {
                String w = s.word();
                if (!w.isEmpty() && !knownCommand.test(w)) out.add("step " + n + ": unknown command '" + w + "'");
            }
            for (var e : s.with().entrySet()) {
                var set = scratch.byName(e.getKey());
                if (set.isEmpty()) {
                    out.add("step " + n + ": @with: no setting called '" + e.getKey() + "' (#settings lists them)");
                } else {
                    try {
                        set.get().parse(e.getValue());
                    } catch (IllegalArgumentException ex) {
                        out.add("step " + n + ": @with " + e.getKey() + "=" + e.getValue() + ": " + ex.getMessage());
                    }
                }
            }
            if (knownTarget != null && s.kind() != Step.Kind.SAY && s.kind() != Step.Kind.NOTIFY) {
                Matcher m = TARGET.matcher(s.command());
                while (m.find()) {
                    String t = m.group(1);
                    if (!knownTarget.test(t)) out.add("step " + n + ": no target called @" + t + targetHint(t));
                }
            }
        }
        if (!whole) return out;
        out.addAll(labelProblems(steps));
        int[] nums = at.stream().mapToInt(Integer::intValue).toArray();
        Program.build(steps, nums, out);
        out.addAll(variableProblems(steps, nums));
        return out;
    }

    private static String targetHint(String t) {
        String best = null;
        int bestD = 3;
        for (String m : Step.MODIFIERS) {
            int d = Step.distance(t.toLowerCase(Locale.ROOT), m);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best == null ? " (#target list)" : " (a typo for the modifier @" + best + "?)";
    }

    /** What {@code #routine help} prints, one line each. */
    public static List<String> help(String p) {
        return List.of(
            "A routine step is a command plus modifiers: " + p + "getitem string 64 @retry 3 @timeout 5m @onfail continue @if inv.free>4",
            "@retry <n>  try again up to n times if it fails or times out",
            "@timeout <dur>  give up after 30s, 5m, 1h (a job with no end, like farm, simply ends then)",
            "@onfail stop|continue|skip-rest|goto <label>  what to do when it still failed (default continue)",
            "@times <n>  run the step n times in a row   @until <cond>  repeat (or run) until the condition holds",
            "@before <dur> / @after <dur>  wait before / after the step",
            "@if <cond> / @unless <cond>  only run when it holds / doesn't hold, else the step is skipped",
            "@with name=value ...  change settings for this step only (put back after)   @off  switch the step off",
            "Conditions: inv.<item>>n  chest.<item>>n  anywhere.<item>>n  inv.free>n (or: free slots > n)  inv.full  has.<item>  holding <item>  health>n  hunger>n  "
                + "armor durability<n  tool durability <item> < n  xp level>n  y level<n  time==day|night or time>=ticks  weather==rain|thunder|clear  dim==nether  "
                + "distance to @target (or x,y,z) < n  players nearby  hostile nearby  has effect <name>  last step failed|succeeded  loop count  random <percent>  busy  $variable>n; "
                + "compare with < <= > >= == !=, join with and, or, not, ( )",
            "Blocks (one step per line, 'end' closes the block, they nest): if <cond> ... else if <cond> ... else ... end   while <cond> ... end   repeat <n> ... end   break   continue",
            "Variables: set count = 0   set want = inv.iron_ingot * 2 - 5   add count (or add count 5); use $count in conditions and arguments (withdraw string $want); built in: $round $fails $loop. A plain 'end' outside blocks still ends the routine.",
            "A loop that goes round more than routineLoopLimit times (default 100000) stops the routine; " + p + "stop always works at once. " + p + "routine check <name> reports unbalanced blocks, unknown names and variables, with the line.",
            "Step words: wait <dur>, say <text>, notify <text>, skip (rest of the round), end, label <name>, jump <name> (or goto :<name>)",
            "@name in a command is a saved target (" + p + "target list): withdraw string all @duper",
            p + "routine show|edit|insert|remove|rename|copy|check|status <name> ...");
    }
}
