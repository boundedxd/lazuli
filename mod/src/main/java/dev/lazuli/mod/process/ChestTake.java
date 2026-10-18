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
 * a chest it couldn't get to or open is left alone a while and forgotten at the third strike; it goes on to the next chest when one doesn't hold enough. With {@code shulkers} on, also out of a shulker box in the inventory
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
    /** Chests this take already went to (it moves on to the next one when one didn't hold enough). */
    private final java.util.Set<BlockPos> tried = new java.util.HashSet<>();
    /** How long a chest that couldn't be reached is left alone before the next strike (ms). */
    private static final long COOLDOWN = 60_000;

    ChestTake(dev.lazuli.core.settings.Settings settings, ContainerProcess containers, Function<Minecraft, ChestMemory> chests,
              Function<Minecraft, String> dimension) {
        this.settings = settings;
        this.containers = containers;
        this.chests = chests;
        this.dimension = dimension;
        this.unpack = new ShulkerUnpack(containers.session(), settings, ClickDriver.get());
    }

    /** What a take is for: the finished thing asked for, something to make it with, or a restock. */
    enum Use { FINAL, INGREDIENT, RESTOCK }

    /** The one place that says whether remembered chests may be used for {@code use} (chestMemory + chestFinal/chestIngredients). */
    static boolean allowed(dev.lazuli.core.settings.Settings s, Use use) {
        if (!s.chestMemory.get()) return false;
        return switch (use) {
            case FINAL -> s.chestFinal.get();
            case INGREDIENT -> s.chestIngredients.get();
            case RESTOCK -> true;
        };
    }

    /**
     * What remembered chests within chestRange hold, as plain item ids (a shulker box's contents counted when
     * {@code shulkers} is on): for the planner to take from. Empty when chests may not be used for {@code use}.
     */
    static java.util.Map<String, Integer> stored(dev.lazuli.core.settings.Settings s, ChestMemory mem, String dim, BlockPos at, Use use) {
        if (mem == null || !allowed(s, use)) return java.util.Map.of();
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        mem.totals(dim, at.getX(), at.getY(), at.getZ(), s.chestRange.get()).forEach((k, n) -> {
            if (ItemKeys.isInShulker(k)) {
                if (s.shulkers.get()) out.merge(ItemKeys.shulkerContent(k), n, Integer::sum);
            } else if (k.indexOf('#') < 0 && k.indexOf('@') < 0) {
                out.merge(k, n, Integer::sum);
            }
        });
        return out;
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
        tried.clear();
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
        double r = (double) settings.chestRange.get();
        for (var spot : mem.find(what, dimension.apply(mc), bp.getX(), bp.getY(), bp.getZ())) {
            if (spot.distSq(bp.getX(), bp.getY(), bp.getZ()) > r * r) break;
            BlockPos pos = new BlockPos(spot.x(), spot.y(), spot.z());
            if (!tried.add(pos)) continue;
            at = pos;
            containers.start(mc, false, List.of(new ItemCount(what, ItemKeys.isInShulker(what) ? 1 : Math.min(n, spot.count()))), at);
            if (containers.active()) return true;
            mem.fail(spot.dim(), spot.x(), spot.y(), spot.z(), COOLDOWN);
            at = null;
        }
        return false;
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
                    chests.apply(mc).fail(dimension.apply(mc), was.getX(), was.getY(), was.getZ(), COOLDOWN);
                }
                if (gotBox) {
                    unpack.start(p, ShulkerUnpack.boxWith(p, item), item, n);
                    mode = Mode.UNPACK;
                    return Result.RUNNING;
                }
                // this chest didn't hold enough: on to the next one that has some
                if (mode == Mode.CHEST && !ItemKeys.isInShulker(item) && containers.outcome() == ContainerProcess.Outcome.DONE
                    && got(p) < n && fromChest(mc, p, item, n - got(p))) return Result.RUNNING;
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
