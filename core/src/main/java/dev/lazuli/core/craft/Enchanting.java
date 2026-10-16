package dev.lazuli.core.craft;

/** Choosing an enchanting table option, and how much material an anvil repair takes, as the game counts them. */
public final class Enchanting {
    private Enchanting() {}

    /** Anvil repairs cost under this many levels in survival ("Too expensive!" at 40). */
    public static final int TOO_EXPENSIVE = 40;

    /**
     * The table option (0-2) to take: {@code wanted} (0-2) if it can be paid for, or with {@code wanted} -1 the best
     * (lowest row) that can; -1 if none. Option i costs i+1 lapis and levels, and needs {@code costs[i]} levels to have.
     */
    public static int pick(int[] costs, int levels, int lapis, int wanted) {
        if (wanted >= 0) return wanted < costs.length && affordable(costs, levels, lapis, wanted) ? wanted : -1;
        for (int i = costs.length - 1; i >= 0; i--) if (affordable(costs, levels, lapis, i)) return i;
        return -1;
    }

    private static boolean affordable(int[] costs, int levels, int lapis, int i) {
        return costs[i] > 0 && levels >= costs[i] && levels >= i + 1 && lapis >= i + 1;
    }

    /** Units of repair material (iron ingots, diamonds...) a repair uses: each fixes a quarter of the durability. */
    public static int repairMaterials(int damage, int maxDamage) {
        int quarter = Math.max(1, maxDamage / 4);
        return damage <= 0 ? 0 : (damage + quarter - 1) / quarter;
    }
}
