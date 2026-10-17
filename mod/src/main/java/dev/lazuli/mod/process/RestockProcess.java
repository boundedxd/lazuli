package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.ItemKeys;
import dev.lazuli.core.inventory.Restock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.storage.ChestMemory;
import dev.lazuli.mod.control.Tools;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Keeps the {@code restockItems} topped up while a job runs: something running low and a known chest (or a shulker
 * box) has it, the job waits while it walks over and takes some, then carries on (like {@link Guard}, a plain trip is
 * picked up again after).
 */
public final class RestockProcess implements Process {
    private static final int CHECK_EVERY = 40;
    /** After a try that got nothing, that line is left alone this long; after any, a short breather. */
    private static final int GAVE_UP_TICKS = 20 * 60 * 5, BREATHER = 20 * 20;

    private static volatile boolean active;

    private final Settings settings;
    private final Consumer<String> chat;
    private final PathingSession session;
    private final ContainerProcess containers;
    private final ChestTake take;
    private final Function<Minecraft, ChestMemory> chests;
    private final Function<Minecraft, String> dimension;
    private final BooleanSupplier jobRunning;
    /** True while restocking shouldn't start (following someone with restockFollow off). */
    private BooleanSupplier skipWhen = () -> false;
    private final Trip trip;
    private final Map<String, Integer> skipUntil = new HashMap<>();
    private int nextCheck;
    private String line;
    private ItemCount taking;
    private dev.lazuli.core.goal.Goal resume;

    /** @param jobRunning a process other than this (and the guard) is running */
    public void skipWhen(BooleanSupplier when) {
        skipWhen = when;
    }

    public RestockProcess(PathingSession session, Settings settings, ClickDriver clicks, Function<Minecraft, ChestMemory> chests,
                          Function<Minecraft, String> dimension, BooleanSupplier jobRunning, Consumer<String> chat) {
        this.session = session;
        this.settings = settings;
        this.chat = chat;
        this.chests = chests;
        this.dimension = dimension;
        this.jobRunning = jobRunning;
        // its own failures said (walked there and nothing happened, silently, before)
        this.containers = new ContainerProcess(session, settings, clicks, s -> {
            if (s.contains(Chat.RED)) chat.accept(s);
        });
        this.take = new ChestTake(settings, containers, chests, dimension);
        this.trip = new Trip(session);
    }

    /** Restocking: other processes wait. */
    public static boolean paused() {
        return active;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        return active && taking != null ? "Restocking " + taking.count() + " " + name(taking.item()) : null;
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        take.stop();
        resume = null;
        active = false;
    }

    private LocalPlayer lastPlayer;

    @Override
    public void tick(Minecraft mc) {
        containers.tick(mc);
        LocalPlayer p = mc.player;
        if (p != lastPlayer) {
            // a new player (respawned, rejoined): its tick count starts again at 0, so the waits do too
            lastPlayer = p;
            nextCheck = 0;
            skipUntil.clear();
        }
        if (p == null || mc.level == null) {
            if (active) stop(false);
            return;
        }
        if (active) {
            switch (take.tick(mc, p)) {
                case RUNNING -> {
                    return;
                }
                case CANCELLED -> {}
                case DONE -> {
                    int got = take.got(p);
                    if (got == 0) skipUntil.put(line, p.tickCount + GAVE_UP_TICKS);
                    else chat.accept(Chat.ok("Restocked {} {}", got, name(taking.item())));
                }
            }
            finish(p);
            return;
        }
        if (!settings.restock.get() || p.tickCount < nextCheck) return;
        nextCheck = p.tickCount + CHECK_EVERY;
        PathingSession.Snapshot snap = session.snapshot();
        boolean pathing = snap.state() == PathingSession.State.PATHING || session.planning();
        boolean job = jobRunning.getAsBoolean();
        if (!pathing && !job || skipWhen.getAsBoolean()) return;
        if (Guard.paused() || p.isFallFlying() || mc.screen != null || p.containerMenu != p.inventoryMenu) return;
        if (ClickDriver.get().busy() || ClickDriver.holding()) return;
        List<Restock.Want> wants = Restock.parse(settings.restockSpec(),
            Arrays.stream(settings.throwawayBlocks.get().split(",")).filter(s -> !s.isBlank()).map(ItemKeys::id).toList());
        java.util.Set<String> skip = new java.util.HashSet<>();
        skipUntil.forEach((k, until) -> {
            if (p.tickCount < until) skip.add(k);
        });
        ChestMemory mem = chests.apply(mc);
        String dim = dimension.apply(mc);
        var bp = p.blockPosition();
        int range = settings.chestRange.get();
        ItemCount next = Restock.next(wants, ids -> held(p, ids), id -> !Tools.collecting(id)
            && (settings.chestMemory.get() && (mem.nearest(id, dim, bp.getX(), bp.getY(), bp.getZ(), range) != null
                || settings.shulkers.get() && mem.nearest(ItemKeys.inShulker(id), dim, bp.getX(), bp.getY(), bp.getZ(), range) != null)
                || settings.shulkers.get() && ShulkerUnpack.boxWith(p, id) >= 0), skip);
        if (next == null) return;
        line = Restock.nameOf(wants, next.item());
        resume = !job && pathing ? snap.goal() : null;
        String id = next.item();
        if (!take.start(mc, p, id, next.count(), pl -> held(pl, List.of(id)))) {
            skipUntil.put(line, p.tickCount + GAVE_UP_TICKS);
            resume = null;
            return;
        }
        taking = next;
        active = true;
        Guard.tookOver(session.snapshot().trip());
        chat.accept(Chat.info("Low on {}: taking {} {} from storage", line, next.count(), name(id)));
    }

    private void finish(LocalPlayer p) {
        active = false;
        taking = null;
        nextCheck = p.tickCount + BREATHER;
        Guard.tookOver(session.snapshot().trip());
        dev.lazuli.core.goal.Goal g = resume;
        resume = null;
        if (g != null) trip.go(g, p, true); // back on the way to where it was going
        trip.clear();
    }

    /** How many of these items the player holds (tools nearly worn out don't count). */
    private static int held(LocalPlayer p, List<String> ids) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || !ids.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) continue;
            if (s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() < s.getMaxDamage() / 10) continue;
            n += s.getCount();
        }
        return n;
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
