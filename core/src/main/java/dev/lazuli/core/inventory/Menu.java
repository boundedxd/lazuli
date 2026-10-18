package dev.lazuli.core.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * An open menu's slots and the item on the cursor, with vanilla's rules for clicks. The planner works on a copy to
 * check its plans; the mod builds one from the real menu.
 */
public final class Menu {
    private final Stack[] slots;
    private Stack cursor = Stack.EMPTY;
    /** For a shift-click on a slot: the slots it fills, in the order the game tries them. */
    private final IntFunction<int[]> quickMoveTo;
    private int dropped;

    public Menu(Stack[] slots, IntFunction<int[]> quickMoveTo) {
        this.slots = slots.clone();
        this.quickMoveTo = quickMoveTo;
    }

    public Menu copy() {
        Menu m = new Menu(slots, quickMoveTo);
        m.cursor = cursor;
        m.dropped = dropped;
        return m;
    }

    public int size() {
        return slots.length;
    }

    public Stack get(int slot) {
        return slots[slot];
    }

    public Stack cursor() {
        return cursor;
    }

    /** Items thrown out so far. */
    public int dropped() {
        return dropped;
    }

    public int[] quickMoveTo(int slot) {
        return quickMoveTo.apply(slot);
    }

    /** How many of {@code item} the slots in {@code range} hold. */
    public int count(int[] range, Predicate<String> item) {
        int n = 0;
        for (int i : range) if (!slots[i].empty() && item.test(slots[i].item())) n += slots[i].count();
        return n;
    }

    public void apply(Click c) {
        Stack s = slots[c.slot()];
        switch (c.kind()) {
            case THROW -> {
                if (!cursor.empty() || s.empty()) return;
                int n = c.button() == 1 ? s.count() : 1;
                slots[c.slot()] = s.withCount(s.count() - n);
                dropped += n;
            }
            case QUICK_MOVE -> {
                if (s.empty()) return;
                int left = s.count();
                for (int pass = 0; pass < 2 && left > 0; pass++) {
                    for (int t : quickMoveTo(c.slot())) {
                        Stack d = slots[t];
                        // first onto stacks of the same item, then into empty slots
                        if (pass == 0 ? !d.stacksWith(s) : !d.empty()) continue;
                        int put = Math.min(left, s.max() - d.count());
                        if (put <= 0) continue;
                        slots[t] = s.withCount(d.count() + put);
                        left -= put;
                        if (left == 0) break;
                    }
                }
                slots[c.slot()] = s.withCount(left);
            }
            case SWAP -> {} // the menu doesn't know where the hotbar is; planners don't use it
            case PICKUP -> {
                boolean right = c.button() == 1;
                if (cursor.empty()) {
                    if (s.empty()) return;
                    int take = right ? (s.count() + 1) / 2 : s.count();
                    cursor = s.withCount(take);
                    slots[c.slot()] = s.withCount(s.count() - take);
                } else if (s.empty() || s.stacksWith(cursor)) {
                    int put = Math.min(right ? 1 : cursor.count(), cursor.max() - s.count());
                    if (put <= 0) return;
                    slots[c.slot()] = cursor.withCount(s.count() + put);
                    cursor = cursor.withCount(cursor.count() - put);
                } else {
                    slots[c.slot()] = cursor;
                    cursor = s;
                }
            }
        }
    }

    /**
     * A shift-click on {@code from} that the game is known to route into {@code to} only (a furnace's input slot, a
     * stand's bottle slot...): moves as much as fits there.
     */
    public void quickInto(int from, int to) {
        Stack s = slots[from], d = slots[to];
        if (s.empty() || !(d.empty() || d.stacksWith(s))) return;
        int put = Math.min(s.count(), s.max() - d.count());
        if (put <= 0) return;
        slots[to] = s.withCount(d.count() + put);
        slots[from] = s.withCount(s.count() - put);
    }

    public void applyAll(List<Click> clicks) {
        for (Click c : clicks) apply(c);
    }

    @Override
    public String toString() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) if (!slots[i].empty()) out.add(i + ":" + slots[i].item() + "x" + slots[i].count());
        return out + (cursor.empty() ? "" : " cursor " + cursor.item() + "x" + cursor.count());
    }

    /** Slots from {@code from} up to (not including) {@code to}. */
    public static int[] range(int from, int to) {
        int[] r = new int[Math.max(0, to - from)];
        Arrays.setAll(r, i -> from + i);
        return r;
    }
}
