package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the routine editor needs to know about each command: which fields its arguments have (an item and a count,
 * coordinates, a number...), and how to read those fields out of the argument text and write them back. A command
 * whose arguments don't fit its fields (several items, a waypoint name...) just isn't "simple": the editor then
 * leaves the text alone.
 */
public final class Forms {
    private Forms() {
    }

    public enum Layout {
        /** No arguments. */
        NONE,
        /** {@code <item> [count]} */
        ITEM_COUNT,
        /** {@code <item|all> [count]} */
        ITEM_ALL_COUNT,
        /** {@code x y z}, or a target {@code @name}. */
        COORDS,
        /** {@code [count] <block>} (mine). */
        BLOCK_COUNT,
        /** {@code [number]} */
        NUMBER,
        /** A duration (wait). */
        DURATION,
        /** Any text (say, notify). */
        TEXT,
        /** A label name. */
        LABEL,
        /** Anything else: only the text. */
        RAW
    }

    /** {@code numberLabel}: what the NUMBER is ("Range", "Minutes"); {@code max}: its largest sensible value. */
    public record Spec(String word, String title, Layout layout, String numberLabel, int max) {
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private static void add(String word, String title, Layout layout) {
        SPECS.put(word, new Spec(word, title, layout, "Amount", 100000));
    }

    private static void number(String word, String title, String label, int max) {
        SPECS.put(word, new Spec(word, title, Layout.NUMBER, label, max));
    }

    static {
        add("goto", "Go to", Layout.COORDS);
        add("fly", "Fly to", Layout.COORDS);
        add("mine", "Mine", Layout.BLOCK_COUNT);
        add("getitem", "Get item", Layout.ITEM_COUNT);
        add("craft", "Craft", Layout.ITEM_COUNT);
        add("smelt", "Smelt", Layout.ITEM_COUNT);
        add("brew", "Brew", Layout.ITEM_COUNT);
        add("hunt", "Hunt for", Layout.ITEM_COUNT);
        add("smith", "Smith", Layout.ITEM_COUNT);
        add("enchant", "Enchant", Layout.ITEM_COUNT);
        add("bucket", "Fill bucket", Layout.RAW);
        add("deposit", "Deposit", Layout.ITEM_ALL_COUNT);
        add("withdraw", "Withdraw", Layout.ITEM_ALL_COUNT);
        add("drop", "Drop", Layout.ITEM_ALL_COUNT);
        add("find", "Find in chests", Layout.ITEM_COUNT);
        add("trade", "Trade", Layout.RAW);
        add("repair", "Repair", Layout.RAW);
        add("combine", "Combine", Layout.RAW);
        number("farm", "Farm", "Range", 64);
        number("fish", "Fish", "Minutes", 1440);
        number("sort", "Sort chests", "Range", 64);
        add("tunnel", "Tunnel", Layout.RAW);
        add("explore", "Explore", Layout.RAW);
        add("follow", "Follow", Layout.RAW);
        add("breed", "Breed", Layout.RAW);
        add("build", "Build schematic", Layout.RAW);
        add("portal", "Go through portal", Layout.NONE);
        add("stronghold", "Find stronghold", Layout.NONE);
        add("sleep", "Sleep", Layout.NONE);
        add("come", "Come here", Layout.NONE);
        add("back", "Go back", Layout.NONE);
        add("stop", "Stop", Layout.NONE);
        add("set", "Change setting", Layout.RAW);
        add("wait", "Wait", Layout.DURATION);
        add("say", "Say in chat", Layout.TEXT);
        add("notify", "Tell me", Layout.TEXT);
        add("label", "Label", Layout.LABEL);
        add("jump", "Jump to label", Layout.LABEL);
        add("skip", "Skip rest of round", Layout.NONE);
        add("end", "End routine", Layout.NONE);
        add("if", "If", Layout.RAW);
        add("else", "Else / else if", Layout.RAW);
        add("while", "While", Layout.RAW);
        add("repeat", "Repeat", Layout.RAW);
        add("setvar", "Set variable", Layout.RAW);
        add("addvar", "Add to variable", Layout.RAW);
        add("break", "Break (leave the loop)", Layout.NONE);
        add("continue", "Continue (next turn)", Layout.NONE);
    }

    /** The picker words that make a block (an {@code if}, {@code while} or {@code repeat} with its {@code end}). */
    public static boolean blockWord(String word) {
        return word.equals("if") || word.equals("while") || word.equals("repeat");
    }

    /** The commands the picker offers, in order. */
    public static List<String> words() {
        return List.copyOf(SPECS.keySet());
    }

    /** The layout of a command word; RAW for one that isn't listed. */
    public static Spec spec(String word) {
        Spec s = SPECS.get(word.toLowerCase(Locale.ROOT));
        return s != null ? s : new Spec(word, word, Layout.RAW, "Amount", 100000);
    }

