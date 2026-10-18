package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.Melee;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Kills mobs for what they drop (#getitem's hunting step): the nearest grown-up mob of the kinds asked for, walked up to
 * and hit (see {@link Melee}), then its drops picked up, until the inventory holds enough. None around: it wanders
 * looking for some, for up to huntMinutes.
 */
public final class HuntProcess implements Process {
    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private static final double RANGE = 64, LOOT_RANGE = 8;
    private static final int WANDER = 48;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private MakeRoom room;
    private final InputDriver input = new InputDriver();
    private boolean active;
    private Outcome outcome = Outcome.NONE;
    private String item;
    private int target, startTick, kills, wanders;
    private List<String> mobs = List.of();
    private LivingEntity prey;
    private BlockPos goal;
    /** Drops it couldn't get to in time: left alone. */
    private final java.util.Set<Integer> skipDrops = new java.util.HashSet<>();
    /** Mobs it couldn't get to within {@link #PREY_TICKS}: left alone this run. */
    private final java.util.Set<Integer> skipPrey = new java.util.HashSet<>();
    private static final int PREY_TICKS = 20 * 30;
    private int preyId = -1, preyTicks;
    private int dropId = -1, dropTicks;
    private static final int DROP_TICKS = 200;

    public HuntProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
        this.room = new MakeRoom(settings, null, chat);
    }

    /** Lets a full inventory be put away in a remembered chest, not just thrown out. */
    public void useContainers(ContainerProcess containers) {
        room = new MakeRoom(settings, containers, chat);
    }

    private static final java.util.Set<String> PASSIVE = java.util.Set.of("minecraft:cow", "minecraft:pig", "minecraft:sheep",
        "minecraft:chicken", "minecraft:rabbit", "minecraft:squid", "minecraft:glow_squid", "minecraft:cod", "minecraft:salmon");

    private static boolean hasWeapon(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            String id = BuiltInRegistries.ITEM.getKey(p.getInventory().getItem(i).getItem()).getPath();
            if (id.endsWith("_sword") || id.endsWith("_axe")) return true;
        }
        return false;
    }

    /** Kills {@code mobs} (entity ids, best first) until the inventory holds {@code target} of {@code item}. */
    public void start(Minecraft mc, String item, int target, List<String> mobs) {
        stop(false);
        this.item = item;
        this.target = target;
        this.mobs = List.copyOf(mobs);
        startTick = mc.player.tickCount;
        kills = wanders = 0;
        skipDrops.clear();
        skipPrey.clear();
        preyId = -1;
        dropId = -1;
        prey = null;
        goal = null;
        outcome = Outcome.NONE;
        room.reset();
        trip.clear();
        if (!mobs.isEmpty() && !mc.player.getAbilities().instabuild && !hasWeapon(mc.player) && !PASSIVE.containsAll(mobs)) {
            outcome = Outcome.FAILED;
            chat.accept(Chat.err("No sword or axe to hunt {} with", name(mobs.get(0))));
            return;
        }
        active = true;
    }

    public Outcome outcome() {
        return outcome;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        return active ? "Hunting " + mobs.stream().map(HuntProcess::name).limit(2).toList() + " for " + name(item)
            + " (" + have(Minecraft.getInstance().player) + "/" + target + ")" : null;
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (have(p) >= target) {
            end(Outcome.DONE, null);
            return;
        }
        if (p.tickCount - startTick > settings.huntMinutes.get() * 60 * 20) {
            end(Outcome.FAILED, Chat.err("Couldn't find enough {} to get {} in {} min", name(mobs.get(0)), name(item), settings.huntMinutes.get()));
            return;
        }
        // nowhere to put what it kills drops: make room (throw junk out, or put things away), else stop saying so
        if (MakeRoom.full(p, item)) {
            switch (room.tick(mc, p, id -> id.equals(item))) {
                case WORKING -> {
                    trip.cancel();
                    input.release();
                    return;
                }
                case STUCK -> {
                    end(Outcome.FAILED, room.reason() + " (" + have(p) + " of " + target + " " + name(item) + ")");
                    return;
                }
                default -> { }
            }
        }
        // its drops first: walk over them
        ItemEntity drop = mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(LOOT_RANGE),
                e -> e.isAlive() && !skipDrops.contains(e.getId()) && BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString().equals(item))
            .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
        if (drop != null && (prey == null || !prey.isAlive())) {
            if (drop.getId() != dropId) {
                dropId = drop.getId();
                dropTicks = 0;
            }
            if (++dropTicks > DROP_TICKS) skipDrops.add(dropId);
            input.release();
            walk(p, drop.blockPosition(), 0);
            return;
        }
        if (prey == null || !prey.isAlive() || prey.isRemoved()) {
            if (prey != null && !prey.isAlive()) kills++;
            prey = find(mc, p);
        }
        if (prey == null) {
            // none around: look elsewhere
            input.release();
            if (!trip.running()) {
                if (wanders++ == 0) chat.accept(Chat.info("No {} around; looking for some", name(mobs.get(0))));
                double a = p.getRandom().nextDouble() * Math.PI * 2;
                trip.go(new GoalXZ(p.getBlockX() + (int) (Math.cos(a) * WANDER), p.getBlockZ() + (int) (Math.sin(a) * WANDER)), p, true);
            }
            return;
        }
        if (prey.getId() != preyId) {
            preyId = prey.getId();
            preyTicks = 0;
        }
        if (p.distanceTo(prey) > Melee.REACH + 1.5 || !p.hasLineOfSight(prey)) {
            if (++preyTicks > PREY_TICKS) {
                skipPrey.add(preyId); // can't get to it (across water, behind a fence): another one
                prey = null;
                trip.cancel();
                input.release();
                return;
            }
            input.release();
            walk(p, prey.blockPosition(), 1);
            return;
        }
        preyTicks = 0;
        // in reach: stand and fight (no trip pulling the other way)
        if (trip.running()) trip.cancel();
        goal = null;
        Melee.tick(mc, p, prey, input, settings);
    }

    /** A trip to near {@code to}, made again when it has moved off a few blocks. */
    private void walk(LocalPlayer p, BlockPos to, int radius) {
        if (trip.running() && goal != null && goal.distSqr(to) <= 9) return;
        goal = to;
        trip.go(radius == 0 ? new GoalBlock(to.getX(), to.getY(), to.getZ()) : new GoalNear(to.getX(), to.getY(), to.getZ(), radius), p, true);
    }

    /** The nearest grown-up mob of the kinds asked for (the best kind among those about as near). */
    private LivingEntity find(Minecraft mc, LocalPlayer p) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(RANGE), LivingEntity::isAlive)) {
            int rank = mobs.indexOf(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            if (rank < 0 || e.isBaby() || skipPrey.contains(e.getId())) continue;
            double score = p.distanceTo(e) + rank * 8;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private int have(LocalPlayer p) {
        if (p == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; i++) {
            var s = p.getInventory().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item)) n += s.getCount();
        }
        return n;
    }

    private void end(Outcome how, String msg) {
        active = false;
        outcome = how;
        trip.cancel();
        input.release();
        prey = null;
        if (msg != null) chat.accept(msg);
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
