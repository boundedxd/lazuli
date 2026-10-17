package dev.lazuli.mod.process;

import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.ItemKeys;
import dev.lazuli.core.storage.ChestMemory;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Takes an item out of the nearest remembered chest (chestMemory, within chestRange) through {@link ContainerProcess};
 * a chest it couldn't get to or open is forgotten. With {@code shulkers} on, also out of a shulker box in the inventory
 * (placed, opened, picked up again), or a remembered chest's shulker box holding it (taken, then unpacked).
 * Shared by #getitem, #brew, #enchant and restocking.
 */
final class ChestTake {
    enum Result { RUNNING, DONE, CANCELLED }

    private enum Mode { NONE, CHEST, BOX_FROM_CHEST, UNPACK }

    private final dev.lazuli.core.settings.Settings settings;
    private final ContainerProcess containers;
    private final Function<Minecraft, ChestMemory> chests;
    private final Function<Minecraft, String> dimension;
    private final ShulkerUnpack unpack;
    private Mode mode = Mode.NONE;
    private BlockPos at;
    private String item;
    private int n;
    private ToIntFunction<LocalPlayer> count;
    private int before;

    ChestTake(dev.lazuli.core.settings.Settings settings, ContainerProcess containers, Function<Minecraft, ChestMemory> chests,
              Function<Minecraft, String> dimension) {
        this.settings = settings;
        this.containers = containers;
        this.chests = chests;
        this.dimension = dimension;
        this.unpack = new ShulkerUnpack(containers.session(), settings, ClickDriver.get());
    }

    boolean active() {
        return mode != Mode.NONE;
    }

    /**
     * Starts taking {@code n} of {@code item} (an id, or "id@potion"); false if nothing known holds some.
     * @param count how many the player holds now (to tell whether it got any)
     */
    boolean start(Minecraft mc, LocalPlayer p, String item, int n, ToIntFunction<LocalPlayer> count) {
        if (n <= 0) return false;
        this.item = item;
        this.n = n;
        this.count = count;
        before = count.applyAsInt(p);
        if (settings.chestMemory.get() && fromChest(mc, p, item, n)) {
            mode = Mode.CHEST;
            return true;
        }
        if (!settings.shulkers.get()) return false;
        int slot = ShulkerUnpack.boxWith(p, item);
        if (slot >= 0) {
            unpack.start(p, slot, item, n);
            mode = Mode.UNPACK;
            return true;
        }
        if (settings.chestMemory.get() && fromChest(mc, p, ItemKeys.inShulker(item), 1)) {
            mode = Mode.BOX_FROM_CHEST;
            return true;
        }
        return false;
    }

    private boolean fromChest(Minecraft mc, LocalPlayer p, String what, int n) {
        var mem = chests.apply(mc);
        var bp = p.blockPosition();
        var spot = mem.nearest(what, dimension.apply(mc), bp.getX(), bp.getY(), bp.getZ(), settings.chestRange.get());
        if (spot == null) return false;
        at = new BlockPos(spot.x(), spot.y(), spot.z());
        containers.start(mc, false, List.of(new ItemCount(what, ItemKeys.isInShulker(what) ? 1 : Math.min(n, spot.count()))), at);
        if (!containers.active()) {
            mem.forget(spot.dim(), spot.x(), spot.y(), spot.z());
            at = null;
            return false;
        }
        return true;
    }

    /** RUNNING while walking/taking; once it's over, how it went ({@link #got} says how many). */
    Result tick(Minecraft mc, LocalPlayer p) {
        switch (mode) {
            case NONE -> {
                return Result.DONE;
            }
            case CHEST, BOX_FROM_CHEST -> {
                if (containers.active()) return Result.RUNNING;
                BlockPos was = at;
                at = null;
                if (containers.outcome() == ContainerProcess.Outcome.CANCELLED) {
                    mode = Mode.NONE;
                    return Result.CANCELLED;
                }
                boolean gotBox = mode == Mode.BOX_FROM_CHEST && ShulkerUnpack.boxWith(p, item) >= 0;
                // couldn't get to it or open it: don't count on that chest again
                // (opened but got nothing, say with a full inventory: the chest is fine)
                if (containers.outcome() == ContainerProcess.Outcome.FAILED && !containers.opened()
                    && (mode == Mode.CHEST ? count.applyAsInt(p) <= before : !gotBox)) {
                    chests.apply(mc).forget(dimension.apply(mc), was.getX(), was.getY(), was.getZ());
                }
                if (gotBox) {
                    unpack.start(p, ShulkerUnpack.boxWith(p, item), item, n);
                    mode = Mode.UNPACK;
                    return Result.RUNNING;
                }
                mode = Mode.NONE;
                return Result.DONE;
            }
            case UNPACK -> {
                if (unpack.tick(mc, p) == ShulkerUnpack.Result.RUNNING) return Result.RUNNING;
                mode = Mode.NONE;
                return Result.DONE;
            }
        }
        return Result.DONE;
    }

    /** How many more it holds than when the take started. */
    int got(LocalPlayer p) {
        return count == null ? 0 : Math.max(0, count.applyAsInt(p) - before);
    }

    void stop() {
        if (mode == Mode.CHEST || mode == Mode.BOX_FROM_CHEST) containers.stop(false);
        if (mode == Mode.UNPACK) unpack.stop();
        mode = Mode.NONE;
        at = null;
    }
}
