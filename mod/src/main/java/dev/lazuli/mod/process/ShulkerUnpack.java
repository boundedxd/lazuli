package dev.lazuli.mod.process;

import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.List;
import java.util.function.Predicate;

/**
 * Gets items out of a shulker box in the inventory: places it next to the player, opens it, takes them, and breaks
 * it and picks it back up (always, whatever pickupStations says: it still holds things).
 */
final class ShulkerUnpack {
    enum Result { RUNNING, DONE, FAILED }

    private enum Phase { IDLE, OPEN, TAKE, PICKUP }

    private static final int TAKE_TICKS = 200;

    private final ClickDriver clicks;
    private final Station station;
    private Phase phase = Phase.IDLE;
    private String item;
    private int n, since;
    private boolean clicked;
    private String error;

    ShulkerUnpack(PathingSession session, Settings settings, ClickDriver clicks) {
        this.clicks = clicks;
        this.station = new Station(session, settings, clicks);
    }

    /** The inventory slot (0-35) of a shulker box holding some {@code item}, the fullest first; -1 for none. */
    static int boxWith(LocalPlayer p, String item) {
        int best = -1, most = 0;
        for (int i = 0; i < 36; i++) {
            int c = Inventories.inShulker(p.getInventory().getItem(i), item);
            if (c > most) {
                most = c;
                best = i;
            }
        }
        return best;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    String error() {
        return error;
    }

    /** Starts taking {@code n} of {@code item} out of the box at inventory slot {@code slot}. */
    void start(LocalPlayer p, int slot, String item, int n) {
        this.item = item;
        this.n = n;
        error = null;
        clicked = false;
        String key = Inventories.key(p.getInventory().getItem(slot));
        station.startPlaced(p, s -> s.getBlock() instanceof ShulkerBoxBlock, key, "shulker box", m -> m instanceof ShulkerBoxMenu);
        phase = Phase.OPEN;
    }

    Result tick(Minecraft mc, LocalPlayer p) {
        switch (phase) {
            case IDLE -> {
                return error == null ? Result.DONE : Result.FAILED;
            }
            case OPEN -> {
                switch (station.tick(mc, p)) {
                    case OPEN -> {
                        phase = Phase.TAKE;
                        since = p.tickCount;
                    }
                    case FAILED -> {
                        error = station.error();
                        // placed but it wouldn't open: still pick it up
                        if (!pickUp(p)) phase = Phase.IDLE;
                        return phase == Phase.IDLE ? Result.FAILED : Result.RUNNING;
                    }
                    case WORKING -> {}
                }
            }
            case TAKE -> {
                if (!(p.containerMenu instanceof ShulkerBoxMenu menu)) {
                    if (!pickUp(p)) phase = Phase.IDLE;
                    return Result.RUNNING;
                }
                if (!clicked) {
                    int[] mine = Inventories.slots(menu, p, true), theirs = Inventories.slots(menu, p, false);
                    Menu sim = Inventories.read(menu, p);
                    Predicate<String> m = Inventories.matcher(item);
                    List<Click> plan = Moves.transfer(sim, theirs, mine, m, n);
                    clicks.play(menu.containerId, plan);
                    clicked = true;
                    return Result.RUNNING;
                }
                if (clicks.busy() && p.tickCount - since < TAKE_TICKS) return Result.RUNNING;
                p.closeContainer();
                if (!pickUp(p)) phase = Phase.IDLE;
            }
            case PICKUP -> {
                if (station.pickUp(mc, p)) phase = Phase.IDLE;
            }
        }
        if (phase == Phase.IDLE) return error == null ? Result.DONE : Result.FAILED;
        return Result.RUNNING;
    }

    private boolean pickUp(LocalPlayer p) {
        if (!station.startPickUp(p, true)) return false;
        phase = Phase.PICKUP;
        return true;
    }

    void stop() {
        station.cancel();
        LocalPlayer p = Minecraft.getInstance().player;
        if (phase == Phase.TAKE && p != null && p.containerMenu instanceof ShulkerBoxMenu) p.closeContainer();
        phase = Phase.IDLE;
    }
}
