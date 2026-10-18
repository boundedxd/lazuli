package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A condition for a routine step or block ({@code @if}, {@code @unless}, {@code @until}, {@code if}, {@code while}):
 * {@code inv.free>4 and not night}. Parses from and prints to text without loss. Natural wording
 * ({@code free slots < 4}, {@code tool durability iron_pickaxe < 20}, {@code last step failed}) is read as the
 * dotted names and printed that way.
 */
public sealed interface Cond {
    boolean eval(World w);

    /** Canonical text; {@code parse(text())} gives an equal condition. */
    String text();

    /** What conditions look at (the game, or a test's pretend one). */
    interface World {
        /** Items of that kind in the inventory ({@code iron_ingot}, {@code minecraft:iron_ingot}). */
        int items(String item);

        /** Items of that kind in remembered chests. */
        int chestItems(String item);

        int freeSlots();

        double health();

        int hunger();

        boolean night();

        /** overworld, nether or end. */
        String dimension();

        boolean busy();

        /** Items in the inventory and remembered chests together. */
        default int anywhere(String item) {
            return items(item) + chestItems(item);
        }

        /** Percent (0-100) left on the most worn armor piece being worn (100: none worn). */
        default double armorDurability() {
            return 100;
        }

        /** Percent (0-100) left on the most worn stack of that tool in the inventory (0: you have none). */
        default double toolDurability(String item) {
            return 100;
        }

        default int xpLevel() {
            return 0;
        }

        /** Ticks into the day, 0-23999. */
        default int timeOfDay() {
            return night() ? 18000 : 6000;
        }

        default boolean raining() {
            return false;
        }

        default boolean thundering() {
            return false;
        }

        default int y() {
            return 64;
        }

        /** Blocks to a saved target ({@code @home}) or to {@code x,y,z}; a huge number when it isn't known. */
        default double distanceTo(String where) {
            return 1e9;
        }

        default int playersNearby() {
            return 0;
        }

        default int hostilesNearby() {
            return 0;
        }

        default boolean holding(String item) {
            return false;
        }

        default boolean hasEffect(String name) {
            return false;
        }

        /** The step that ran last failed (retries used up). */
        default boolean lastFailed() {
            return false;
        }

        /** The step that ran last went fine. */
        default boolean lastSucceeded() {
            return false;
        }

        /** The turn of the loop being run (1 for the first; 0 outside loops). */
        default int loopCount() {
            return 0;
        }

        /** A routine variable ({@code $name}, without the $); 0 when it was never set. */
        default double variable(String name) {
            return 0;
        }

        /** True {@code percent} times out of a hundred. */
        default boolean random(int percent) {
            return java.util.concurrent.ThreadLocalRandom.current().nextInt(100) < percent;
        }
    }

    Set<String> NUMBERS = Set.of("inv.free", "health", "hunger", "armor.durability", "xp", "y", "players", "hostiles", "loop");
    Set<String> FLAGS = Set.of("inv.full", "full", "day", "night", "busy", "rain", "raining", "thunder", "thundering",
        "last.failed", "last.ok", "players", "hostiles");
    List<String> OPS = List.of(">=", "<=", "!=", "==", ">", "<");

    record Cmp(String subject, String op, String value) implements Cond {
        @Override
        public boolean eval(World w) {
            String s = subject.toLowerCase(Locale.ROOT);
            if (s.equals("dim") || s.equals("dimension")) {
                boolean same = norm(w.dimension()).equals(norm(value));
                return op.equals("!=") != same;
            }
            if (s.equals("weather")) {
                boolean same = switch (value.toLowerCase(Locale.ROOT)) {
                    case "thunder" -> w.thundering();
                    case "rain" -> w.raining();
                    default -> !w.raining();
                };
                return op.equals("!=") != same;
            }
            if (s.equals("time") && (value.equalsIgnoreCase("day") || value.equalsIgnoreCase("night"))) {
                boolean same = value.equalsIgnoreCase("night") == w.night();
                return op.equals("!=") != same;
            }
            double have = number(s, w);
            double want = operand(value, w);
            return switch (op) {
                case ">" -> have > want;
                case ">=" -> have >= want;
                case "<" -> have < want;
                case "<=" -> have <= want;
                case "!=" -> have != want;
                default -> have == want;
            };
        }

