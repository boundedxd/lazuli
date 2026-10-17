package dev.lazuli.mod.control;

import dev.lazuli.core.inventory.Click;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.Predicate;

/** Getting an item into the main hand: picked on the hotbar, or swapped there from the inventory. */
public final class Hands {
    private Hands() {}

    /** True once a matching item is in the main hand; false while it's being swapped in, or if there's none. */
    public static boolean hold(LocalPlayer p, ClickDriver clicks, Predicate<ItemStack> want) {
        var inv = p.getInventory();
        if (want.test(inv.getItem(inv.getSelectedSlot()))) return true;
        int slot = -1;
        for (int i = 0; i < 36 && slot < 0; i++) if (want.test(inv.getItem(i))) slot = i;
        if (slot < 0) return false;
        if (slot < 9) inv.setSelectedSlot(slot);
        else if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, inv.getSelectedSlot())));
        return slot < 9;
    }

    /** Whether the main inventory or hotbar holds a matching item. */
    public static boolean has(LocalPlayer p, Predicate<ItemStack> want) {
        for (int i = 0; i < 36; i++) if (want.test(p.getInventory().getItem(i))) return true;
        return false;
    }

    /** How many matching items the main inventory and hotbar hold. */
    public static int count(LocalPlayer p, Predicate<ItemStack> want) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (want.test(p.getInventory().getItem(i))) n += p.getInventory().getItem(i).getCount();
        return n;
    }
}
