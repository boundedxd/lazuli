package dev.lazuli.core.trade;

import dev.lazuli.core.inventory.ItemCount;

/**
 * One trade a villager offers, as last seen in its trading screen: pay {@code costA} (and {@code costB}, or null) for
 * {@code result}. An enchanted book's enchantment is {@code enchant} at {@code level} (null and 0 otherwise).
 *
 * @param index where it is in the villager's list (what the game selects it by)
 * @param left  times it can still be done before the villager restocks
 */
public record Offer(int index, ItemCount costA, ItemCount costB, ItemCount result, String enchant, int level, int left) {
    /** Gives what's wanted (an enchanted book: the same enchantment, at least as high). */
    public boolean gives(Want w) {
        if (!result.item().equals(w.item())) return false;
        return w.enchant() == null || w.enchant().equals(enchant) && level >= w.level();
    }

    /** For chat: "20 emerald + book -> sharpness 5". */
    public String describe() {
        String out = enchant != null ? name(enchant) + " " + level : (result.count() > 1 ? result.count() + " " : "") + name(result.item());
        String in = costA.count() + " " + name(costA.item()) + (costB != null ? " + " + costB.count() + " " + name(costB.item()) : "");
        return in + " -> " + out;
    }

    static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
