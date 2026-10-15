package dev.lazuli.core.inventory;

/**
 * One inventory click, as the game sends it: {@code button} 0 is left (or one item for THROW), 1 right (or the stack);
 * for SWAP it's the hotbar slot (0-8) to swap with.
 */
public record Click(int slot, int button, Kind kind) {
    public enum Kind { PICKUP, QUICK_MOVE, THROW, SWAP }

    public static Click left(int slot) {
        return new Click(slot, 0, Kind.PICKUP);
    }

    public static Click right(int slot) {
        return new Click(slot, 1, Kind.PICKUP);
    }

    public static Click shift(int slot) {
        return new Click(slot, 0, Kind.QUICK_MOVE);
    }

    public static Click throwOne(int slot) {
        return new Click(slot, 0, Kind.THROW);
    }

    /** Swaps {@code slot} with hotbar slot {@code hotbar} (0-8), like pressing that number key over it. */
    public static Click swap(int slot, int hotbar) {
        return new Click(slot, hotbar, Kind.SWAP);
    }

    public static Click throwAll(int slot) {
        return new Click(slot, 1, Kind.THROW);
    }
}
