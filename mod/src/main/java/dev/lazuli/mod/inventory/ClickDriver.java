package dev.lazuli.mod.inventory;

import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Sends planned inventory clicks a few a tick (see the clicksPerTick setting), into the menu they were planned for.
 * Every click in the player's own inventory goes through here: with openInventory on it first stands still and opens
 * the inventory screen, as a player would (anticheats flag clicks with no screen open, or while moving), and closes
 * it again when done.
 */
public final class ClickDriver {
    private static ClickDriver instance;
    private static boolean holding;

    private final Settings settings;
    private final Deque<Click> queue = new ArrayDeque<>();
    private int containerId = -1;
    /** Whether Lazuli opened the inventory screen; ticks to wait after opening; ticks it's still wanted open. */
    private boolean opened;
    private int settle, lease;

    public ClickDriver(Settings settings) {
        this.settings = settings;
        instance = this;
    }

    public static ClickDriver get() {
        return instance;
    }

    /** True while it holds the player still (no movement keys, no path steering) to use the inventory screen. */
    public static boolean holding() {
        return holding;
    }

    /** Replaces whatever was queued. */
    public void play(int containerId, List<Click> clicks) {
        queue.clear();
        add(containerId, clicks);
    }

    /** Queues more clicks after the ones waiting (dropping those if they were for another menu). */
    public void add(int containerId, List<Click> clicks) {
        if (this.containerId != containerId) queue.clear();
        queue.addAll(clicks);
        this.containerId = containerId;
    }

    /** Keeps the inventory screen open a few more ticks after the queue runs out (call each tick it's needed). */
    public void keepOpen() {
        lease = 3;
    }

    public boolean busy() {
        return !queue.isEmpty();
    }

    public void tick(Minecraft mc, int perTick) {
        LocalPlayer p = mc.player;
        if (lease > 0) lease--;
        if (p == null || mc.gameMode == null) {
            queue.clear();
            opened = holding = false;
            return;
        }
        if (queue.isEmpty()) {
            if (opened && lease == 0) close(mc, p);
            if (!opened) holding = false;
            return;
        }
        if (p.containerMenu.containerId != containerId) {
            queue.clear(); // the menu closed (or another opened): the plan no longer applies
            return;
        }
        boolean own = containerId == p.inventoryMenu.containerId;
        if (own && settings.openInventory.get() && !(mc.screen instanceof InventoryScreen)) {
            if (opened) { // the player closed it: drop the plan
                queue.clear();
                opened = holding = false;
                return;
            }
            holding = true;
            if (mc.screen != null || !still(p)) return;
            mc.setScreen(new InventoryScreen(p));
            opened = true;
            settle = 2;
            return;
        }
        if (settle > 0) {
            settle--;
            return;
        }
        for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
            Click c = queue.poll();
            mc.gameMode.handleInventoryMouseClick(containerId, c.slot(), c.button(), type(c.kind()), p);
        }
    }

    private void close(Minecraft mc, LocalPlayer p) {
        if (mc.screen instanceof InventoryScreen) p.closeContainer();
        opened = holding = false;
    }

    /** Stopped on the ground: no inventory use while moving. */
    private static boolean still(LocalPlayer p) {
        var v = p.getDeltaMovement();
        return (p.onGround() || p.isInWater()) && v.x * v.x + v.z * v.z < 1e-4;
    }

    private static ClickType type(Click.Kind k) {
        return switch (k) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case THROW -> ClickType.THROW;
            case SWAP -> ClickType.SWAP;
        };
    }
}
