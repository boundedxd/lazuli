package dev.lazuli.core.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A {@code trade} step as the routine editor shows it: what to trade, the villager (a profession or a saved target),
 * and each option as a value or null ("as the setting says"). Reads from and writes to the bare option words
 * ({@code sell string 64 @fisher with fisherman max 64 only-known no-scan keep emerald 10}); {@link #parse} gives null for
 * anything that isn't that shape ({@code list}, {@code scan}, several items...), which the editor leaves as text.
 */
public final class TradeForm {
    /** An item to keep in the inventory: {@code count} -1 means never pay with it at all. */
    public record Keep(String item, int count) {
    }

    public boolean sell;
    public String item = "";
    public int count;
    /** A saved target (the villager to go to), "" for none. */
    public String target = "";
    /** A profession, "" for any. */
    public String with = "";
    public Boolean onlyKnown, scan, waitRestock, waitDay, stay;
    public Integer max, range;
    public Double minRate;
    public final List<Keep> keep = new ArrayList<>();

    /** The professions the editor offers. */
    public static final List<String> PROFESSIONS = List.of("armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman",
        "fletcher", "leatherworker", "librarian", "mason", "shepherd", "toolsmith", "weaponsmith", "wandering_trader");

    /** Null when the text isn't a plain trade (a subcommand, several items, a bad option). */
    public static TradeForm parse(String args) {
        String[] t = args.isBlank() ? new String[0] : args.trim().split("\\s+");
        TradeForm f = new TradeForm();
        int i = 0;
        if (t.length > 0 && t[0].equalsIgnoreCase("sell")) {
            f.sell = true;
            i++;
        }
        boolean gotItem = false;
        for (; i < t.length; i++) {
            String a = t[i];
            boolean dashed = a.startsWith("--") && a.length() > 2;
            String word = (dashed ? a.substring(2) : a).toLowerCase(Locale.ROOT);
            if (!dashed && word.equals("scan") && !gotItem && !f.sell) return null; // the subcommand
            if (!dashed && !TradeOptions.WORDS.contains(word)) {
                if (a.startsWith("@") && a.length() > 1) {
                    if (!f.target.isEmpty()) return null;
                    f.target = a.substring(1);
                } else if (a.matches("[0-9]+")) {
                    if (!gotItem || f.count != 0) return null;
                    f.count = Integer.parseInt(a);
                } else if (!gotItem && !a.startsWith("~")) {
                    if (!f.sell && List.of("list", "scan", "forget", "find").contains(a.toLowerCase(Locale.ROOT))) return null;
                    f.item = a;
                    gotItem = true;
                } else if (f.sell && gotItem && a.equalsIgnoreCase("all") && f.count == 0) {
                    return null;
                } else {
                    return null;
                }
                continue;
            }
            try {
                switch (word) {
                    case "with" -> f.with = need(t, ++i).toLowerCase(Locale.ROOT);
                    case "only-known" -> f.onlyKnown = true;
                    case "no-only-known" -> f.onlyKnown = false;
                    case "scan" -> f.scan = true;
                    case "no-scan" -> f.scan = false;
                    case "max" -> f.max = Integer.parseInt(need(t, ++i));
                    case "range" -> f.range = Integer.parseInt(need(t, ++i));
                    case "min-rate" -> f.minRate = Double.parseDouble(need(t, ++i));
                    case "keep" -> {
                        String it = need(t, ++i).toLowerCase(Locale.ROOT);
                        int n = -1;
                        if (i + 1 < t.length && t[i + 1].matches("\\d+")) n = Integer.parseInt(t[++i]);
                        f.keep.add(new Keep(it, n));
                    }
                    case "wait-restock" -> f.waitRestock = true;
                    case "no-wait-restock" -> f.waitRestock = false;
                    case "wait-day" -> f.waitDay = true;
                    case "no-wait-day" -> f.waitDay = false;
                    case "stay", "no-walk" -> f.stay = true;
                    case "no-stay", "walk" -> f.stay = false;
                    default -> {
                        return null;
                    }
                }
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return f;
    }

    private static String need(String[] t, int i) {
        if (i >= t.length) throw new IllegalArgumentException("missing value");
        return t[i];
    }

    /** The arguments, with the option words bare. */
    public String text() {
        List<String> out = new ArrayList<>();
        if (sell) out.add("sell");
        if (!item.isBlank()) {
            out.add(item);
            if (count > 0) out.add(String.valueOf(count));
        }
        if (!target.isBlank()) out.add("@" + target);
        if (!with.isBlank()) {
            out.add("with");
            out.add(with);
        }
        flag(out, onlyKnown, "only-known");
        flag(out, scan, "scan");
        if (max != null) {
            out.add("max");
            out.add(String.valueOf(max));
        }
        if (range != null) {
            out.add("range");
            out.add(String.valueOf(range));
        }
        if (minRate != null) {
            out.add("min-rate");
            out.add(minRate == Math.rint(minRate) ? String.valueOf(minRate.longValue()) : String.valueOf(minRate));
        }
        for (Keep k : keep) {
            out.add("keep");
            out.add(k.item());
            if (k.count() >= 0) out.add(String.valueOf(k.count()));
        }
        flag(out, waitRestock, "wait-restock");
        flag(out, waitDay, "wait-day");
        if (stay != null) out.add(stay ? "stay" : "walk");
        return String.join(" ", out);
    }

    private static void flag(List<String> out, Boolean v, String word) {
        if (v == null) return;
        out.add(v ? word : "no-" + word);
    }
}
