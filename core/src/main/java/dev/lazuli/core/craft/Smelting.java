package dev.lazuli.core.craft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** Plans a furnace job: which recipe and input, which furnace, how much fuel. */
public final class Smelting {
    /** Fuel time (ticks) one item takes in a plain furnace; blast furnaces and smokers cook and burn twice as fast. */
    public static final int ITEM_TICKS = 200;

    private Smelting() {}

    /**
     * @param recipe the plain furnace recipe
     * @param input  the item that goes in
     * @param fast   the same thing in a blast furnace or smoker, or null if there's none
     */
    public record Job(SmeltRecipe recipe, String input, SmeltRecipe fast) {}

    /**
     * How to get {@code item} out of a furnace from {@code have}: {@code item} is what comes out (iron_ingot) or what
     * goes in (raw_iron). Picks the input it has most of; null if it has none.
     */
    public static Job job(Recipes recipes, String item, Map<String, Integer> have) {
        List<SmeltRecipe> options = recipes.smelting(item);
        if (options.isEmpty()) options = recipes.smeltingFrom(item).stream().filter(r -> r.input().contains(item)).toList();
        SmeltRecipe best = null;
        String input = null;
        int most = 0;
        for (SmeltRecipe r : options) {
            if (r.furnace() != SmeltRecipe.Furnace.FURNACE) continue;
            for (String in : r.input()) {
                if (recipes.smelting(item).isEmpty() && !in.equals(item)) continue; // asked by input: that input
                int n = have.getOrDefault(in, 0);
                if (n > most) {
                    most = n;
                    best = r;
                    input = in;
                }
            }
        }
        if (best == null) return null;
        SmeltRecipe fast = null;
        for (SmeltRecipe r : recipes.smeltingFrom(input))
            if (r.furnace() != SmeltRecipe.Furnace.FURNACE && r.result().equals(best.result())) fast = r;
        return new Job(best, input, fast);
    }

    /**
     * Fuel for {@code items} items: through {@code prefs} in order, as much of each as it has and needs. Null if all of
     * it together isn't enough.
     *
     * @param burn fuel ticks of one of an item (0: not a fuel)
     */
    public static Map<String, Integer> fuel(List<String> prefs, Map<String, Integer> have, ToIntFunction<String> burn, int items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        long left = (long) items * ITEM_TICKS;
        for (String f : prefs) {
            if (left <= 0) break;
            int b = burn.applyAsInt(f), n = have.getOrDefault(f, 0);
            if (b <= 0 || n <= 0 || out.containsKey(f)) continue;
            int k = (int) Math.min(n, (left + b - 1) / b);
            out.put(f, k);
            left -= (long) k * b;
        }
        return left > 0 ? null : out;
    }
}
