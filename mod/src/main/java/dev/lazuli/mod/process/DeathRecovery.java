package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.InputDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * deathRecovery: after a respawn, back to where the player died (same dimension only) to pick the dropped items up
 * before they despawn (5 minutes after the death), walking over each one in turn. Not when the death was in lava or
 * the void (nothing left), or when it's too far to make it in time.
 */
public final class DeathRecovery implements Process {
    /** Items despawn this many ticks after dropping. */
    private static final long DESPAWN = 6000;
    /** How far round the death spot to look for the items; ticks to try for one before leaving it. */
    private static final double LOOT = 10;
    private static final int ITEM_TICKS = 200, WAIT_RESPAWN = 20;
    /** Blocks walked per tick, about: further than this in the time left isn't worth the trip. */
    private static final double SPEED = 0.2;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Function<Minecraft, String> dimension;
    private final Trip trip;
    private final InputDriver input = new InputDriver();

    private BlockPos died;
    private String diedIn;
    private long diedAt;
    /** Waiting for the respawn; then going back. */
    private boolean pending, active;
    private int itemId = -1, itemTicks, picked, aliveTicks;
    private final Set<Integer> skip = new HashSet<>();

    public DeathRecovery(PathingSession session, Settings settings, Function<Minecraft, String> dimension, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.dimension = dimension;
        this.trip = new Trip(session);
    }

    /** The player just died here. */
    public void died(Minecraft mc, BlockPos at) {
        stop(false);
        if (!settings.deathRecovery.get() || mc.level == null) return;
        FluidState f = mc.level.getFluidState(at);
        if (at.getY() < mc.level.getMinY()) {
            chat.accept(Chat.warn("Died in the void: nothing to get back"));
            return;
        }
        if (f.is(net.minecraft.tags.FluidTags.LAVA)) {
            chat.accept(Chat.warn("Died in lava: the items burnt"));
            return;
        }
        died = at;
        diedIn = dimension.apply(mc);
        diedAt = mc.level.getGameTime();
        pending = true;
        aliveTicks = 0;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        return active ? "Getting my stuff back (" + picked + " picked up)" : null;
    }

    @Override
    public void stop(boolean announce) {
        pending = false;
        if (!active) return;
        end(announce ? Chat.warn("Stopped") : null);
    }

    /** Called every tick (even when not active): starts once the player is back. */
    public void watch(Minecraft mc) {
        if (!pending) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || p.isDeadOrDying()) {
            aliveTicks = 0;
            return;
        }
        if (++aliveTicks < WAIT_RESPAWN) return;
        pending = false;
        if (!diedIn.equals(dimension.apply(mc))) {
            chat.accept(Chat.warn("Died in {}: not going back for the items from here", diedIn));
            return;
        }
        double dist = Math.sqrt(p.blockPosition().distSqr(died));
        if (dist / SPEED > left(mc)) {
            chat.accept(Chat.warn("Died {} blocks away: the items will be gone before I get there", (int) dist));
            return;
        }
        active = true;
        picked = 0;
        skip.clear();
        itemId = -1;
        trip.clear();
        chat.accept(Chat.info("Going back for my items at {} ({}s left)", died.toShortString(), left(mc) / 20));
    }

    /** Ticks until the dropped items despawn. */
    private long left(Minecraft mc) {
        return DESPAWN - (mc.level.getGameTime() - diedAt);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (p.isDeadOrDying()) {
            end(null);
            return;
        }
        if (left(mc) <= 0) {
            end(picked > 0 ? Chat.ok("Got {} stacks back; the rest despawned", picked) : Chat.warn("The items despawned"));
            return;
        }
        boolean near = p.blockPosition().distSqr(died) <= LOOT * LOOT;
        ItemEntity item = near || mc.level.isLoaded(died) ? nearest(mc, p) : null;
        if (item != null) {
            if (item.getId() != itemId) {
                if (itemId >= 0) picked++;
                itemId = item.getId();
                itemTicks = 0;
                trip.cancel();
            }
            if (++itemTicks > ITEM_TICKS) {
                skip.add(itemId); // can't get to it
                trip.cancel();
                return;
            }
            if (!trip.running()) {
                BlockPos b = item.blockPosition();
                trip.go(new GoalBlock(b.getX(), b.getY(), b.getZ()), p, true);
            }
            return;
        }
        if (near && mc.level.isLoaded(died)) {
            if (itemId >= 0) picked++;
            end(picked > 0 ? Chat.ok("Got my items back ({} stacks)", picked) : Chat.warn("No items left where I died"));
            return;
        }
        if (trip.ended() == PathingSession.State.FAILED) {
            end(Chat.err("Can't get back to {}", died.toShortString()));
            return;
        }
        if (!trip.running()) trip.go(new GoalNear(died.getX(), died.getY(), died.getZ(), 2), p, true);
    }

    /** The nearest dropped item round the death spot not given up on, or null. */
    private ItemEntity nearest(Minecraft mc, LocalPlayer p) {
        return mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(died).inflate(LOOT),
                e -> e.isAlive() && !skip.contains(e.getId()))
            .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
    }

    private void end(String msg) {
        active = false;
        trip.cancel();
        input.release();
        if (msg != null) chat.accept(msg);
    }
}
