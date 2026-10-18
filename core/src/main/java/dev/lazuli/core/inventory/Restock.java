package dev.lazuli.core.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * What to keep stocked while working ({@code restockItems}: "food:16,torch:32,pickaxe:1,blocks:64"): an item, or a
 * group (food, pickaxe, axe, shovel, sword, blocks = the throwaway blocks). Under half of the amount (none, for an
 * amount of 1) it's topped up again from whatever a chest has, best of the group first.
 */
public final class Restock {
    private Restock() {}

    /** One line of the list: its name as written, the items that count (best first), and how many to have. */
    public record Want(String name, List<String> ids, int amount) {}

    private static final List<String> FOOD = ids("golden_carrot", "cooked_beef", "cooked_porkchop", "cooked_mutton",
        "cooked_salmon", "baked_potato", "cooked_chicken", "cooked_rabbit", "cooked_cod", "bread", "pumpkin_pie", "carrot",
        "apple", "beetroot", "melon_slice", "sweet_berries");

    private static List<String> tools(String kind) {
        return ids("netherite_" + kind, "diamond_" + kind, "iron_" + kind, "stone_" + kind, "golden_" + kind, "wooden_" + kind);
    }

    private static List<String> ids(String... names) {
        List<String> out = new ArrayList<>();
        for (String n : names) out.add(ItemKeys.id(n));
        return List.copyOf(out);
    }

    /** @param blocks the throwaway blocks, for "blocks" */
    public static List<Want> parse(String spec, List<String> blocks) {
        List<Want> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String[] kv = part.trim().toLowerCase(Locale.ROOT).split(":");
            if (kv.length < 2) continue;
            int amount;
            try {
                amount = Integer.parseInt(kv[kv.length - 1].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            String name = part.trim().toLowerCase(Locale.ROOT);
            name = name.substring(0, name.lastIndexOf(':')).trim();
            if (amount <= 0 || name.isEmpty()) continue;
            List<String> which = switch (name) {
                case "food" -> FOOD;
                case "pickaxe", "axe", "shovel", "sword", "hoe" -> tools(name);
                case "blocks" -> List.copyOf(blocks);
                case "arrows" -> ids("arrow", "spectral_arrow", "tipped_arrow");
                case "totem" -> ids("totem_of_undying");
                default -> List.of(ItemKeys.id(name));
            };
            out.add(new Want(name, which, amount));
        }
        return out;
    }

    /**
     * The first thing running low, as the item to take and how many; null when nothing is.
     * @param held   how many of these items the player has together
     * @param stored whether a known chest has some of an item
     * @param skip   names (as written) not to try now
     */
    public static ItemCount next(List<Want> wants, ToIntFunction<List<String>> held, Predicate<String> stored, Set<String> skip) {
        for (Want w : wants) {
            if (skip.contains(w.name())) continue;
            int have = held.applyAsInt(w.ids());
            boolean low = w.amount() == 1 ? have < 1 : have * 2 < w.amount();
            if (!low) continue;
            for (String id : w.ids()) if (stored.test(id)) return new ItemCount(id, w.amount() - have);
        }
        return null;
    }

    /** The line an item is restocked for (to skip it after a failed try), or null. */
    public static String nameOf(List<Want> wants, String id) {
        for (Want w : wants) if (w.ids().contains(id)) return w.name();
        return null;
    }
}
