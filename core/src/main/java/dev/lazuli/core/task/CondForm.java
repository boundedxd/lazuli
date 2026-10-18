package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A condition as the editor shows it: clauses (what, comparison, value, "not") joined by all-of or any-of. Turns
 * into a {@link Cond} and back; a condition that isn't that flat shape (nested and/or) reads back as null, and the
 * editor leaves it to the text field. Every kind of condition the language has is a {@link Subject}.
 */
public final class CondForm {
    /** What the extra text of a clause is (the {@link Clause#item} field). */
    public enum NameKind { NONE, ITEM, TARGET, EFFECT, VARIABLE }

    public enum Subject {
        INV_ITEM("Items in inventory"), CHEST_ITEM("Items in remembered chests"), FREE_SLOTS("Free inventory slots"),
        INV_FULL("Inventory is full"), HAS_ITEM("Has an item"), HEALTH("Health"), HUNGER("Hunger"),
        TIME("Time of day"), DIMENSION("Dimension"), BUSY("Lazuli is busy"),
        ANYWHERE_ITEM("Items anywhere (inventory and chests)"), ARMOR_DURABILITY("Armor durability (%)"),
        TOOL_DURABILITY("Tool durability (%)"), XP_LEVEL("Experience level"), TIME_TICK("Time of day (ticks)"),
        WEATHER("Weather"), Y_LEVEL("Height (y)"), DISTANCE("Distance to target or x,y,z"), PLAYERS_NEARBY("Players nearby"),
        HOSTILES_NEARBY("Hostile mobs nearby"), HOLDING("Holding an item"), HAS_EFFECT("Has an effect"),
        LAST_STEP("Last step"), LOOP_COUNT("Loop turn"), RANDOM("Random chance (%)"), VARIABLE("Variable"),
        ROUND("Round"), FAILS("Failed steps so far");

        public final String title;

        Subject(String title) {
            this.title = title;
        }

        public NameKind nameKind() {
            return switch (this) {
                case INV_ITEM, CHEST_ITEM, HAS_ITEM, ANYWHERE_ITEM, TOOL_DURABILITY, HOLDING -> NameKind.ITEM;
                case DISTANCE -> NameKind.TARGET;
                case HAS_EFFECT -> NameKind.EFFECT;
                case VARIABLE -> NameKind.VARIABLE;
                default -> NameKind.NONE;
            };
        }

        public boolean needsItem() {
            return nameKind() == NameKind.ITEM;
        }

        /** Needs the {@link Clause#item} text of any kind (item, target, effect, variable name). */
        public boolean needsName() {
            return nameKind() != NameKind.NONE;
        }

        /** Compared with > < = and a number. */
        public boolean numeric() {
            return switch (this) {
                case INV_ITEM, CHEST_ITEM, FREE_SLOTS, HEALTH, HUNGER, ANYWHERE_ITEM, ARMOR_DURABILITY, TOOL_DURABILITY, XP_LEVEL,
                     TIME_TICK, Y_LEVEL, DISTANCE, PLAYERS_NEARBY, HOSTILES_NEARBY, LOOP_COUNT, VARIABLE, ROUND, FAILS -> true;
                default -> false;
            };
        }

        /** Takes a number but no comparison (random chance). */
        public boolean percentOnly() {
            return this == RANDOM;
        }

        /** The words to pick from (day/night, overworld/nether/end...); empty when it isn't a word subject. */
        public List<String> words() {
            return switch (this) {
                case TIME -> List.of("day", "night");
                case DIMENSION -> List.of("overworld", "nether", "end");
                case WEATHER -> List.of("clear", "rain", "thunder");
                case LAST_STEP -> List.of("failed", "succeeded");
                default -> List.of();
            };
        }

        /** The most sensible largest value for the number. */
        public int max() {
            return switch (this) {
                case ARMOR_DURABILITY, TOOL_DURABILITY, RANDOM -> 100;
                case TIME_TICK -> 24000;
                case HEALTH, HUNGER -> 20;
                case Y_LEVEL -> 320;
                default -> 100000;
            };
        }
    }

    public static final List<String> OPS = List.of(">", ">=", "<", "<=", "==", "!=");

    /** {@code word}: day or night for TIME, overworld/nether/end for DIMENSION...; {@code item}: the item, target, effect or variable. */
    public static final class Clause {
        public Subject subject = Subject.INV_ITEM;
        public String item = "";
        public String op = ">=";
        public int number = 1;
        public String word = "";
        public boolean negate;

        public Clause() {
        }

        public Clause(Clause c) {
            subject = c.subject;
            item = c.item;
            op = c.op;
            number = c.number;
            word = c.word;
            negate = c.negate;
        }

        /** A clause for a subject with sensible starting values (an item picked, a word chosen). */
        public static Clause of(Subject s) {
            Clause c = new Clause();
            c.subject = s;
            c.reset();
            return c;
        }