        @Override
        public String text() {
            return subject + op + value;
        }
    }

    record Flag(String name) implements Cond {
        @Override
        public boolean eval(World w) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.startsWith("has.")) return w.items(n.substring(4)) > 0;
            if (n.startsWith("holding.")) return w.holding(n.substring(8));
            if (n.startsWith("effect.")) return w.hasEffect(n.substring(7));
            if (n.startsWith("random.")) return w.random(Integer.parseInt(n.substring(7)));
            if (n.startsWith("$")) return w.variable(n.substring(1)) != 0;
            return switch (n) {
                case "inv.full", "full" -> w.freeSlots() <= 0;
                case "day" -> !w.night();
                case "night" -> w.night();
                case "busy" -> w.busy();
                case "rain", "raining" -> w.raining();
                case "thunder", "thundering" -> w.thundering();
                case "last.failed" -> w.lastFailed();
                case "last.ok" -> w.lastSucceeded();
                case "players" -> w.playersNearby() > 0;
                case "hostiles" -> w.hostilesNearby() > 0;
                default -> false;
            };
        }

        @Override
        public String text() {
            return name;
        }
    }

    record Not(Cond c) implements Cond {
        @Override
        public boolean eval(World w) {
            return !c.eval(w);
        }

        @Override
        public String text() {
            return "not " + (c instanceof And || c instanceof Or ? "(" + c.text() + ")" : c.text());
        }
    }

    record And(List<Cond> all) implements Cond {
        public And {
            all = List.copyOf(all);
        }

        @Override
        public boolean eval(World w) {
            for (Cond c : all) if (!c.eval(w)) return false;
            return true;
        }

        @Override
        public String text() {
            List<String> parts = new ArrayList<>();
            for (Cond c : all) parts.add(c instanceof Or ? "(" + c.text() + ")" : c.text());
            return String.join(" and ", parts);
        }
    }

    record Or(List<Cond> any) implements Cond {
        public Or {
            any = List.copyOf(any);
        }

        @Override
        public boolean eval(World w) {
            for (Cond c : any) if (c.eval(w)) return true;
            return false;
        }

        @Override
        public String text() {
            List<String> parts = new ArrayList<>();
            for (Cond c : any) parts.add(c.text());
            return String.join(" or ", parts);
        }
    }

    private static String norm(String dim) {
        String d = dim.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return switch (d) {
            case "the_nether", "nether" -> "nether";
            case "the_end", "end" -> "end";
            default -> d;
        };
    }

    /** The value of a numeric subject ({@code inv.free}, {@code inv.iron_ingot}, {@code $count}...). */
    static double number(String subject, World w) {
        String s = subject.toLowerCase(Locale.ROOT);
        if (s.startsWith("$")) return w.variable(s.substring(1));
        if (s.startsWith("chest.")) return w.chestItems(s.substring(6));
        if (s.startsWith("anywhere.")) return w.anywhere(s.substring(9));
        if (s.startsWith("tool.")) return w.toolDurability(s.substring(5));
        if (s.startsWith("dist.")) return w.distanceTo(s.substring(5));
        return switch (s) {
            case "inv.free" -> w.freeSlots();
            case "health" -> w.health();
            case "hunger" -> w.hunger();
            case "armor.durability" -> w.armorDurability();
            case "xp" -> w.xpLevel();
            case "y" -> w.y();
            case "time" -> w.timeOfDay();
            case "players" -> w.playersNearby();
            case "hostiles" -> w.hostilesNearby();
            case "loop" -> w.loopCount();
            default -> s.startsWith("inv.") ? w.items(s.substring(4)) : 0;
        };
    }

    /** A number, a {@code $variable} or a numeric subject, as a number. */
    private static double operand(String v, World w) {
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return number(v, w);
        }
    }

    /** True for names that give a number ({@code inv.free}, {@code tool.iron_pickaxe}, {@code $x}...). */
    static boolean numeric(String lower) {
        return NUMBERS.contains(lower) || lower.equals("time")
            || lower.startsWith("$") && lower.length() > 1 || lower.startsWith("inv.") && lower.length() > 4
            || lower.startsWith("chest.") && lower.length() > 6 || lower.startsWith("anywhere.") && lower.length() > 9
            || lower.startsWith("tool.") && lower.length() > 5 || lower.startsWith("dist.") && lower.length() > 5;
    }

    String TOK = "([^\\s<>=!()]+)";
    int CI = Pattern.CASE_INSENSITIVE;
    List<Pattern> PHRASES = List.of(
        Pattern.compile("\\bhas\\s+effect\\s+" + TOK, CI),
        Pattern.compile("\\bfree\\s+slots\\b", CI),
        Pattern.compile("\\barmou?r\\s+durability\\b", CI),
        Pattern.compile("\\btool\\s+durability\\s+" + TOK, CI),
        Pattern.compile("\\bxp\\s+level\\b", CI),
        Pattern.compile("\\by\\s+level\\b", CI),
        Pattern.compile("\\bdistance\\s+to\\s+" + TOK, CI),
        Pattern.compile("\\bplayers?\\s+nearby\\b", CI),
        Pattern.compile("\\bhostiles?\\s+nearby\\b", CI),
        Pattern.compile("\\bholding\\s+" + TOK, CI),
        Pattern.compile("\\blast\\s+step\\s+failed\\b", CI),
        Pattern.compile("\\blast\\s+step\\s+(?:succeeded|ok)\\b", CI),
        Pattern.compile("\\bloop\\s+count\\b", CI),
        Pattern.compile("\\brandom\\s+([0-9]+)\\s*%?", CI));
    String[] PHRASE_TO = {"effect.$1", "inv.free", "armor.durability", "tool.$1", "xp", "y", "dist.$1",
        "players", "hostiles", "holding.$1", "last.failed", "last.ok", "loop", "random.$1"};

    /** The natural wording ({@code free slots}, {@code tool durability iron_pickaxe}...) as the dotted names the parser knows. */
    static String normalize(String text) {
        String t = text;
        for (int i = 0; i < PHRASES.size(); i++) t = PHRASES.get(i).matcher(t).replaceAll(PHRASE_TO[i]);
        return t;
    }

    /** Throws IllegalArgumentException with a readable message. */
    static Cond parse(String text) {
        List<String> t = tokens(normalize(text));
        if (t.isEmpty()) throw new IllegalArgumentException("empty condition");
        int[] at = {0};
        Cond c = or(t, at);
        if (at[0] < t.size()) throw new IllegalArgumentException("unexpected '" + t.get(at[0]) + "' in condition '" + text.trim() + "'");
        return c;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '(' || c == ')') {
                out.add(String.valueOf(c));
                i++;
            } else if (c == '>' || c == '<' || c == '=' || c == '!') {
                int j = i + 1;
                if (j < n && text.charAt(j) == '=') j++;
                out.add(text.substring(i, j));
                i = j;
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(text.charAt(j)) && "()<>=!".indexOf(text.charAt(j)) < 0) j++;
                out.add(text.substring(i, j));
                i = j;
            }
        }
        return out;
    }

    private static Cond or(List<String> t, int[] at) {
        List<Cond> parts = new ArrayList<>();
        parts.add(and(t, at));
        while (at[0] < t.size() && t.get(at[0]).equalsIgnoreCase("or")) {
            at[0]++;
            parts.add(and(t, at));
        }
        return parts.size() == 1 ? parts.get(0) : new Or(parts);
    }

    private static Cond and(List<String> t, int[] at) {
        List<Cond> parts = new ArrayList<>();
        parts.add(unary(t, at));
        while (at[0] < t.size() && t.get(at[0]).equalsIgnoreCase("and")) {
            at[0]++;
            parts.add(unary(t, at));
        }
        return parts.size() == 1 ? parts.get(0) : new And(parts);
    }

    private static Cond unary(List<String> t, int[] at) {
        if (at[0] >= t.size()) throw new IllegalArgumentException("condition ends too soon (something missing after and/or/not)");
        String w = t.get(at[0]);
        if (w.equalsIgnoreCase("not")) {
            at[0]++;
            return new Not(unary(t, at));
        }
        if (w.equals("(")) {
            at[0]++;
            Cond c = or(t, at);
            if (at[0] >= t.size() || !t.get(at[0]).equals(")")) throw new IllegalArgumentException("missing ) in condition");
            at[0]++;
            return c;
        }
        if (w.equals(")") || OPS.contains(w) || w.equals("=") || w.equalsIgnoreCase("and") || w.equalsIgnoreCase("or"))
            throw new IllegalArgumentException("unexpected '" + w + "' in condition");
        at[0]++;
        String name = w;
        String lower = name.toLowerCase(Locale.ROOT);
        boolean hasOp = at[0] < t.size() && (OPS.contains(t.get(at[0])) || t.get(at[0]).equals("="));
        if (!hasOp) {
            if (FLAGS.contains(lower) || lower.startsWith("has.") && lower.length() > 4 || lower.startsWith("holding.") && lower.length() > 8
                || lower.startsWith("effect.") && lower.length() > 7 || lower.startsWith("$") && lower.length() > 1) return new Flag(name);
            if (lower.matches("random\\.[0-9]+")) {
                if (lower.length() > 10 || Integer.parseInt(lower.substring(7)) > 100)
                    throw new IllegalArgumentException("random takes a percent, 0 to 100");
                return new Flag(name);
            }
            if (known(lower)) throw new IllegalArgumentException("'" + name + "' needs a comparison, e.g. " + name + ">4");
            throw new IllegalArgumentException(unknownName(name));
        }
        String op = t.get(at[0]++);
        if (op.equals("=")) op = "==";
        if (at[0] >= t.size()) throw new IllegalArgumentException("'" + name + op + "' needs a value");
        String value = t.get(at[0]++);
        if (!known(lower)) throw new IllegalArgumentException(unknownName(name));
        if (lower.equals("dim") || lower.equals("dimension")) {
            if (!op.equals("==") && !op.equals("!=")) throw new IllegalArgumentException("dim only takes == or !=");
            if (!Set.of("overworld", "nether", "end").contains(value.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("dim must be overworld, nether or end, not '" + value + "'");
            value = value.toLowerCase(Locale.ROOT);
        } else if (lower.equals("weather")) {
            if (!op.equals("==") && !op.equals("!=")) throw new IllegalArgumentException("weather only takes == or !=");
            if (!Set.of("clear", "rain", "thunder").contains(value.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("weather must be clear, rain or thunder, not '" + value + "'");
            value = value.toLowerCase(Locale.ROOT);
        } else if (lower.equals("time") && (value.equalsIgnoreCase("day") || value.equalsIgnoreCase("night"))) {
            if (!op.equals("==") && !op.equals("!=")) throw new IllegalArgumentException("time only takes == or !=");
            value = value.toLowerCase(Locale.ROOT);
        } else {
            boolean ok = numeric(value.toLowerCase(Locale.ROOT)) && !value.equalsIgnoreCase("time");
            if (!ok) {
                try {
                    Double.parseDouble(value);
                    ok = true;
                } catch (NumberFormatException e) {
                    // the error below
                }
            }
            if (!ok) {
                if (lower.equals("time"))
                    throw new IllegalArgumentException("time must be day or night (or a tick number), not '" + value + "'");
                throw new IllegalArgumentException("'" + name + op + value + "': '" + value + "' must be a number");
            }
        }
        return new Cmp(name, op, value);
    }

    private static boolean known(String lower) {
        return numeric(lower) || lower.equals("dim") || lower.equals("dimension") || lower.equals("weather");
    }

    private static String unknownName(String name) {
        return "unknown condition '" + name + "' (try inv.<item>, chest.<item>, anywhere.<item>, inv.free, inv.full, has.<item>, "
            + "holding.<item>, health, hunger, armor.durability, tool.<item>, xp, y, time, weather, dim, dist.<target>, players, "
            + "hostiles, effect.<name>, last.failed, last.ok, busy, loop, random.<percent>, $variable, day, night)";
    }
}
