package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A condition as the editor shows it: clauses (what, comparison, value, "not") joined by all-of or any-of. Turns
 * into a {@link Cond} and back; a condition that isn't that flat shape (nested and/or) reads back as null, and the
 * editor leaves it to the text field.
 */
public final class CondForm {
    public enum Subject {
        INV_ITEM("Items in inventory"), CHEST_ITEM("Items in remembered chests"), FREE_SLOTS("Free inventory slots"),
        INV_FULL("Inventory is full"), HAS_ITEM("Has an item"), HEALTH("Health"), HUNGER("Hunger"),
        TIME("Time of day"), DIMENSION("Dimension"), BUSY("Lazuli is busy");

        public final String title;

        Subject(String title) {
            this.title = title;
        }

        public boolean needsItem() {
            return this == INV_ITEM || this == CHEST_ITEM || this == HAS_ITEM;
        }

        /** Compared with > < = and a number. */
        public boolean numeric() {
            return this == INV_ITEM || this == CHEST_ITEM || this == FREE_SLOTS || this == HEALTH || this == HUNGER;
        }
    }

    public static final List<String> OPS = List.of(">", ">=", "<", "<=", "==", "!=");

    /** {@code word}: day or night for TIME, overworld/nether/end for DIMENSION. */
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

        /** The condition this clause means; throws IllegalArgumentException when it's incomplete (no item...). */
        public Cond toCond() {
            String it = item.toLowerCase(Locale.ROOT).replace("minecraft:", "");
            if (subject.needsItem() && it.isEmpty()) throw new IllegalArgumentException("pick an item");
            Cond c = switch (subject) {
                case INV_ITEM -> new Cond.Cmp("inv." + it, op, String.valueOf(number));
                case CHEST_ITEM -> new Cond.Cmp("chest." + it, op, String.valueOf(number));
                case FREE_SLOTS -> new Cond.Cmp("inv.free", op, String.valueOf(number));
                case HEALTH -> new Cond.Cmp("health", op, String.valueOf(number));
                case HUNGER -> new Cond.Cmp("hunger", op, String.valueOf(number));
                case INV_FULL -> new Cond.Flag("inv.full");
                case HAS_ITEM -> new Cond.Flag("has." + it);
                case BUSY -> new Cond.Flag("busy");
                case TIME -> new Cond.Flag(word.equals("night") ? "night" : "day");
                case DIMENSION -> new Cond.Cmp("dim", "==", word.isEmpty() ? "overworld" : word);
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
                } else {
                    switch (n) {
                        case "inv.full", "full" -> cl.subject = Subject.INV_FULL;
                        case "busy" -> cl.subject = Subject.BUSY;
                        case "day", "night" -> {
                            cl.subject = Subject.TIME;
                            cl.word = n;
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
                if (s.equals("dim") || s.equals("dimension")) {
                    cl.subject = Subject.DIMENSION;
                    cl.word = m.value();
                    if (m.op().equals("!=")) cl.negate = !cl.negate;
                    return cl;
                }
                if (s.equals("time")) {
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
                } else if (s.equals("inv.free")) {
                    cl.subject = Subject.FREE_SLOTS;
                } else if (s.equals("health")) {
                    cl.subject = Subject.HEALTH;
                } else if (s.equals("hunger")) {
                    cl.subject = Subject.HUNGER;
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
                    return null;
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
