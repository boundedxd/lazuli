package dev.lazuli.core.mine;

/** Which blocks are ores (hidden by anti-xray) and the Y level to strip-mine for each. */
public final class OreDepth {
    private OreDepth() {}

    private static String path(String block) {
        String b = block.startsWith("minecraft:") ? block.substring(10) : block;
        if (b.startsWith("deepslate_") && b.endsWith("_ore")) b = b.substring(10);
        return b;
    }

    public static boolean isOre(String block) {
        String b = path(block);
        return b.endsWith("_ore") || b.equals("ancient_debris");
    }

    /**
     * Where most of it is (1.18+ generation), kept 5 above the bottom of the world (bedrock); null for a block that
     * isn't an ore found underground in this dimension (look around the surface instead).
     */
    public static Integer bestY(String block, String dimension, int minY, int maxY) {
        boolean nether = dimension.endsWith("the_nether"), end = dimension.endsWith("the_end");
        Integer y = switch (path(block)) {
            case "coal_ore", "copper_ore" -> nether || end ? null : 48;
            case "iron_ore" -> nether || end ? null : 16;
            case "gold_ore" -> nether || end ? null : -16;
            case "lapis_ore" -> nether || end ? null : 0;
            case "redstone_ore", "diamond_ore" -> nether || end ? null : -58;
            case "nether_quartz_ore", "nether_gold_ore", "ancient_debris" -> nether ? 15 : null;
            default -> null;
        };
        if (y == null) return null;
        return Math.max(minY + 5, Math.min(maxY - 5, y));
    }
}
