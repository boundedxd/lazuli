package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Tools;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * What a process does when it needs a free inventory slot and has none: (a) throw out a stack of junk (what #mine does,
 * {@link Tools#junkSlot}), else (b) put the non-essentials into a remembered chest ({@link ContainerProcess}), else
 * (c) {@link State#STUCK}: the caller stops with {@link #reason()}. Call {@link #tick} every tick while a slot is
 * wanted; on WORKING the caller stands still (cancels its trip, releases its keys).
 */
public final class MakeRoom {
    public enum State { ROOM, WORKING, STUCK }

    private static final int MAX_THROWS = 12;

    private final Settings settings;
    private final ContainerProcess containers;
    private final Consumer<String> chat;
    private int throwsDone;
    private boolean depositStarted, depositDone;
    private String reason = "Inventory full";

    /** @param containers for the chest deposit, or null for junk only */
    public MakeRoom(Settings settings, ContainerProcess containers, Consumer<String> chat) {
        this.settings = settings;
        this.containers = containers;
        this.chat = chat;
    }

    public void reset() {
        throwsDone = 0;
        depositStarted = depositDone = false;
        reason = "Inventory full";
    }

    /** Why it's {@link State#STUCK}. */
    public String reason() {
        return reason;
    }

    /** Whether the inventory is out of free slots. */
    public static boolean full(LocalPlayer p) {
        return p.getInventory().getFreeSlot() < 0;
    }

    /** Full, and no stack of {@code id} with room to grow either (so a drop of it has nowhere to go). */
    public static boolean full(LocalPlayer p, String id) {
        if (!full(p)) return false;
        for (int i = 0; i < 36; i++) {
            var st = p.getInventory().getItem(i);
            if (st.getCount() < st.getMaxStackSize() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(id)) return false;
        }
        return true;
    }

    /** @param keep ids that must not be put into a chest (what the caller is collecting); may be null */
    public State tick(Minecraft mc, LocalPlayer p, Predicate<String> keep) {
        if (!full(p)) {
            if (!(containers != null && depositStarted && containers.active())) {
                reset();
                return State.ROOM;
            }
            return State.WORKING; // let the deposit finish
        }
        ClickDriver clicks = ClickDriver.get();
        if (clicks.busy()) return State.WORKING;
        if (depositStarted && !depositDone) {
            if (containers.active()) return State.WORKING;
            depositDone = true;
            if (containers.outcome() == ContainerProcess.Outcome.CANCELLED) {
                reason = "Stopped";
                return State.STUCK;
            }
        }
        if (settings.makeRoomDropJunk.get() && throwsDone < MAX_THROWS) {
            int junk = Tools.junkSlot(p, settings);
            if (junk >= 0) {
                clicks.add(p.inventoryMenu.containerId, List.of(Click.throwAll(junk)));
                throwsDone++;
                return State.WORKING;
            }
        }
        if (containers != null && settings.makeRoomDeposit.get() && !depositStarted) {
            List<ItemCount> items = depositable(p, keep);
            if (!items.isEmpty()) {
                depositStarted = true;
                chat.accept(Chat.info("Inventory full: putting some away"));
                containers.start(mc, true, items, null);
                return State.WORKING;
            }
        }
        reason = Chat.err("Inventory full: nothing to throw out{}", containers != null && settings.makeRoomDeposit.get()
            ? depositStarted ? " and no chest took anything" : " or put away" : "");
        return State.STUCK;
    }

    /** Ids of everything that isn't a tool, weapon, armor, food, or something being collected. */
    private static List<ItemCount> depositable(LocalPlayer p, Predicate<String> keep) {
        List<ItemCount> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            var st = p.getInventory().getItem(i);
            if (st.isEmpty() || st.isDamageableItem() || st.has(DataComponents.FOOD) || st.has(DataComponents.EQUIPPABLE)) continue;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
            if (seen.contains(id) || (keep != null && keep.test(id)) || Tools.collecting(id)) continue;
            seen.add(id);
            out.add(new ItemCount(id, -1));
        }
        return out;
    }
}
