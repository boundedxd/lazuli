package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #follow: keeps within followRadius of the nearest matching entity: any player, any mob, named players, or entities
 * of the named types. Sticks with one target while it stays around; picks the next nearest when it's gone.
 */
public final class FollowProcess implements Process {
    public enum Mode { PLAYERS, ENTITIES, PLAYER, ENTITY }

    /** Ticks between re-plans while the target keeps moving. */
    private static final int REPLAN_TICKS = 10;
    /** How far (blocks) the target must move from where the trip was aimed before it's re-aimed. */
    private static final double MOVED = 2.5;
    private static final double SEARCH = 128;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private Mode mode;
    private final Set<String> names = new HashSet<>();
    private final Set<EntityType<?>> types = new HashSet<>();
    private String what = "";
    private Entity target;
    private BlockPos aimedAt;
    private boolean waiting;
    private boolean active;
    private int retryAt;

    public FollowProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    @Override
    public boolean active() {
        return active;
    }

    /** @param args player names (PLAYER) or entity type ids (ENTITY); ignored otherwise. Returns false if invalid. */
    public boolean start(Mode mode, List<String> args) {
        names.clear();
        types.clear();
        if (mode == Mode.ENTITY) {
            for (String a : args) {
                var t = EntityType.byString(a.contains(":") ? a : "minecraft:" + a);
                if (t.isEmpty()) {
                    chat.accept(Chat.err("Unknown entity type: {}", a));
                    return false;
                }
                types.add(t.get());
            }
        }
        for (String a : args) names.add(a.toLowerCase(Locale.ROOT));
        this.mode = mode;
        what = switch (mode) {
            case PLAYERS -> "any player";
            case ENTITIES -> "any mob";
            case PLAYER, ENTITY -> String.join(", ", args);
        };
        target = null;
        aimedAt = null;
        waiting = false;
        active = true;
        retryAt = 0;
        trip.clear();
        chat.accept(Chat.ok("Following {}", what));
        return true;
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        active = false;
        trip.cancel();
        if (announce) chat.accept(Chat.warn("Stopped following"));
    }

    private boolean matches(Entity e, LocalPlayer self) {
        if (e == self || !e.isAlive() || e.isSpectator()) return false;
        return switch (mode) {
            case PLAYERS -> e instanceof Player;
            case ENTITIES -> e instanceof LivingEntity;
            case PLAYER -> e instanceof Player p && names.contains(p.getName().getString().toLowerCase(Locale.ROOT));
            case ENTITY -> types.contains(e.getType());
        };
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer player = mc.player;
        if (target == null || target.isRemoved() || target.level() != player.level() || !matches(target, player)
            || target.distanceTo(player) > SEARCH) {
            target = nearest(mc, player);
            aimedAt = null;
        }
        if (target == null) {
            trip.cancel();
            if (!waiting) chat.accept(Chat.warn("Waiting for {} to show up", what));
            waiting = true;
            return;
        }
        if (waiting) chat.accept(Chat.ok("Following {}", target.getName().getString()));
        waiting = false;
        int radius = settings.followRadius.get();
        if (player.distanceTo(target) <= radius) {
            trip.cancel(); // close enough: wait here
            aimedAt = null;
            return;
        }
        BlockPos tp = target.blockPosition();
        if (trip.ended() == PathingSession.State.FAILED) {
            trip.clear();
            retryAt = player.tickCount + 40; // can't get there right now (flying, across water...): try again soon
        }
        if (player.tickCount < retryAt) return;
        boolean moved = aimedAt == null || aimedAt.distSqr(tp) > MOVED * MOVED;
        if (!trip.running() || moved && trip.age(player) >= REPLAN_TICKS) {
            aimedAt = tp;
            trip.go(new GoalNear(tp.getX(), tp.getY(), tp.getZ(), Math.max(1, radius - 1)), player);
        }
    }

    private Entity nearest(Minecraft mc, LocalPlayer player) {
        Entity best = null;
        double bestD = SEARCH * SEARCH;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!matches(e, player)) continue;
            double d = e.distanceToSqr(player);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }
}
