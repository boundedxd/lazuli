package dev.lazuli.core.inventory;

/** The player menu's slot layout (vanilla InventoryMenu) and where to put things in it. */
public final class PlayerSlots {
    private PlayerSlots() {}

    /** Menu slots 9..35. */
    public static final int[] MAIN = Menu.range(9, 36);
    /** Menu slots 36..44. */
    public static final int[] HOTBAR = Menu.range(36, 45);
    /** The offhand slot in the player menu. */
    public static final int OFFHAND = 45;

    /** Main inventory and hotbar: everything a player can shuffle around. */
    public static int[] carried() {
        int[] out = new int[MAIN.length + HOTBAR.length];
        System.arraycopy(MAIN, 0, out, 0, MAIN.length);
        System.arraycopy(HOTBAR, 0, out, MAIN.length, HOTBAR.length);
        return out;
    }

    /** Where {@code item} should go in the hotbar: a stack of it, else an empty slot, else -1 (none free). */
    public static int hotbarFor(Menu menu, String item) {
        for (int s : HOTBAR) if (!menu.get(s).empty() && item.equals(menu.get(s).item())) return s;
        for (int s : HOTBAR) if (menu.get(s).empty()) return s;
        return -1;
    }

    /** Where in the player menu slot {@code index} is, for reporting. */
    public static String zone(int index) {
        if (index >= 36 && index <= 44) return "hotbar";
        if (index >= 9 && index <= 35) return "inventory";
        if (index == OFFHAND) return "offhand";
        if (index >= 5 && index <= 8) return "armor";
        if (index >= 0 && index <= 4) return "crafting";
        return "player";
    }
}
