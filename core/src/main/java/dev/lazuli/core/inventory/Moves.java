package dev.lazuli.core.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Plans the clicks that move or throw out items, checking each step on a copy of the menu. */
public final class Moves {
    private Moves() {}

    /**
     * Clicks moving {@code count} of the items matching {@code item} (all of them when negative) from the {@code from}
     * slots into the {@code to} slots, where a shift-click on a {@code from} slot sends them. Moves as many as fit.
     * Whole stacks go by shift-click; the rest of a stack is picked up, dealt out one by one and put back.
     */
    public static List<Click> transfer(Menu menu, int[] from, int[] to, Predicate<String> item, int count) {
        Menu sim = menu.copy();
        List<Click> out = new ArrayList<>();
        int left = count < 0 ? Integer.MAX_VALUE : count;
        for (int slot : from) {
            if (left <= 0) break;
            Stack s = sim.get(slot);
            if (s.empty() || !item.test(s.item())) continue;
            if (s.count() <= left) {
                int before = sim.count(to, s.item()::equals);
                Click c = Click.shift(slot);
                sim.apply(c);
                int moved = sim.count(to, s.item()::equals) - before;
                if (moved > 0) out.add(c);
                left -= moved;
                if (!sim.get(slot).empty()) break; // the other side is full
                continue;
            }
            int target = room(sim, to, s, left);
            if (target < 0) break;
            List<Click> part = new ArrayList<>();
            part.add(Click.left(slot));
            for (int i = 0; i < left; i++) part.add(Click.right(target));
            part.add(Click.left(slot));
            sim.applyAll(part);
            out.addAll(part);
            left = 0;
        }
        return out;
    }

    /** A slot in {@code to} that can take {@code n} more of {@code s}: a stack of it with room, else an empty one; or -1. */
    private static int room(Menu m, int[] to, Stack s, int n) {
        for (int t : to) if (m.get(t).stacksWith(s) && s.max() - m.get(t).count() >= n) return t;
        for (int t : to) if (m.get(t).empty()) return t;
        return -1;
    }

    /** Clicks throwing out {@code count} of the items matching {@code item} from the {@code from} slots (all when negative). */
    public static List<Click> drop(Menu menu, int[] from, Predicate<String> item, int count) {
        List<Click> out = new ArrayList<>();
        int left = count < 0 ? Integer.MAX_VALUE : count;
        for (int slot : from) {
            Stack s = menu.get(slot);
            if (left <= 0) break;
            if (s.empty() || !item.test(s.item())) continue;
            if (s.count() <= left) {
                out.add(Click.throwAll(slot));
                left -= s.count();
            } else {
                for (int i = 0; i < left; i++) out.add(Click.throwOne(slot));
                left = 0;
            }
        }
        return out;
    }
}
