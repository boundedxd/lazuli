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

    private static final Pattern TARGET = Pattern.compile("(?<![\\w@])@([A-Za-z][\\w-]*)");

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
        List<String> out = new ArrayList<>();
        List<Step> steps = new ArrayList<>();
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
        out.addAll(labelProblems(steps));
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
            "Conditions: inv.<item>>n  chest.<item>>n  inv.free>n  inv.full  has.<item>  health>n  hunger>n  day  night  time==day  dim==nether  busy; join with and, or, not, ( )",
            "Step words: wait <dur>, say <text>, notify <text>, skip (rest of the round), end, label <name>, jump <name> (or goto :<name>)",
            "@name in a command is a saved target (" + p + "target list): withdraw string all @duper",
            p + "routine show|edit|insert|remove|rename|copy|check|status <name> ...");
    }
}
