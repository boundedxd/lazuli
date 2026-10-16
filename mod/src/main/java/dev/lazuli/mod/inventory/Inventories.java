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

    /** {@link Stack#item()} for a game stack: its id, plus a hash of whatever keeps it from stacking with a plain one
     * (and a potion's kind, see {@link dev.lazuli.core.inventory.ItemKeys}). */
    public static String key(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        if (s.getComponentsPatch().isEmpty()) return id;
        String kind = potionKind(s);
        return id + "#" + s.getComponentsPatch().hashCode() + (kind == null ? "" : "@" + kind);
    }

    /** What a chest remembers a stack as: its id, and for a potion also "id@potion" (so #brew can find it). */
    public static List<String> memoryIds(ItemStack s) {
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
        String kind = potionKind(s);
        return kind == null ? List.of(id) : List.of(id, id + "@" + kind);
    }

    /** What's inside a shulker box stack (empty for anything else). */
    public static List<ItemStack> shulkerContents(ItemStack s) {
        if (!(s.getItem() instanceof net.minecraft.world.item.BlockItem b
            && b.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock)) return List.of();
        var c = s.get(net.minecraft.core.component.DataComponents.CONTAINER);
        return c == null ? List.of() : c.nonEmptyStream().toList();
    }

    /** How many of {@code item} (an id, or "id@potion") a shulker box stack holds. */
    public static int inShulker(ItemStack box, String item) {
        Predicate<String> m = matcher(item);
        int n = 0;
        for (ItemStack in : shulkerContents(box)) if (m.test(key(in))) n += in.getCount();
        return n;
    }

    /** A bottle's (or tipped arrow's) potion id, or null. */
    public static String potionKind(ItemStack s) {
        var pc = s.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS);
        if (pc == null) return null;
        return pc.potion().flatMap(net.minecraft.core.Holder::unwrapKey).map(k -> k.identifier().toString()).orElse(null);
    }

    /** Matches stacks of {@code name} (an id, namespace optional, whatever its enchantments...); "all" matches anything. */
    public static Predicate<String> matcher(String name) {
        return dev.lazuli.core.inventory.ItemKeys.matcher(name);
    }

    /** A full item id: "Raw_Iron" -> "minecraft:raw_iron". */
    public static String id(String name) {
        return dev.lazuli.core.inventory.ItemKeys.id(name);
    }

    /** Plain items (nothing enchanted, named or damaged) in the main inventory and hotbar, by id. */
    public static java.util.Map<String, Integer> plainCounts(Player p) {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String k = key(s);
            if (!k.contains("#")) out.merge(k, s.getCount(), Integer::sum);
        }
        return out;
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
