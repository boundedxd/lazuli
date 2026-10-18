package dev.lazuli.core.craft;

import java.util.List;

/** Which tool to mine with, and which pickaxes count for planning: one that drops the block, with durability to spare. */
public final class ToolChoice {
    private ToolChoice() {}

    /** A candidate tool: its slot, whether it gets the block's drop, ticks to break it, durability left (huge if none). */
    public record Cand(int slot, boolean drops, double ticks, int left) {}

    /** A pickaxe in the inventory: tier 1-4 and durability left. */
    public record Pick(int tier, int left) {}

    /**
     * Slot to mine with, or -1 when {@code needsTool} and nothing drops the block. Order: drops the block, then (with
     * {@code spare}) not nearly broken (durability above {@code floor}), then fastest, the selected slot on ties.
     * A nearly-broken tool is only chosen when nothing better drops the block.
     */
    public static int best(List<Cand> cands, int selected, boolean needsTool, int floor, boolean spare) {
        Cand best = null;
        for (Cand c : cands) {
            if (needsTool && !c.drops()) continue;
            if (best == null || better(c, best, selected, floor, spare)) best = c;
        }
        return best == null ? -1 : best.slot();
    }

    private static boolean better(Cand c, Cand b, int selected, int floor, boolean spare) {
        if (c.drops() != b.drops()) return c.drops();
        if (spare) {
            boolean cw = c.left() <= floor, bw = b.left() <= floor;
            if (cw != bw) return !cw;
        }
        if (c.ticks() != b.ticks()) return c.ticks() < b.ticks();
        return c.slot() == selected && b.slot() != selected;
    }

    /** Durability a pickaxe needs left to count as the tool for a job of {@code blocks} blocks. */
    public static int floor(int blocks) {
        return blocks <= 8 ? 3 : 12;
    }

    /** Best pickaxe tier among those that can do the job (0: none); with {@code spare} the nearly broken don't count. */
    public static int pickTier(List<Pick> picks, int blocks, boolean spare) {
        int best = 0;
        int floor = floor(blocks);
        for (Pick p : picks) if (!spare || p.left() > floor) best = Math.max(best, p.tier());
        return best;
    }
}
