package dev.lazuli.core.craft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what to craft, in order, to make an item from what's in the inventory, crafting the ingredients it lacks
 * first (logs into planks, planks into sticks...).
 */
public final class CraftTree {
    private static final int DEPTH = 8;

    /** Craft {@code recipe} {@code crafts} times with these items in its cells (null: a blank cell). */
    public record Step(Recipe recipe, int crafts, String[] cells) {
        @Override
        public String toString() {
            return recipe.id() + " x" + crafts + " " + Arrays.toString(cells);
        }
    }

    /** The steps and what the inventory holds after them, or what's missing (item -> how many) if it can't be made. */
    public record Result(List<Step> steps, Map<String, Integer> left, Map<String, Integer> missing) {
        public boolean ok() {
            return missing.isEmpty();
        }
    }

    private final Recipes recipes;
    private final int depth;
    private Map<String, Integer> have;
    private List<Step> steps = new ArrayList<>();
    private Map<String, Integer> missing = new HashMap<>();
    private final Set<String> making = new HashSet<>();

    private CraftTree(Recipes recipes, Map<String, Integer> have, int depth) {
        this.recipes = recipes;
        this.depth = depth;
        this.have = new HashMap<>(have);
    }

    /**
     * How to make at least {@code count} more of {@code item} (full ids) from {@code have} (item -> count); what it
     * already has of {@code item} itself is left alone.
     *
     * @param ingredients also craft the ingredients it lacks (else only {@code item} itself)
     */
    public static Result resolve(Recipes recipes, String item, int count, Map<String, Integer> have, boolean ingredients) {
        CraftTree t = new CraftTree(recipes, have, ingredients ? DEPTH : 0);
        if (t.make(item, count, 0)) {
            t.have.values().removeIf(n -> n <= 0);
            return new Result(List.copyOf(t.steps), Map.copyOf(t.have), Map.of());
        }
        return new Result(List.of(), Map.copyOf(have), t.missing.isEmpty() ? Map.of(item, count) : Map.copyOf(t.missing));
    }

    public static Result resolve(Recipes recipes, String item, int count, Map<String, Integer> have) {
        return resolve(recipes, item, count, have, true);
    }

    /** Crafts at least {@code n} of {@code item} into {@code have}. On failure nothing changes but {@link #missing}. */
    private boolean make(String item, int n, int depth) {
        List<Recipe> options = recipes.producing(item);
        if (options.isEmpty() || depth > this.depth || making.contains(item)) {
            if (depth > 0) missing.merge(item, n, Integer::sum);
            return false;
        }
        making.add(item);
        // recipes it can start on right away first
        List<Recipe> ordered = new ArrayList<>(options);
        ordered.sort(java.util.Comparator.comparing(r -> direct(r, 1) == null));
        Map<String, Integer> firstMissing = null;
        boolean ok = false;
        for (Recipe r : ordered) {
            var haveBefore = new HashMap<>(have);
            var missingBefore = new HashMap<>(missing);
            int stepsBefore = steps.size();
            if (use(r, n, depth)) {
                ok = true;
                break;
            }
            if (firstMissing == null) firstMissing = new HashMap<>(missing);
            have = haveBefore;
            missing = missingBefore;
            steps.subList(stepsBefore, steps.size()).clear();
        }
        making.remove(item);
        if (!ok) {
            // deeper down only the item itself is worth naming; at the top, the first recipe's missing ingredients
            if (depth > 0) missing.merge(item, n, Integer::sum);
            else if (firstMissing != null) missing = firstMissing;
        }
        return ok;
    }

    /** Crafts {@code r} enough times for {@code n} items, making its ingredients as needed. */
    private boolean use(Recipe r, int n, int depth) {
        int crafts = (n + r.count() - 1) / r.count(), left = crafts;
        // cells taking the same items are one need
        Map<Set<String>, Integer> groups = new LinkedHashMap<>();
        for (Set<String> c : r.cells()) if (!c.isEmpty()) groups.merge(c, 1, Integer::sum);
        for (int guard = 0; left > 0 && guard < 64; guard++) {
            int k = mostDirect(r, left);
            if (k > 0) {
                String[] cells = direct(r, k);
                for (String c : cells) if (c != null) have.merge(c, -k, Integer::sum);
                steps.add(new Step(r, k, cells));
                left -= k;
                continue;
            }
            boolean made = false;
            for (var g : groups.entrySet()) {
                int need = left * g.getValue(), got = 0;
                for (String o : g.getKey()) got += have.getOrDefault(o, 0);
                if (got >= need) continue;
                if (!makeAny(g.getKey(), need - got, depth)) return false;
                made = true;
            }
            if (!made) { // enough in total, but split up in a way no cell can use
                missing.merge(groups.keySet().iterator().next().iterator().next(), 1, Integer::sum);
                return false;
            }
        }
        if (left > 0) return false;
        have.merge(r.result(), crafts * r.count(), Integer::sum);
        return true;
    }

    /** Makes {@code n} of any one of {@code options}, trying them in order. */
    private boolean makeAny(Set<String> options, int n, int depth) {
        Map<String, Integer> firstMissing = null;
        for (String o : options) {
            var missingBefore = new HashMap<>(missing);
            if (make(o, n, depth + 1)) {
                missing = missingBefore;
                return true;
            }
            if (firstMissing == null) firstMissing = new HashMap<>(missing);
            missing = missingBefore;
        }
        if (firstMissing != null) missing = firstMissing;
        return false;
    }

    /** The most crafts (up to {@code max}) it can do with what it has now. */
    private int mostDirect(Recipe r, int max) {
        if (direct(r, 1) == null) return 0;
        int lo = 1, hi = max;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (direct(r, mid) != null) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** An item for each cell so that {@code k} crafts can be done from what it has, or null. */
    private String[] direct(Recipe r, int k) {
        Map<String, Integer> left = new HashMap<>(have);
        String[] out = new String[r.cells().size()];
        Map<Set<String>, String> last = new HashMap<>();
        for (int i = 0; i < out.length; i++) {
            Set<String> c = r.cells().get(i);
            if (c.isEmpty()) continue;
            // the kind already used for this ingredient if there's enough, else whatever there's most of
            String pick = last.get(c);
            if (pick == null || left.getOrDefault(pick, 0) < k) {
                pick = null;
                int best = k - 1;
                for (String o : c) {
                    int n = left.getOrDefault(o, 0);
                    if (n > best) {
                        best = n;
                        pick = o;
                    }
                }
            }
            if (pick == null) return null;
            left.merge(pick, -k, Integer::sum);
            last.put(c, pick);
            out[i] = pick;
        }
        return out;
    }
}
