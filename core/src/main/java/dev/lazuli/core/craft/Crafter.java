package dev.lazuli.core.craft;

import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.Menu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Plans the clicks that lay out one batch of a recipe in a crafting grid. */
public final class Crafter {
    private Crafter() {}

    /** Crafts done at once: a grid cell holds at most a stack. */
    public static int batch(int crafts) {
        return Math.min(crafts, 64);
    }

    /**
     * Clicks putting exactly {@code crafts} of each cell's item (taken from the {@code from} slots) into an empty
     * {@code size} x {@code size} grid whose slots are {@code grid}, row by row. Picks a stack up once and deals it
     * out over every cell needing it, then puts back what's left.
     *
     * @throws IllegalStateException if the {@code from} slots don't hold enough
     */
    public static List<Click> fill(Menu menu, int[] from, int[] grid, int size, Recipe recipe, String[] cells, int crafts) {
        Menu sim = menu.copy();
        List<Click> out = new ArrayList<>();
        for (String item : new LinkedHashSet<>(java.util.Arrays.asList(cells))) {
            if (item == null) continue;
            int source = -1;
            for (int i = 0; i < cells.length; i++) {
                if (!item.equals(cells[i])) continue;
                int target = grid[recipe.gridIndex(i, size)];
                int left = crafts;
                while (left > 0) {
                    if (sim.cursor().empty()) {
                        source = biggest(sim, from, item);
                        if (source < 0) throw new IllegalStateException("Not enough " + item);
                        click(sim, out, Click.left(source));
                    }
                    int c = sim.cursor().count();
                    if (c <= left) {
                        click(sim, out, Click.left(target));
                        left -= c;
                    } else {
                        for (int j = 0; j < left; j++) click(sim, out, Click.right(target));
                        left = 0;
                    }
                }
            }
            if (!sim.cursor().empty()) click(sim, out, Click.left(source)); // the rest goes back where it came from
        }
        return out;
    }

    private static int biggest(Menu m, int[] from, String item) {
        int best = -1;
        for (int s : from) if (item.equals(m.get(s).item()) && (best < 0 || m.get(s).count() > m.get(best).count())) best = s;
        return best;
    }

    private static void click(Menu sim, List<Click> out, Click c) {
        sim.apply(c);
        out.add(c);
    }
}
