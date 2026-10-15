package dev.lazuli.mod.inventory;

import dev.lazuli.core.inventory.Click;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ClickType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Sends planned inventory clicks a few a tick (see the clicksPerTick setting), into the menu they were planned for. */
public final class ClickDriver {
    private final Deque<Click> queue = new ArrayDeque<>();
    private int containerId = -1;

    /** Replaces whatever was queued. */
    public void play(int containerId, List<Click> clicks) {
        queue.clear();
        queue.addAll(clicks);
        this.containerId = containerId;
    }

    public boolean busy() {
        return !queue.isEmpty();
    }

    public void tick(Minecraft mc, int perTick) {
        if (queue.isEmpty()) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || p.containerMenu.containerId != containerId) {
            queue.clear(); // the menu closed (or another opened): the plan no longer applies
            return;
        }
        for (int i = 0; i < perTick && !queue.isEmpty(); i++) {
            Click c = queue.poll();
            mc.gameMode.handleInventoryMouseClick(containerId, c.slot(), c.button(), type(c.kind()), p);
        }
    }

    private static ClickType type(Click.Kind k) {
        return switch (k) {
            case PICKUP -> ClickType.PICKUP;
            case QUICK_MOVE -> ClickType.QUICK_MOVE;
            case THROW -> ClickType.THROW;
        };
    }
}
