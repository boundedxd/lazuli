package dev.lazuli.core.craft;

import java.util.List;
import java.util.Set;

/**
 * A crafting recipe. {@code cells} is the pattern row by row ({@code width} x {@code height}), each cell the items
 * that may go there (empty for a blank cell); a shapeless recipe is one row of its ingredients, placed in any cells.
 */
public record Recipe(String id, String result, int count, int width, int height, List<Set<String>> cells, boolean shaped) {
    /** Whether it can be made in a {@code size} x {@code size} grid (2: the inventory, 3: a crafting table). */
    public boolean fits(int size) {
        return shaped ? width <= size && height <= size : cells.size() <= size * size;
    }

    /**
     * The grid slot index (0 to size*size-1, row by row) for cell {@code i}: shaped recipes go in the top left corner,
     * shapeless ones fill the grid in order.
     */
    public int gridIndex(int i, int size) {
        return shaped ? (i / width) * size + i % width : i;
    }
}
