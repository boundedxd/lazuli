package dev.lazuli.core.craft;

import dev.lazuli.core.inventory.ItemCount;

import java.util.List;

/**
 * A #getitem: the items to have (a negative count: 1), and after: where to bring them ({@code returnTo} x y z, null to
 * stay) and whether to put them in the nearest chest there.
 */
public record ItemRequest(List<ItemCount> items, int[] returnTo, boolean deposit) {
    public ItemRequest {
        items = List.copyOf(items);
    }

    public static ItemRequest of(String item, int count) {
        return new ItemRequest(List.of(new ItemCount(item, count)), null, false);
    }
}
