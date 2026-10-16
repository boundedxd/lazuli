package dev.lazuli.core.portal;

/**
 * Whether a far overworld trip is quicker through the nether (one nether block is eight here), and the coordinates on
 * the other side. Distances are in blocks walked; building a portal counts as {@link #BUILD} blocks of walking, going
 * through one as {@link #ENTER}, and nether ground as {@link #NETHER_ROUGH} times longer than it looks.
 */
public final class NetherRoute {
    public static final double BUILD = 60, ENTER = 10, NETHER_ROUGH = 1.6;
    /** The nether way has to be at least this much shorter to bother. */
    public static final double MARGIN = 0.75;

    private NetherRoute() {}

    public static int toNether(int overworld) {
        return Math.floorDiv(overworld, 8);
    }

    public static int toOverworld(int nether) {
        return nether * 8;
    }

    /**
     * @param walk        straight overworld distance to the goal
     * @param toEntrance  overworld walk to a known portal (negative: none, one gets built here)
     * @param exitKnown   a portal is known in the nether near the goal's nether spot
     */
    public static double netherCost(double walk, double toEntrance, boolean exitKnown) {
        double c = toEntrance >= 0 ? toEntrance + ENTER : BUILD + ENTER;
        c += walk / 8 * NETHER_ROUGH;
        c += exitKnown ? ENTER : BUILD + ENTER;
        return c;
    }

    /** Whether to go through the nether: far enough, and quicker with the margin. */
    public static boolean worthIt(double walk, double minDistance, double toEntrance, boolean exitKnown) {
        return walk >= minDistance && netherCost(walk, toEntrance, exitKnown) < walk * MARGIN;
    }

    /** How many portals the trip needs built. */
    public static int builds(double toEntrance, boolean exitKnown) {
        return (toEntrance >= 0 ? 0 : 1) + (exitKnown ? 0 : 1);
    }
}
