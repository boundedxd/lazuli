package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A condition for a routine step ({@code @if}, {@code @unless}, {@code @until}): {@code inv.free>4 and not night}.
 * Parses from and prints to text without loss.
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
    }

    Set<String> NUMBERS = Set.of("inv.free", "health", "hunger");
    Set<String> FLAGS = Set.of("inv.full", "full", "day", "night", "busy");
    List<String> OPS = List.of(">=", "<=", "!=", "==", ">", "<");

    record Cmp(String subject, String op, String value) implements Cond {
        @Override
        public boolean eval(World w) {
            String s = subject.toLowerCase(Locale.ROOT);
            if (s.equals("dim") || s.equals("dimension")) {
                boolean same = norm(w.dimension()).equals(norm(value));
                return op.equals("!=") != same;
            }
            if (s.equals("time")) {
                boolean same = value.equalsIgnoreCase("night") == w.night();
                return op.equals("!=") != same;
            }
            double have = number(s, w);
            double want = Double.parseDouble(value);
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
            return switch (n) {
                case "inv.full", "full" -> w.freeSlots() <= 0;
                case "day" -> !w.night();
                case "night" -> w.night();
                case "busy" -> w.busy();
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

    private static double number(String subject, World w) {
        if (subject.startsWith("chest.")) return w.chestItems(subject.substring(6));
        return switch (subject) {
            case "inv.free" -> w.freeSlots();
            case "health" -> w.health();
            case "hunger" -> w.hunger();
            default -> subject.startsWith("inv.") ? w.items(subject.substring(4)) : 0;
        };
    }

    /** Throws IllegalArgumentException with a readable message. */
    static Cond parse(String text) {
        List<String> t = tokens(text);
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
            if (FLAGS.contains(lower) || lower.startsWith("has.") && lower.length() > 4) return new Flag(name);
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
        } else if (lower.equals("time")) {
            if (!op.equals("==") && !op.equals("!=")) throw new IllegalArgumentException("time only takes == or !=");
            if (!value.equalsIgnoreCase("day") && !value.equalsIgnoreCase("night"))
                throw new IllegalArgumentException("time must be day or night, not '" + value + "'");
            value = value.toLowerCase(Locale.ROOT);
        } else {
            try {
                Double.parseDouble(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + name + op + value + "': '" + value + "' must be a number");
            }
        }
        return new Cmp(name, op, value);
    }

    private static boolean known(String lower) {
        return NUMBERS.contains(lower) || lower.equals("dim") || lower.equals("dimension") || lower.equals("time")
            || lower.startsWith("inv.") && lower.length() > 4 || lower.startsWith("chest.") && lower.length() > 6;
    }

    private static String unknownName(String name) {
        return "unknown condition '" + name + "' (try inv.<item>, inv.free, inv.full, chest.<item>, has.<item>, health, hunger, "
            + "day, night, time, dim, busy)";
    }
}
