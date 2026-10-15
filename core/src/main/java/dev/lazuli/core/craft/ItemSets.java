package dev.lazuli.core.craft;

import java.util.ArrayList;
import java.util.List;

/** Shortcuts for #getitem: {@code iron_tools}, {@code diamond_armor}, {@code iron_gear} (both). */
public final class ItemSets {
    private static final String[] TOOLS = {"pickaxe", "axe", "shovel", "sword"};
    private static final String[] ARMOR = {"helmet", "chestplate", "leggings", "boots"};
    private static final List<String> TOOL_MATERIALS = List.of("wooden", "stone", "iron", "golden", "diamond", "netherite");
    private static final List<String> ARMOR_MATERIALS = List.of("leather", "chainmail", "iron", "golden", "diamond", "netherite");

    private ItemSets() {}

    /** The items a set name stands for (keeping a {@code minecraft:} in front), or null if it isn't one. */
    public static List<String> expand(String name) {
        String ns = "";
        String n = name;
        int colon = n.indexOf(':');
        if (colon >= 0) {
            ns = n.substring(0, colon + 1);
            n = n.substring(colon + 1);
        }
        int u = n.lastIndexOf('_');
        if (u <= 0) return null;
        String mat = switch (n.substring(0, u)) {
            case "wood" -> "wooden";
            case "gold" -> "golden";
            case "chain" -> "chainmail";
            default -> n.substring(0, u);
        };
        String kind = n.substring(u + 1);
        boolean tools = TOOL_MATERIALS.contains(mat), armor = ARMOR_MATERIALS.contains(mat);
        List<String> out = new ArrayList<>();
        switch (kind) {
            case "tools" -> {
                if (!tools) return null;
                for (String t : TOOLS) out.add(ns + mat + "_" + t);
            }
            case "armor", "armour" -> {
                if (!armor) return null;
                for (String a : ARMOR) out.add(ns + mat + "_" + a);
            }
            case "gear", "set", "kit" -> {
                if (!tools && !armor) return null;
                if (tools) for (String t : TOOLS) out.add(ns + mat + "_" + t);
                if (armor) for (String a : ARMOR) out.add(ns + mat + "_" + a);
            }
            default -> {
                return null;
            }
        }
        return out;
    }
}
