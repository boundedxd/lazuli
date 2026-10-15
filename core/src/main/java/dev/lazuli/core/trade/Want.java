package dev.lazuli.core.trade;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Something to trade for: {@code count} of an item, or of enchanted books with {@code enchant} at {@code level} or higher. */
public record Want(String item, String enchant, int level, int count) {
    public static final String BOOK = "minecraft:enchanted_book";

    /** For chat: "sharpness 5", "20 bread". */
    public String describe() {
        String what = enchant != null ? Offer.name(enchant) + " " + level : Offer.name(item);
        return count > 1 ? count + " " + what : what;
    }

    /**
     * {@code sharpness 5 mending bread 20}: an enchantment takes a level (a number or I..X; none: 1) and then a count,
     * an item a count. Null if a number comes first.
     */
    public static List<Want> parse(String[] args, Predicate<String> isEnchant) {
        List<Want> out = new ArrayList<>();
        int i = 0;
        while (i < args.length) {
            String name = args[i++].toLowerCase(Locale.ROOT);
            if (number(name) > 0) return null;
            String id = name.contains(":") ? name : "minecraft:" + name;
            if (isEnchant.test(id)) {
                int level = i < args.length && number(args[i]) > 0 ? number(args[i++]) : 1;
                int count = i < args.length && number(args[i]) > 0 && !roman(args[i]) ? number(args[i++]) : 1;
                out.add(new Want(BOOK, id, level, count));
            } else {
                int count = i < args.length && number(args[i]) > 0 && !roman(args[i]) ? number(args[i++]) : 1;
                out.add(new Want(id, null, 0, count));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static final String[] ROMAN = {"i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x"};

    private static boolean roman(String s) {
        return List.of(ROMAN).contains(s.toLowerCase(Locale.ROOT));
    }

    /** A positive number (digits or I..X), else 0. */
    private static int number(String s) {
        int r = List.of(ROMAN).indexOf(s.toLowerCase(Locale.ROOT));
        if (r >= 0) return r + 1;
        try {
            return Math.max(0, Integer.parseInt(s));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
