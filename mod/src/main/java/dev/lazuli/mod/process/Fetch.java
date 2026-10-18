package dev.lazuli.mod.process;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.storage.ChestMemory;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * What a job does before it gives up on a missing item: take it out of a remembered chest (or a shulker box) through
 * {@link ChestTake}, then start the job again. The job calls {@link #start} where it would say "missing", runs
 * {@link #tick} while it's fetching, and starts over when that says DONE (it fetches at most {@link #MAX} times per job,
 * and not an item a chest turned out to have none of).
 */
final class Fetch {
    enum State { RUNNING, DONE, CANCELLED }

    static final int MAX = 12;

    private final Settings settings;
    private final ChestTake take;
    private final Set<String> skip = new HashSet<>();
    private String item;
    private int attempts;

    Fetch(Settings settings, ContainerProcess containers, Function<Minecraft, ChestMemory> chests, Function<Minecraft, String> dimension) {
        this.settings = settings;
        this.take = new ChestTake(settings, containers, chests, dimension);
    }

    /** A new job: nothing tried yet. */
    void reset() {
        skip.clear();
        attempts = 0;
    }

    /**
     * Starts taking one of the {@code missing} items (id to how many) from a chest; false if chests may not be used,
     * none of them is in a remembered chest, or it has fetched enough.
     */
    boolean start(Minecraft mc, LocalPlayer p, Map<String, Integer> missing) {
        if (!ChestTake.allowed(settings, ChestTake.Use.INGREDIENT) || attempts >= MAX || missing.isEmpty()) return false;
        for (var e : missing.entrySet()) {
            String id = Inventories.id(e.getKey());
            if (e.getValue() <= 0 || skip.contains(id)) continue;
            if (take.start(mc, p, id, e.getValue(), pl -> Inventories.plainCounts(pl).getOrDefault(id, 0))) {
                item = id;
                attempts++;
                return true;
            }
        }
        return false;
    }

    boolean active() {
        return take.active();
    }

    State tick(Minecraft mc, LocalPlayer p) {
        return switch (take.tick(mc, p)) {
            case RUNNING -> State.RUNNING;
            case CANCELLED -> State.CANCELLED;
            case DONE -> {
                if (take.got(p) <= 0) skip.add(item); // nothing came out: not that again
                yield State.DONE;
            }
        };
    }

    void stop() {
        take.stop();
    }
}
