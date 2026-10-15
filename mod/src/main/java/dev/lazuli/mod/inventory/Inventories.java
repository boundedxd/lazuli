package dev.lazuli.mod.inventory;

import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Stack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/** Reads the game's menus into the planner's {@link Menu}s. */
public final class Inventories {
    private Inventories() {}

    /** The player's own menu: main inventory, then the hotbar (no armor, no crafting grid). */
    public static final int[] PLAYER_MAIN = Menu.range(9, 45);
    public static final int OFFHAND = 45;

    /** {@link Stack#item()} for a game stack: its id, plus a hash of whatever keeps it from stacking with a plain one. */
    public static String key(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        return s.getComponentsPatch().isEmpty() ? id : id + "#" + s.getComponentsPatch().hashCode();
    }

    /** Matches stacks of {@code name} (an id, namespace optional, whatever its enchantments...); "all" matches anything. */
    public static Predicate<String> matcher(String name) {
        if (name.equals("all")) return k -> true;
        String id = name.contains(":") ? name : "minecraft:" + name.toLowerCase(Locale.ROOT);
        return k -> k.equals(id) || k.startsWith(id + "#");
    }

    public static Stack stack(ItemStack s) {
        return s.isEmpty() ? Stack.EMPTY : new Stack(key(s), s.getCount(), s.getMaxStackSize());
    }

    /**
     * The menu as the planner sees it. A shift-click moves between the player's inventory and the rest (the chest,
     * furnace...), into the player's from the hotbar end first, as the game does.
     */
    public static Menu read(AbstractContainerMenu menu, Player p) {
        int n = menu.slots.size();
        Stack[] st = new Stack[n];
        List<Integer> own = new ArrayList<>(), other = new ArrayList<>();
        boolean[] mine = new boolean[n];
        for (int i = 0; i < n; i++) {
            var slot = menu.slots.get(i);
            st[i] = stack(slot.getItem());
            mine[i] = slot.container == p.getInventory();
            (mine[i] ? own : other).add(i);
        }
        int[] toOwn = new int[own.size()];
        for (int i = 0; i < toOwn.length; i++) toOwn[i] = own.get(own.size() - 1 - i);
        int[] toOther = other.stream().mapToInt(Integer::intValue).toArray();
        return new Menu(st, slot -> mine[slot] ? toOther : toOwn);
    }

    /** The slots of {@code menu} that are the player's inventory (main and hotbar), or the rest. */
    public static int[] slots(AbstractContainerMenu menu, Player p, boolean players) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            var slot = menu.slots.get(i);
            boolean mine = slot.container == p.getInventory();
            // the player's armor and offhand slots are never moved about
            if (mine && slot.getContainerSlot() >= 36) continue;
            if (mine == players) out.add(i);
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }
}
