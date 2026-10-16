package dev.lazuli.mod.process;

import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.storage.ChestMemory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Takes an item out of the nearest remembered chest (chestMemory, within chestRange) through {@link ContainerProcess};
 * a chest it couldn't get to or open is forgotten. Shared by #getitem and #brew.
 */
final class ChestTake {
    enum Result { RUNNING, DONE, CANCELLED }

    private final dev.lazuli.core.settings.Settings settings;
    private final ContainerProcess containers;
    private final Function<Minecraft, ChestMemory> chests;
    private final Function<Minecraft, String> dimension;
    private BlockPos at;
    private ToIntFunction<LocalPlayer> count;
    private int before;

    ChestTake(dev.lazuli.core.settings.Settings settings, ContainerProcess containers, Function<Minecraft, ChestMemory> chests,
              Function<Minecraft, String> dimension) {
        this.settings = settings;
        this.containers = containers;
        this.chests = chests;
        this.dimension = dimension;
    }

    boolean active() {
        return at != null;
    }

    /**
     * Starts taking {@code n} of {@code item} (an id, or "id@potion"); false if no remembered chest holds some.
     * @param count how many the player holds now (to tell whether it got any)
     */
    boolean start(Minecraft mc, LocalPlayer p, String item, int n, ToIntFunction<LocalPlayer> count) {
        if (n <= 0 || !settings.chestMemory.get()) return false;
        var mem = chests.apply(mc);
        var bp = p.blockPosition();
        var spot = mem.nearest(item, dimension.apply(mc), bp.getX(), bp.getY(), bp.getZ(), settings.chestRange.get());
        if (spot == null) return false;
        at = new BlockPos(spot.x(), spot.y(), spot.z());
        this.count = count;
        before = count.applyAsInt(p);
        containers.start(mc, false, List.of(new ItemCount(item, Math.min(n, spot.count()))), at);
        if (!containers.active()) {
            mem.forget(spot.dim(), spot.x(), spot.y(), spot.z());
            at = null;
            return false;
        }
        return true;
    }

    /** RUNNING while walking/taking; once it's over, how it went ({@link #got} says how many). */
    Result tick(Minecraft mc, LocalPlayer p) {
        if (at == null) return Result.DONE;
        if (containers.active()) return Result.RUNNING;
        BlockPos was = at;
        at = null;
        if (containers.outcome() == ContainerProcess.Outcome.CANCELLED) return Result.CANCELLED;
        // couldn't get to it or open it: don't count on that chest again
        if (count.applyAsInt(p) <= before && containers.outcome() == ContainerProcess.Outcome.FAILED) {
            chests.apply(mc).forget(dimension.apply(mc), was.getX(), was.getY(), was.getZ());
        }
        return Result.DONE;
    }

    /** How many more it holds than when the take started. */
    int got(LocalPlayer p) {
        return count == null ? 0 : Math.max(0, count.applyAsInt(p) - before);
    }

    void stop() {
        if (at != null) containers.stop(false);
        at = null;
    }
}