        /** Gives the fields starting values that suit {@link #subject} where they're missing or wrong. */
        public void reset() {
            switch (subject.nameKind()) {
                case ITEM -> {
                    if (item.isEmpty() || item.startsWith("@") || item.contains(",")) item = "cobblestone";
                }
                case TARGET -> {
                    if (item.isEmpty() || !item.startsWith("@") && !item.contains(",")) item = "0,64,0";
                }
                case EFFECT -> {
                    if (item.isEmpty() || item.startsWith("@")) item = "speed";
                }
                case VARIABLE -> {
                    if (item.isEmpty() || item.startsWith("@") || item.contains(",")) item = "count";
                }
                default -> { }
            }
            List<String> words = subject.words();
            if (!words.isEmpty() && !words.contains(word)) word = subject == Subject.TIME ? "night" : words.get(0);
            if (subject == Subject.RANDOM && (number < 0 || number > 100)) number = 50;
            if (op.isEmpty() || !OPS.contains(op)) op = ">=";
        }

        /** The condition this clause means; throws IllegalArgumentException when it's incomplete (no item...). */
        public Cond toCond() {
            String it = item.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "");
            if (subject.needsName() && it.isEmpty()) throw new IllegalArgumentException(subject.nameKind() == NameKind.ITEM ? "pick an item" : "fill in the name");
            if (subject == Subject.DISTANCE && !it.startsWith("@") && !it.matches("-?[0-9.]+,-?[0-9.]+,-?[0-9.]+"))
                throw new IllegalArgumentException("pick a target or write x,y,z");
            String n = String.valueOf(number);
            Cond c = switch (subject) {
                case INV_ITEM -> new Cond.Cmp("inv." + it, op, n);
                case CHEST_ITEM -> new Cond.Cmp("chest." + it, op, n);
                case ANYWHERE_ITEM -> new Cond.Cmp("anywhere." + it, op, n);
                case FREE_SLOTS -> new Cond.Cmp("inv.free", op, n);
                case HEALTH -> new Cond.Cmp("health", op, n);
                case HUNGER -> new Cond.Cmp("hunger", op, n);
                case ARMOR_DURABILITY -> new Cond.Cmp("armor.durability", op, n);
                case TOOL_DURABILITY -> new Cond.Cmp("tool." + it, op, n);
                case XP_LEVEL -> new Cond.Cmp("xp", op, n);
                case TIME_TICK -> new Cond.Cmp("time", op, n);
                case Y_LEVEL -> new Cond.Cmp("y", op, n);
                case DISTANCE -> new Cond.Cmp("dist." + it, op, n);
                case PLAYERS_NEARBY -> new Cond.Cmp("players", op, n);
                case HOSTILES_NEARBY -> new Cond.Cmp("hostiles", op, n);
                case LOOP_COUNT -> new Cond.Cmp("loop", op, n);
                case VARIABLE -> new Cond.Cmp("$" + it, op, n);
                case ROUND -> new Cond.Cmp("$round", op, n);
                case FAILS -> new Cond.Cmp("$fails", op, n);
                case INV_FULL -> new Cond.Flag("inv.full");
                case HAS_ITEM -> new Cond.Flag("has." + it);
                case HOLDING -> new Cond.Flag("holding." + it);
                case HAS_EFFECT -> new Cond.Flag("effect." + it);
                case BUSY -> new Cond.Flag("busy");
                case RANDOM -> new Cond.Flag("random." + Math.max(0, Math.min(100, number)));
                case LAST_STEP -> new Cond.Flag(word.equals("succeeded") ? "last.ok" : "last.failed");
                case TIME -> new Cond.Flag(word.equals("night") ? "night" : "day");
                case DIMENSION -> new Cond.Cmp("dim", "==", word.isEmpty() ? "overworld" : word);
                case WEATHER -> new Cond.Cmp("weather", "==", word.isEmpty() ? "clear" : word);
            };
            return negate ? new Cond.Not(c) : c;
        }

