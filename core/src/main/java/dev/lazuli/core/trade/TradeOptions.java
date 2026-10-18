package dev.lazuli.core.trade;

import dev.lazuli.core.settings.Settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What one {@code #trade} run changes from the trade settings (the {@code --flags}); null fields mean "as the
 * settings say". Pure: parsing the flags and putting them over {@link Settings}.
 */
public record TradeOptions(String with, Boolean onlyKnown, Boolean scan, Integer max, String keep, Double minRate,
                           Boolean waitRestock, Boolean waitDay, Boolean stay, Integer range) {
    public static final TradeOptions NONE = new TradeOptions(null, null, null, null, null, null, null, null, null, null);

    /** The flags taken out of a command's arguments, and what was left. */
    public record Parsed(TradeOptions options, List<String> rest) {}

    /** Takes every {@code --flag} (and its value) out of {@code args}; throws IllegalArgumentException when one is wrong. */
    public static Parsed parse(String[] args) {
        String with = null, keep = null;
        Boolean onlyKnown = null, scan = null, waitRestock = null, waitDay = null, stay = null;
        Integer max = null, range = null;
        Double minRate = null;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--") || a.length() < 3) {
                rest.add(a);
                continue;
            }
            switch (a.toLowerCase(Locale.ROOT)) {
                case "--with" -> with = value(args, ++i, a).toLowerCase(Locale.ROOT);
                case "--only-known" -> onlyKnown = true;
                case "--scan" -> scan = true;
                case "--max" -> max = whole(value(args, ++i, a), a, 0);
                case "--range" -> range = whole(value(args, ++i, a), a, 1);
                case "--min-rate" -> {
                    String v = value(args, ++i, a);
                    try {
                        minRate = Double.parseDouble(v);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--min-rate must be a number");
                    }
                    if (minRate < 0) throw new IllegalArgumentException("--min-rate must be 0 or more");
                }
                case "--keep" -> {
                    String item = value(args, ++i, a).toLowerCase(Locale.ROOT);
                    int n = Integer.MAX_VALUE; // no amount: never pays with it
                    if (i + 1 < args.length && args[i + 1].matches("\\d+")) n = whole(args[++i], a, 0);
                    keep = (keep == null ? "" : keep + ",") + item + ":" + n;
                }
                case "--wait-restock" -> waitRestock = true;
                case "--wait-day" -> waitDay = true;
                case "--stay", "--no-walk" -> stay = true;
                default -> throw new IllegalArgumentException("Unknown trade flag " + a
                    + " (--with --only-known --scan --max --keep --min-rate --wait-restock --wait-day --stay --no-walk --range)");
            }
        }
        return new Parsed(new TradeOptions(with, onlyKnown, scan, max, keep, minRate, waitRestock, waitDay, stay, range), List.copyOf(rest));
    }

    private static String value(String[] args, int i, String flag) {
        if (i >= args.length || args[i].startsWith("--")) throw new IllegalArgumentException(flag + " needs a value");
        return args[i];
    }

    private static int whole(String s, String flag, int min) {
        int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " must be a whole number");
        }
        if (v < min) throw new IllegalArgumentException(flag + " must be " + min + " or more");
        return v;
    }

    // ---- the options over the settings ----

    /** Never walk to a villager whose trades aren't known. */
    public boolean onlyKnown(Settings s) {
        if (scan != null && scan && onlyKnown == null) return false;
        return onlyKnown != null ? onlyKnown : s.tradeOnlyKnown.get();
    }

    /** May look at villagers not seen yet when no known one can do the job. */
    public boolean mayScan(Settings s) {
        if (onlyKnown(s)) return false;
        return scan != null ? scan : s.tradeScanUnknown.get();
    }

    /** Trades in a run, 0: no limit. */
    public int maxTrades(Settings s) {
        return max != null ? max : s.tradeMaxPerRun.get();
    }

    public double minRate(Settings s) {
        return minRate != null ? minRate : s.tradeMinRate.get();
    }

    public boolean waitRestock(Settings s) {
        return waitRestock != null ? waitRestock : s.tradeWaitRestock.get();
    }

    public boolean waitDay(Settings s) {
        return waitDay != null ? waitDay : s.tradeWaitDay.get();
    }

    public boolean stay(Settings s) {
        return stay != null ? stay : s.tradeStayPut.get();
    }

    public int range(Settings s) {
        return range != null ? range : s.tradeRange.get();
    }

    /**
     * Minutes to wait for restocks: a day (20) with wait-day, else tradeRestockMinutes; 0 when waiting isn't asked for
     * and {@code buying} (selling only waits when told to).
     */
    public int waitMinutes(Settings s, boolean buying) {
        int base = s.tradeRestockMinutes.get();
        if (waitDay(s)) return Math.max(base, 20);
        if (waitRestock(s)) return Math.max(base, 10);
        return buying ? base : 0;
    }

    /** What to keep in the inventory (item to amount): the setting's list plus this run's {@code --keep}. */
    public Map<String, Integer> keepItems(Settings s) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String list : new String[]{s.tradeKeepItems.get(), keep == null ? "" : keep}) {
            for (String part : list.split(",")) {
                if (part.isBlank()) continue;
                int c = part.lastIndexOf(':');
                String id = c < 0 ? part : part.substring(0, c);
                int n = Integer.MAX_VALUE;
                if (c >= 0 && part.substring(c + 1).matches("\\d+")) n = Integer.parseInt(part.substring(c + 1));
                else if (c >= 0) id = part; // namespace colon, no amount
                id = id.trim().toLowerCase(Locale.ROOT);
                out.merge(id.contains(":") ? id : "minecraft:" + id, n, Math::max);
            }
        }
        return out;
    }

    /** The inventory counts less what's kept (never below 0). */
    public Map<String, Integer> spendable(Settings s, Map<String, Integer> have) {
        Map<String, Integer> keep = keepItems(s);
        if (keep.isEmpty()) return have;
        Map<String, Integer> out = new LinkedHashMap<>(have);
        for (Map.Entry<String, Integer> e : keep.entrySet()) {
            Integer n = out.get(e.getKey());
            if (n != null) out.put(e.getKey(), (int) Math.max(0L, (long) n - e.getValue()));
        }
        return out;
    }

    /** Does a villager of this profession (and id) match {@code --with}? Always, when it wasn't given. */
    public boolean allows(String profession, String id) {
        if (with == null) return true;
        String p = profession == null ? "" : profession.toLowerCase(Locale.ROOT);
        return p.equals(with) || p.contains(with) || id != null && id.toLowerCase(Locale.ROOT).startsWith(with);
    }

    /** Is this offer good enough: items got per item paid at least the min rate. */
    public boolean rateOk(Settings s, Offer o) {
        double min = minRate(s);
        if (min <= 0) return true;
        int paid = Math.max(1, o.costA().count());
        return (double) o.result().count() / paid >= min;
    }

    /** True when any flag is set. */
    public boolean any() {
        return !equals(NONE);
    }
}
