package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One step of a routine: a command (or a step word: wait, say, notify, skip, end, label, jump) plus modifiers, in the
 * text form {@code getitem string 64 @retry 3 @timeout 5m @onfail continue @if inv.free>4 @with mineDropJunk=false}.
 * {@link #parse} and {@link #text} are each other's inverse, so the GUI edits the record and the file stays readable.
 * An {@code @word} that is not a modifier word, before the first modifier, belongs to the command (a target name).
 */
public record Step(String command, int retry, int timeoutTicks, OnFail onFail, String failLabel, int times,
                   int beforeTicks, int afterTicks, Cond ifCond, Cond unlessCond, Cond untilCond,
                   Map<String, String> with, boolean enabled) {

    public enum OnFail {
        /** Go on with the next step (what routines always did). */
        CONTINUE("continue"),
        /** End the whole routine. */
        STOP("stop"),
        /** Give up the rest of this round, start the next. */
        SKIP_REST("skip-rest"),
        /** Jump to a label. */
        GOTO("goto");

        public final String word;

        OnFail(String word) {
            this.word = word;
        }

        static OnFail of(String w) {
            return switch (w.toLowerCase(Locale.ROOT)) {
                case "continue", "go", "next" -> CONTINUE;
                case "stop", "abort" -> STOP;
                case "skip-rest", "skiprest", "skip_rest", "skip" -> SKIP_REST;
                case "goto", "jump" -> GOTO;
                default -> null;
            };
        }
    }

    public enum Kind { COMMAND, WAIT, SAY, NOTIFY, SKIP, END, LABEL, JUMP }

    /** The modifier words (anything else after the first of these is an error). */
    public static final List<String> MODIFIERS = List.of("retry", "timeout", "onfail", "times", "before", "after",
        "if", "unless", "until", "with", "off");
    private static final Set<String> MOD_SET = Set.copyOf(MODIFIERS);

    public Step {
        command = command.trim();
        with = Map.copyOf(new LinkedHashMap<>(with));
    }

    public static Step of(String command) {
        return new Step(command, 0, 0, OnFail.CONTINUE, "", 1, 0, 0, null, null, null, Map.of(), true);
    }

    // ---- what the command is -------------------------------------------------------------------------------

    /** The first word of the command with a leading command prefix (#, /, ...) removed, lower case. */
    public String word() {
        String c = command.replaceFirst("^[^a-zA-Z:]+", "");
        int sp = c.indexOf(' ');
        return (sp < 0 ? c : c.substring(0, sp)).toLowerCase(Locale.ROOT);
    }

    /** Everything after the first word. */
    public String args() {
        String c = command.replaceFirst("^[^a-zA-Z:]+", "");
        int sp = c.indexOf(' ');
        return sp < 0 ? "" : c.substring(sp + 1).trim();
    }

    public Kind kind() {
        String w = word();
        return switch (w) {
            case "wait" -> Kind.WAIT;
            case "say" -> Kind.SAY;
            case "notify" -> Kind.NOTIFY;
            case "skip" -> Kind.SKIP;
            case "end" -> Kind.END;
            case "label" -> Kind.LABEL;
            case "jump" -> Kind.JUMP;
            default -> w.equals("goto") && args().startsWith(":") ? Kind.JUMP : Kind.COMMAND;
        };
    }

    /** The label a LABEL or JUMP step names. */
    public String label() {
        String a = args();
        return a.startsWith(":") ? a.substring(1).trim() : a;
    }

    /** True when the step cares about failing: retries or an on-fail other than going on. */
    public boolean caresAboutFailure() {
        return retry > 0 || onFail != OnFail.CONTINUE;
    }

    // ---- editing (each returns a changed copy) ------------------------------------------------------------

    public Step withCommand(String c) {
        return new Step(c, retry, timeoutTicks, onFail, failLabel, times, beforeTicks, afterTicks, ifCond, unlessCond, untilCond, with, enabled);
    }

    public Step withRetry(int n) {
        return new Step(command, Math.max(0, n), timeoutTicks, onFail, failLabel, times, beforeTicks, afterTicks, ifCond, unlessCond, untilCond, with, enabled);
    }

    public Step withTimeout(int ticks) {
        return new Step(command, retry, Math.max(0, ticks), onFail, failLabel, times, beforeTicks, afterTicks, ifCond, unlessCond, untilCond, with, enabled);
    }

    public Step withOnFail(OnFail f, String label) {
        return new Step(command, retry, timeoutTicks, f, f == OnFail.GOTO && label != null ? label : "", times, beforeTicks, afterTicks, ifCond, unlessCond, untilCond, with, enabled);
    }

    public Step withTimes(int n) {
        return new Step(command, retry, timeoutTicks, onFail, failLabel, Math.max(1, n), beforeTicks, afterTicks, ifCond, unlessCond, untilCond, with, enabled);
    }

    public Step withWaits(int before, int after) {
        return new Step(command, retry, timeoutTicks, onFail, failLabel, times, Math.max(0, before), Math.max(0, after), ifCond, unlessCond, untilCond, with, enabled);
    }

    public Step withConds(Cond ifC, Cond unlessC, Cond untilC) {
        return new Step(command, retry, timeoutTicks, onFail, failLabel, times, beforeTicks, afterTicks, ifC, unlessC, untilC, with, enabled);
    }

    public Step withSettings(Map<String, String> w) {
        return new Step(command, retry, timeoutTicks, onFail, failLabel, times, beforeTicks, afterTicks, ifCond, unlessCond, untilCond, w, enabled);
    }

    public Step withEnabled(boolean on) {
        return new Step(command, retry, timeoutTicks, onFail, failLabel, times, beforeTicks, afterTicks, ifCond, unlessCond, untilCond, with, on);
    }

    // ---- text ----------------------------------------------------------------------------------------------

    /** The text form; {@code parse(text())} equals this. */
    public String text() {
        StringBuilder b = new StringBuilder(command);
        if (retry > 0) b.append(" @retry ").append(retry);
        if (timeoutTicks > 0) b.append(" @timeout ").append(durationText(timeoutTicks));
        if (onFail != OnFail.CONTINUE) {
            b.append(" @onfail ").append(onFail.word);
            if (onFail == OnFail.GOTO) b.append(' ').append(failLabel);
        }
        if (times > 1) b.append(" @times ").append(times);
        if (beforeTicks > 0) b.append(" @before ").append(durationText(beforeTicks));
        if (afterTicks > 0) b.append(" @after ").append(durationText(afterTicks));
        if (ifCond != null) b.append(" @if ").append(ifCond.text());
        if (unlessCond != null) b.append(" @unless ").append(unlessCond.text());
        if (untilCond != null) b.append(" @until ").append(untilCond.text());
        if (!with.isEmpty()) {
            b.append(" @with");
            with.forEach((k, v) -> b.append(' ').append(k).append('=').append(v));
        }
        if (!enabled) b.append(" @off");
        return b.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    /** "5m", "30s", "1h", "50t" (ticks). */
    public static String durationText(int ticks) {
        if (ticks % 72000 == 0) return ticks / 72000 + "h";
        if (ticks % 1200 == 0) return ticks / 1200 + "m";
        if (ticks % 20 == 0) return ticks / 20 + "s";
        return ticks + "t";
    }

    /** "90", "30s", "5m", "1h30m", "40t" into ticks (a bare number is seconds). */
    public static int parseDuration(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (!t.matches("([0-9]+[hmst]?)+")) throw new IllegalArgumentException("'" + s + "' isn't a duration (like 30s, 5m, 1h, 40t)");
        long total = 0;
        var m = java.util.regex.Pattern.compile("([0-9]+)([hmst]?)").matcher(t);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total += switch (m.group(2)) {
                case "h" -> n * 72000;
                case "m" -> n * 1200;
                case "t" -> n;
                default -> n * 20;
            };
        }
        if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("'" + s + "' is too long");
        return (int) total;
    }

    /** Parses a step line; throws IllegalArgumentException with a clear message. */
    public static Step parse(String line) {
        String text = line.trim();
        if (text.isEmpty()) throw new IllegalArgumentException("empty step");
        // the command runs up to the first modifier word
        List<int[]> tokens = new ArrayList<>(); // start, end
        int i = 0;
        while (i < text.length()) {
            if (Character.isWhitespace(text.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < text.length() && !Character.isWhitespace(text.charAt(j))) j++;
            tokens.add(new int[]{i, j});
            i = j;
        }
        int first = -1;
        for (int k = 0; k < tokens.size() && first < 0; k++) {
            String w = text.substring(tokens.get(k)[0], tokens.get(k)[1]);
            if (w.startsWith("@") && MOD_SET.contains(w.substring(1).toLowerCase(Locale.ROOT))) first = k;
        }
        String command = first < 0 ? text : text.substring(0, tokens.get(first)[0]).trim();
        if (command.isEmpty()) throw new IllegalArgumentException("step has modifiers but no command: '" + text + "'");
        Step s = of(command);
        if (first >= 0) {
            List<String> words = new ArrayList<>();
            for (int k = first; k < tokens.size(); k++) words.add(text.substring(tokens.get(k)[0], tokens.get(k)[1]));
            s = applyModifiers(s, words);
        }
        s.validateKind();
        return s;
    }

    private static Step applyModifiers(Step s, List<String> words) {
        int n = words.size();
        int at = 0;
        int retry = 0, timeout = 0, times = 1, before = 0, after = 0;
        OnFail onFail = OnFail.CONTINUE;
        String failLabel = "";
        Cond ifC = null, unlessC = null, untilC = null;
        Map<String, String> with = new LinkedHashMap<>();
        boolean enabled = true;
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (at < n) {
            String w = words.get(at);
            if (!w.startsWith("@")) throw new IllegalArgumentException("unexpected '" + w + "' (modifiers start with @; known: " + known() + ")");
            String mod = w.substring(1).toLowerCase(Locale.ROOT);
            if (!MOD_SET.contains(mod))
                throw new IllegalArgumentException("unknown modifier @" + mod + hint(mod) + " (known: " + known() + ")");
            at++;
            // the values: everything up to the next modifier word
            List<String> vals = new ArrayList<>();
            while (at < n && !(words.get(at).startsWith("@") && isModWord(words.get(at)))) {
                if (words.get(at).startsWith("@") && !isModWord(words.get(at)))
                    throw new IllegalArgumentException("unknown modifier " + words.get(at) + hint(words.get(at).substring(1).toLowerCase(Locale.ROOT)) + " (known: " + known() + ")");
                vals.add(words.get(at++));
            }
            if (!seen.add(mod) && !mod.equals("with")) throw new IllegalArgumentException("@" + mod + " given twice");
            switch (mod) {
                case "off" -> {
                    if (!vals.isEmpty()) throw new IllegalArgumentException("@off takes no value, got '" + String.join(" ", vals) + "'");
                    enabled = false;
                }
                case "retry" -> retry = number(mod, vals, 0, 1000);
                case "times" -> times = number(mod, vals, 1, 100000);
                case "timeout" -> timeout = duration(mod, vals);
                case "before" -> before = duration(mod, vals);
                case "after" -> after = duration(mod, vals);
                case "onfail" -> {
                    if (vals.isEmpty()) throw new IllegalArgumentException("@onfail needs stop, continue, skip-rest or goto <label>");
                    OnFail f = OnFail.of(vals.get(0));
                    if (f == null) throw new IllegalArgumentException("@onfail " + vals.get(0) + ": use stop, continue, skip-rest or goto <label>");
                    if (f == OnFail.GOTO) {
                        if (vals.size() != 2) throw new IllegalArgumentException("@onfail goto needs one label, e.g. @onfail goto restock");
                        failLabel = vals.get(1).startsWith(":") ? vals.get(1).substring(1) : vals.get(1);
                    } else if (vals.size() != 1) {
                        throw new IllegalArgumentException("@onfail " + vals.get(0) + " takes no more words, got '" + String.join(" ", vals.subList(1, vals.size())) + "'");
                    }
                    onFail = f;
                }
                case "if", "unless", "until" -> {
                    if (vals.isEmpty()) throw new IllegalArgumentException("@" + mod + " needs a condition, e.g. @" + mod + " inv.free>4");
                    Cond c;
                    try {
                        c = Cond.parse(String.join(" ", vals));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("@" + mod + ": " + e.getMessage());
                    }
                    switch (mod) {
                        case "if" -> ifC = c;
                        case "unless" -> unlessC = c;
                        default -> untilC = c;
                    }
                }
                case "with" -> {
                    if (vals.isEmpty()) throw new IllegalArgumentException("@with needs name=value, e.g. @with mineDropJunk=false");
                    for (String v : vals) {
                        int eq = v.indexOf('=');
                        if (eq <= 0 || eq == v.length() - 1) throw new IllegalArgumentException("@with: '" + v + "' must look like name=value");
                        with.put(v.substring(0, eq), v.substring(eq + 1));
                    }
                }
                default -> throw new IllegalStateException(mod);
            }
        }
        return new Step(s.command, retry, timeout, onFail, failLabel, times, before, after, ifC, unlessC, untilC, with, enabled);
    }

    private static boolean isModWord(String w) {
        return MOD_SET.contains(w.substring(1).toLowerCase(Locale.ROOT));
    }

    private static String known() {
        return "@" + String.join(" @", MODIFIERS);
    }

    private static String hint(String mod) {
        String best = null;
        int bestD = 3;
        for (String m : MODIFIERS) {
            int d = distance(mod, m);
            if (d < bestD) {
                bestD = d;
                best = m;
            }
        }
        return best == null ? "" : ", did you mean @" + best + "?";
    }

    /** Edit distance (small strings). */
    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            prev = cur;
        }
        return prev[b.length()];
    }

    private static int number(String mod, List<String> vals, int min, int max) {
        if (vals.size() != 1) throw new IllegalArgumentException("@" + mod + " needs one number, e.g. @" + mod + " 3");
        int v;
        try {
            v = Integer.parseInt(vals.get(0));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("@" + mod + " '" + vals.get(0) + "' must be a whole number");
        }
        if (v < min || v > max) throw new IllegalArgumentException("@" + mod + " must be between " + min + " and " + max);
        return v;
    }

    private static int duration(String mod, List<String> vals) {
        if (vals.size() != 1) throw new IllegalArgumentException("@" + mod + " needs one duration, e.g. @" + mod + " 5m");
        try {
            return parseDuration(vals.get(0));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("@" + mod + ": " + e.getMessage());
        }
    }

    /** Step words need their argument. */
    private void validateKind() {
        switch (kind()) {
            case WAIT -> {
                if (args().isEmpty()) throw new IllegalArgumentException("wait needs a duration, e.g. wait 30s");
                try {
                    parseDuration(args());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("wait: " + e.getMessage());
                }
            }
            case SAY, NOTIFY -> {
                if (args().isEmpty()) throw new IllegalArgumentException(word() + " needs some text, e.g. " + word() + " hello");
            }
            case LABEL, JUMP -> {
                String l = label();
                if (l.isEmpty() || l.contains(" ")) throw new IllegalArgumentException(word() + " needs one label name, e.g. " + word() + " restock");
            }
            case SKIP, END -> {
                if (!args().isEmpty()) throw new IllegalArgumentException(word() + " takes no arguments, got '" + args() + "'");
            }
            default -> { }
        }
    }

    /** Ticks a WAIT step waits. */
    public int waitTicks() {
        return parseDuration(args());
    }
}
