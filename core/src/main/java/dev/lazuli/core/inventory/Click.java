package dev.lazuli.core.inventory;

/** One inventory click, as the game sends it: {@code button} 0 is left (or one item for THROW), 1 right (or the stack). */
public record Click(int slot, int button, Kind kind) {
    public enum Kind { PICKUP, QUICK_MOVE, THROW }

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

    public static Click throwAll(int slot) {
        return new Click(slot, 1, Kind.THROW);
    }
}
