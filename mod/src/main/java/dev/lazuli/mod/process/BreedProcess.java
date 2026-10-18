package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.Melee;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * #breed [animal...] [pairs]: feeds grown-up animals two at a time (cows wheat, pigs carrots...) so they breed, until
 * it has bred that many pairs or there are no more it can. The client isn't told which animals are on their 5-minute
 * cooldown, so it remembers the ones it fed.
 */
public final class BreedProcess implements Process {
    /** Vanilla's wait before a parent can breed again. */
    private static final int COOLDOWN = 6000;
    /** One animal may take this long to get to and feed, then it's left out. */
    private static final int TARGET_TICKS = 300;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    /** Animals fed (or given up on), and the tick it happened. */
    private final Map<UUID, Integer> fed = new HashMap<>();
    private boolean active;
    private List<String> kinds = List.of();
    private int wanted, pairs, targetSince;
    private BlockPos center, goal;
    private Animal target;
    /** The kind the pair being fed is (its first one has been fed), or null. */
    private String pairKind;

    public BreedProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    /** @param kinds entity ids (empty: any animal) @param pairs how many pairs (0: as many as it can) */
    public void start(Minecraft mc, List<String> kinds, int pairs) {
        stop(false);
        this.kinds = List.copyOf(kinds);
        this.wanted = pairs;
        this.pairs = 0;
        fed.clear();
        center = mc.player.blockPosition();
        target = null;
        pairKind = null;
        goal = null;
        trip.clear();
        active = true;
        chat.accept(Chat.info("Breeding {}", kinds.isEmpty() ? "animals" : String.join(", ", kinds.stream().map(BreedProcess::name).toList())));
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        return active ? "Breeding (" + pairs + (wanted > 0 ? "/" + wanted : "") + " pairs)" : null;
    }

    @Override
    public double progress() {
        return active && wanted > 0 ? (double) pairs / wanted : -1;
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        end(announce ? Chat.warn("Stopped breeding") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (wanted > 0 && pairs >= wanted && pairKind == null) {
            end(Chat.ok("Bred {} pairs", pairs));
            return;
        }
        if (target == null || !target.isAlive() || target.isRemoved() || fed.containsKey(target.getUUID())) {
            target = pick(mc, p);
            targetSince = p.tickCount;
            goal = null;
            if (target == null) {
                if (pairKind != null) chat.accept(Chat.warn("Fed one {} but couldn't get to a second", name(pairKind)));
                end(pairs == 0 ? Chat.err("Nothing to breed: needs two grown-ups of a kind and their food") : Chat.ok("Bred {} pairs", pairs));
                return;
            }
        }
        if (p.tickCount - targetSince > TARGET_TICKS) {
            fed.put(target.getUUID(), p.tickCount); // can't get to it: leave it out
            target = null;
            return;
        }
        double dist = p.distanceTo(target);
        if (dist > Melee.REACH + 0.5 || !p.hasLineOfSight(target)) {
            input.release();
            BlockPos at = target.blockPosition();
            if (!trip.running() || goal == null || goal.distSqr(at) > 9) {
                goal = at;
                trip.go(new GoalNear(at.getX(), at.getY(), at.getZ(), 2), p, true);
            }
            return;
        }
        if (trip.running()) trip.cancel();
        Animal a = target;
        if (!Hands.hold(p, ClickDriver.get(), a::isFood)) {
            if (!Hands.has(p, a::isFood)) target = null; // ran out of its food: another kind, or done
            return;
        }
        if (!a.canFallInLove()) {
            fed.put(a.getUUID(), p.tickCount); // in love already, or a baby/cooling down: not this one
            target = null;
            return;
        }
        if (Melee.face(mc, p, a, input, settings, 2.0) && dist <= Melee.REACH) {
            mc.gameMode.interact(p, a, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            fed.put(a.getUUID(), p.tickCount);
            input.release();
            String kind = id(a);
            if (pairKind == null) {
                pairKind = kind;
            } else {
                pairKind = null;
                pairs++;
            }
            target = null;
        }
    }

    /**
     * The next animal to feed: the second of the pair under way, else the nearest of a kind with two grown-ups ready
     * and food for them in the inventory.
     */
    private Animal pick(Minecraft mc, LocalPlayer p) {
        int r = settings.breedRange.get();
        Map<String, List<Animal>> ready = new HashMap<>();
        for (Animal a : mc.level.getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(r), Animal::isAlive)) {
            if (a.isBaby() || a.blockPosition().distSqr(center) > (double) r * r) continue;
            if (a instanceof TamableAnimal t && !t.isTame() || a instanceof AbstractHorse h && !h.isTamed()) continue;
            String kind = id(a);
            if (!kinds.isEmpty() && !kinds.contains(kind)) continue;
            Integer when = fed.get(a.getUUID());
            if (when != null && p.tickCount - when < COOLDOWN) continue;
            if (!Hands.has(p, a::isFood)) continue;
            ready.computeIfAbsent(kind, k -> new ArrayList<>()).add(a);
        }
        Comparator<Animal> near = Comparator.comparingDouble(p::distanceToSqr);
        if (pairKind != null) {
            List<Animal> same = ready.get(pairKind);
            return same == null ? null : same.stream().min(near).orElse(null);
        }
        return ready.values().stream().filter(l -> l.size() >= 2).flatMap(List::stream).min(near).orElse(null);
    }

    private void end(String msg) {
        active = false;
        trip.cancel();
        input.release();
        target = null;
        pairKind = null;
        if (msg != null) chat.accept(msg);
    }

    private static String id(Animal a) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).toString();
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