        static Clause of(Cond c) {
            Clause cl = new Clause();
            if (c instanceof Cond.Not n) {
                cl.negate = true;
                c = n.c();
            }
            if (c instanceof Cond.Flag f) {
                String n = f.name().toLowerCase(Locale.ROOT);
                if (n.startsWith("has.")) {
                    cl.subject = Subject.HAS_ITEM;
                    cl.item = n.substring(4);
                } else if (n.startsWith("holding.")) {
                    cl.subject = Subject.HOLDING;
                    cl.item = n.substring(8);
                } else if (n.startsWith("effect.")) {
                    cl.subject = Subject.HAS_EFFECT;
                    cl.item = n.substring(7);
                } else if (n.startsWith("random.")) {
                    cl.subject = Subject.RANDOM;
                    cl.number = Integer.parseInt(n.substring(7));
                } else if (n.startsWith("$")) {
                    cl.subject = Subject.VARIABLE;
                    cl.item = n.substring(1);
                    cl.op = "!=";
                    cl.number = 0;
                } else {
                    switch (n) {
                        case "inv.full", "full" -> cl.subject = Subject.INV_FULL;
                        case "busy" -> cl.subject = Subject.BUSY;
                        case "day", "night" -> {
                            cl.subject = Subject.TIME;
                            cl.word = n;
                        }
                        case "rain", "raining", "thunder", "thundering" -> {
                            cl.subject = Subject.WEATHER;
                            cl.word = n.startsWith("rain") ? "rain" : "thunder";
                        }
                        case "last.failed", "last.ok" -> {
                            cl.subject = Subject.LAST_STEP;
                            cl.word = n.equals("last.ok") ? "succeeded" : "failed";
                        }
                        case "players", "hostiles" -> {
                            cl.subject = n.equals("players") ? Subject.PLAYERS_NEARBY : Subject.HOSTILES_NEARBY;
                            cl.op = ">";
                            cl.number = 0;
                        }
                        default -> {
                            return null;
                        }
                    }
                }
                return cl;
            }
            if (c instanceof Cond.Cmp m) {
                String s = m.subject().toLowerCase(Locale.ROOT);
                if (s.equals("dim") || s.equals("dimension") || s.equals("weather")) {
                    cl.subject = s.equals("weather") ? Subject.WEATHER : Subject.DIMENSION;
                    cl.word = m.value();
                    if (m.op().equals("!=")) cl.negate = !cl.negate;
                    return cl;
                }
                if (s.equals("time") && (m.value().equals("day") || m.value().equals("night"))) {
                    cl.subject = Subject.TIME;
                    cl.word = m.value();
                    if (m.op().equals("!=")) {
                        cl.word = m.value().equals("night") ? "day" : "night";
                    }
                    return cl;
                }
                if (s.startsWith("chest.")) {
                    cl.subject = Subject.CHEST_ITEM;
                    cl.item = s.substring(6);
                } else if (s.startsWith("anywhere.")) {
                    cl.subject = Subject.ANYWHERE_ITEM;
                    cl.item = s.substring(9);
                } else if (s.startsWith("tool.")) {
                    cl.subject = Subject.TOOL_DURABILITY;
                    cl.item = s.substring(5);
                } else if (s.startsWith("dist.")) {
                    cl.subject = Subject.DISTANCE;
                    cl.item = s.substring(5);
                } else if (s.equals("$round")) {
                    cl.subject = Subject.ROUND;
                } else if (s.equals("$fails")) {
                    cl.subject = Subject.FAILS;
                } else if (s.startsWith("$")) {
                    cl.subject = Subject.VARIABLE;
                    cl.item = s.substring(1);
                } else if (s.equals("inv.free")) {
                    cl.subject = Subject.FREE_SLOTS;
                } else if (s.equals("health")) {
                    cl.subject = Subject.HEALTH;
                } else if (s.equals("hunger")) {
                    cl.subject = Subject.HUNGER;
                } else if (s.equals("armor.durability")) {
                    cl.subject = Subject.ARMOR_DURABILITY;
                } else if (s.equals("xp")) {
                    cl.subject = Subject.XP_LEVEL;
                } else if (s.equals("time")) {
                    cl.subject = Subject.TIME_TICK;
                } else if (s.equals("y")) {
                    cl.subject = Subject.Y_LEVEL;
                } else if (s.equals("players")) {
                    cl.subject = Subject.PLAYERS_NEARBY;
                } else if (s.equals("hostiles")) {
                    cl.subject = Subject.HOSTILES_NEARBY;
                } else if (s.equals("loop")) {
                    cl.subject = Subject.LOOP_COUNT;
                } else if (s.startsWith("inv.")) {
                    cl.subject = Subject.INV_ITEM;
                    cl.item = s.substring(4);
                } else {
                    return null;
                }
                cl.op = m.op();
                try {
                    double d = Double.parseDouble(m.value());
                    if (d != Math.rint(d)) return null;
                    cl.number = (int) d;
                } catch (NumberFormatException e) {
                    return null; // compared with another variable: text only
                }
                return cl;
            }
            return null;
        }
    }

    /** All clauses must hold (false: any one). */
    public boolean all = true;
    public final List<Clause> clauses = new ArrayList<>();

    /** The condition, null when there are no clauses; throws IllegalArgumentException when a clause is incomplete. */
    public Cond toCond() {
        List<Cond> parts = new ArrayList<>();
        for (Clause c : clauses) parts.add(c.toCond());
        if (parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);
        return all ? new Cond.And(parts) : new Cond.Or(parts);
    }

    /** The form for a condition; null when it isn't a flat list of clauses. {@code null} gives an empty form. */
    public static CondForm of(Cond c) {
        CondForm f = new CondForm();
        if (c == null) return f;
        List<Cond> parts;
        if (c instanceof Cond.And a) {
            parts = a.all();
            f.all = true;
        } else if (c instanceof Cond.Or o) {
            parts = o.any();
            f.all = false;
        } else {
            parts = List.of(c);
        }
        for (Cond p : parts) {
            Clause cl = Clause.of(p);
            if (cl == null) return null;
            f.clauses.add(cl);
        }
        return f;
    }
}
