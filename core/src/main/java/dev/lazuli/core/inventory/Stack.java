package dev.lazuli.core.inventory;

/**
 * What's in a slot: {@code item} names the kind exactly (id plus anything that stops stacking, like enchantments),
 * so two stacks with the same item stack together. Empty slots are {@link #EMPTY}.
 */
public record Stack(String item, int count, int max) {
    public static final Stack EMPTY = new Stack("", 0, 64);

    public boolean empty() {
        return count <= 0;
    }

    public Stack withCount(int n) {
        return n <= 0 ? EMPTY : new Stack(item, n, max);
    }

    public boolean stacksWith(Stack o) {
        return !empty() && !o.empty() && item.equals(o.item);
    }
}
