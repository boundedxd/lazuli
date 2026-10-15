package dev.lazuli.core.inventory;

import java.util.List;

/** An item id (or "all") and how many of it; a negative count means all of it. */
public record ItemCount(String item, int count) {
    /** For chat: "3 dirt, all sand". */
    public static String describe(List<ItemCount> items) {
        StringBuilder b = new StringBuilder();
        for (ItemCount i : items) {
            if (!b.isEmpty()) b.append(", ");
            b.append(i.count < 0 ? "all" : String.valueOf(i.count)).append(' ').append(i.item);
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return item + " " + count;
    }
}
