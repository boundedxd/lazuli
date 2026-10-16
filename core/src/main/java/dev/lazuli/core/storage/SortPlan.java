package dev.lazuli.core.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * #sort: which containers each item should live in. Items with the most stacks pick first; each goes to the boxes
 * that already hold the most of it, as far as they have room (in stacks), spilling into the next ones. What's in a
 * box beyond what it's given is misplaced; {@link #room} says how much of an item a box still takes.
 */
public final class SortPlan {
    /** A container: an id (its position), how many slots, what's in it. */
    public record Box(String id, int slots, Map<String, Integer> items) {}

    /** item -> box -> how many it should hold. */
    private final Map<String, Map<String, Integer>> alloc = new HashMap<>();
    private final Map<String, Box> boxes = new LinkedHashMap<>();

    private SortPlan() {}

    public static SortPlan of(List<Box> boxes, ToIntFunction<String> stackSize) {
        SortPlan p = new SortPlan();
        Map<String, Integer> total = new TreeMap<>();
        for (Box b : boxes) {
            p.boxes.put(b.id(), b);
            b.items().forEach((i, n) -> total.merge(i, n, Integer::sum));
        }
        Map<String, Integer> free = new HashMap<>();
        for (Box b : boxes) free.put(b.id(), b.slots());
        List<String> items = new ArrayList<>(total.keySet());
        items.sort(Comparator.comparingInt((String i) -> -stacks(total.get(i), stackSize.applyAsInt(i))).thenComparing(i -> i));
        for (String item : items) {
            int stack = Math.max(1, stackSize.applyAsInt(item));
            int left = total.get(item);
            List<Box> order = new ArrayList<>(boxes);
            // where most of it is first (then the emptiest, so a new item gets a box of its own)
            order.sort(Comparator.comparingInt((Box b) -> -b.items().getOrDefault(item, 0)).thenComparingInt(b -> -free.get(b.id())));
            Map<String, Integer> to = new LinkedHashMap<>();
            for (Box b : order) {
                if (left <= 0) break;
                int fit = Math.min(left, free.get(b.id()) * stack);
                if (fit <= 0) continue;
                to.put(b.id(), fit);
                free.merge(b.id(), -stacks(fit, stack), Integer::sum);
                left -= fit;
            }
            p.alloc.put(item, to);
        }
        return p;
    }

    private static int stacks(int n, int stack) {
        return (n + Math.max(1, stack) - 1) / Math.max(1, stack);
    }

    /** How many of {@code item} {@code box} should end up holding. */
    public int share(String item, String box) {
        return alloc.getOrDefault(item, Map.of()).getOrDefault(box, 0);
    }

    /** The boxes {@code item} lives in, most first. */
    public List<String> homes(String item) {
        return List.copyOf(alloc.getOrDefault(item, Map.of()).keySet());
    }

    /** What's in {@code box} that belongs elsewhere: item -> how many to take out. */
    public Map<String, Integer> misplaced(String box) {
        Map<String, Integer> out = new TreeMap<>();
        Box b = boxes.get(box);
        if (b == null) return out;
        b.items().forEach((i, n) -> {
            int extra = n - alloc.getOrDefault(i, Map.of()).getOrDefault(box, 0);
            if (extra > 0) out.put(i, extra);
        });
        return out;
    }

    /** How many more of {@code item} {@code box} should take. */
    public int room(String box, String item) {
        Box b = boxes.get(box);
        int want = alloc.getOrDefault(item, Map.of()).getOrDefault(box, 0);
        return Math.max(0, want - (b == null ? 0 : b.items().getOrDefault(item, 0)));
    }

    /** Nothing anywhere is misplaced. */
    public boolean sorted() {
        for (String id : boxes.keySet()) if (!misplaced(id).isEmpty()) return false;
        return true;
    }
}