    /** True for the words that aren't commands (wait, say, label...): they're written without the prefix. */
    public static boolean stepWord(String word) {
        return switch (word) {
            case "wait", "say", "notify", "skip", "end", "label", "jump", "if", "else", "while", "repeat", "setvar", "addvar",
                 "break", "continue" -> true;
            default -> false;
        };
    }

    /** A step's command text from its word and arguments, with the command prefix when it's a command. */
    public static String command(String prefix, String word, String args) {
        String head = stepWord(word) ? word : prefix + word;
        return args == null || args.isBlank() ? head : head + " " + args.trim();
    }

    /** A fresh step for a word chosen from the picker. */
    public static String defaultCommand(String prefix, String word) {
        return switch (word) {
            case "wait" -> "wait 30s";
            case "say" -> "say hello";
            case "notify" -> "notify done";
            case "label" -> "label here";
            case "jump" -> "jump here";
            case "if" -> "if night";
            case "else" -> "else";
            case "while" -> "while inv.free>0";
            case "repeat" -> "repeat 3";
            case "setvar" -> "set count = 0";
            case "addvar" -> "add count 1";
            case "break", "continue" -> word;
            default -> command(prefix, word, "");
        };
    }

    // ---- argument fields ------------------------------------------------------------------------------------

    /** An item, how many (0: none given) and a target name that comes last ("" when none): {@code withdraw string 64 @duper}. */
    public record ItemArg(String item, int count, String target) {
        public ItemArg(String item, int count) {
            this(item, count, "");
        }
    }

    /** The commands whose work place can be a target (a chest to deposit into, a brewing stand to brew at). */
    public static boolean takesTarget(String word) {
        return switch (word) {
            case "deposit", "withdraw", "craft", "smelt", "brew", "enchant", "repair", "combine", "smith" -> true;
            default -> false;
        };
    }

    private static boolean isNumber(String s) {
        return s.matches("[0-9]+");
    }

    /** Reads {@code <item> [count]}; null when the text isn't that (more items, odd words). */
    public static ItemArg parseItem(String args) {
        String[] t = args.isBlank() ? new String[0] : args.trim().split("\\s+");
        String item = "", target = "";
        int count = 0;
        for (int i = 0; i < t.length; i++) {
            String w = t[i];
            if (w.matches("@[A-Za-z0-9_][A-Za-z0-9_.-]*") && i == t.length - 1 && target.isEmpty()) target = w.substring(1);
            else if (isNumber(w) && count == 0 && !item.isEmpty()) count = Integer.parseInt(w);
            else if (item.isEmpty() && !isNumber(w) && !w.startsWith("@") && !w.startsWith("~")) item = w;
            else return null;
        }
        return new ItemArg(item, count, target);
    }

    public static String compose(ItemArg a) {
        String base = (a.item().isEmpty() ? "" : a.item() + (a.count() > 0 ? " " + a.count() : "")).trim();
        return a.target().isEmpty() ? base : (base + " @" + a.target()).trim();
    }

    /** Reads {@code [count] <block>} (mine); null when it's something else. */
    public static ItemArg parseBlock(String args) {
        String[] t = args.isBlank() ? new String[0] : args.trim().split("\\s+");
        String item = "";
        int count = 0;
        for (String w : t) {
            if (isNumber(w) && count == 0) count = Integer.parseInt(w);
            else if (item.isEmpty() && !isNumber(w)) item = w;
            else return null;
        }
        return new ItemArg(item, count);
    }

    public static String composeBlock(ItemArg a) {
        return ((a.count() > 0 ? a.count() + " " : "") + a.item()).trim();
    }

    /** Coordinates, or a target name ({@code @duper}); {@code xyz} null when it's a target. */
    public record Where(int[] xyz, String target) {
    }

    /** Reads {@code x y z} (numbers or ~) or {@code @name}; null when it's something else. */
    public static Where parseWhere(String args) {
        String t = args.trim();
        if (t.isEmpty()) return new Where(new int[]{0, 0, 0}, "");
        if (t.matches("@[A-Za-z][\\w-]*")) return new Where(null, t.substring(1));
        String[] w = t.split("\\s+");
        if (w.length != 3) return null;
        int[] xyz = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!w[i].matches("-?[0-9]+")) return null;
            try {
                xyz[i] = Integer.parseInt(w[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new Where(xyz, "");
    }

    public static String compose(Where w) {
        if (w.xyz() == null) return "@" + w.target();
        return w.xyz()[0] + " " + w.xyz()[1] + " " + w.xyz()[2];
    }

    /** Reads an optional whole number; -1 when the text isn't one (0: none given). */
    public static int parseNumber(String args) {
        String t = args.trim();
        if (t.isEmpty()) return 0;
        return isNumber(t) && t.length() < 9 ? Integer.parseInt(t) : -1;
    }
}
