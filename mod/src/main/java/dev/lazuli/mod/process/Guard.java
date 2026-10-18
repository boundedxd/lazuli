package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Combat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Keeps the player alive while Lazuli works. It pauses the job (like eating does), handles the danger, then lets the
 * job carry on:
 * <ul>
 *   <li>dodgeCreepers: a creeper starts to hiss close by, so it runs out of the blast;</li>
 *   <li>lowHpRetreat: health drops to retreatHealth, so it gets away from monsters, eats and waits until healed;</li>
 *   <li>defend: a monster comes within reach, so it turns and hits it (full swings, best weapon) until it's gone,
 *   with the shield up between swings (useShield);</li>
 *   <li>useBow: a skeleton or a creeper is coming, so it shoots it from where it stands (stepping back from one too close);</li>
 *   <li>fightOrFlee: too many monsters for its health, armor and weapon (see {@link dev.lazuli.core.control.Threat}), so it runs.</li>
 * </ul>
 */
public final class Guard implements Process {
    private enum Danger { NONE, CREEPER, LOW_HP, FIGHT, OUTMATCHED }

    /** How close a hissing creeper has to be to run, and how far away it runs. */
    private static final double CREEPER_NEAR = 6, CREEPER_SAFE = 9;
    /** Monsters within this are run from while healing; it heads this far off. */
    private static final double MOB_NEAR = 12, FLEE = 18;
    /** Healing gives up after this long (no food, no regeneration) and lets the job go on. */
    private static final int HEAL_TICKS = 20 * 120;
    private static final int FLEE_TRIES = 3;
    /** A monster this close starts a fight; it's chased this far; a fight gives up after FIGHT_TICKS. */
    private static final double FIGHT_NEAR = 3.0, FIGHT_CHASE = 6;
    private static final int FIGHT_TICKS = 20 * 30;

    private static volatile boolean active, healing;
    /** The last trip number when it took over: process trips up to it were cut short, not failed. */
    private static volatile long tookOverAt = -1;

    private final PathingSession session;
    private final Settings settings;
    private final Consumer<String> chat;
    private final BooleanSupplier jobRunning;
    private final Trip trip;
    private Danger danger = Danger.NONE;
    private final dev.lazuli.mod.control.InputDriver input = new dev.lazuli.mod.control.InputDriver();
    /** A plain trip (#goto, no job) cut short: started again after. */
    private dev.lazuli.core.goal.Goal resume;
    private int since, flees;

    /** @param jobRunning a process other than this is running */
    public Guard(PathingSession session, Settings settings, Consumer<String> chat, BooleanSupplier jobRunning) {
        this.session = session;
        this.settings = settings;
        this.chat = chat;
        this.jobRunning = jobRunning;
        this.trip = new Trip(session);
    }

    /** Handling a danger: other processes wait. */
    public static boolean paused() {
        return active;
    }

    /** Healing: eat whenever not full (regeneration needs a nearly full food bar). */
    public static boolean healing() {
        return healing;
    }

    /** Something else (restocking) took over from trip {@code trip}: counts as the guard's doing for the job. */
    public static void tookOver(long trip) {
        tookOverAt = Math.max(tookOverAt, trip);
    }

    /** Whether a trip started as {@code trip} was cut short by the guard (so it didn't fail). */
    public static boolean interrupted(long trip) {
        return trip >= 0 && tookOverAt >= trip;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        if (!active) return null;
        LocalPlayer p = Minecraft.getInstance().player;
        return switch (danger) {
            case CREEPER -> "Running from a creeper";
            case FIGHT -> "Fighting";
            case OUTMATCHED -> "Outnumbered: running";
            case LOW_HP -> "Healing" + (p == null ? "" : " " + (int) p.getHealth() + "/" + (int) p.getMaxHealth());
            case NONE -> null;
        };
    }

    @Override
    public double progress() {
        LocalPlayer p = Minecraft.getInstance().player;
        return active && danger == Danger.LOW_HP && p != null ? p.getHealth() / p.getMaxHealth() : -1;
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        trip.cancel();
        resume = null;
        end();
    }

    private void end() {
        combat.reset(Minecraft.getInstance());
        input.release();
        active = healing = false;
        danger = Danger.NONE;
        trip.clear();
    }

    @Override
    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            if (active) end();
            return;
        }
        if (!active) {
            PathingSession.Snapshot snap = session.snapshot();
            boolean pathing = snap.state() == PathingSession.State.PATHING || session.planning();
            boolean job = jobRunning.getAsBoolean();
            if (!pathing && !job) return;
            if (p.isFallFlying()) return; // nothing to fight or flee up there; stopping would drop out of the sky
            Danger d = spot(mc, p);
            if (d == Danger.NONE) return;
            resume = !job && pathing ? snap.goal() : null;
            take(d, p);
        }
        since++;
        switch (danger) {
            case CREEPER -> {
                List<LivingEntity> c = creepers(mc, p, CREEPER_SAFE);
                if (c.isEmpty() || since > 20 * 15) {
                    if (since > 20 * 15) trip.cancel();
                    release();
                    return;
                }
                flee(p, c, CREEPER_SAFE + 3);
            }
            case LOW_HP -> {
                if (p.getHealth() >= p.getMaxHealth() - 1 || since > HEAL_TICKS) {
                    if (since > HEAL_TICKS) {
                        chat.accept(Chat.warn("Not healing (no food?); carrying on at {} HP", (int) p.getHealth()));
                        healGaveUpAt = p.tickCount;
                        healGaveUpHp = p.getHealth();
                    }
                    trip.cancel();
                    release();
                    return;
                }
                List<LivingEntity> mobs = enemies(mc, p, MOB_NEAR);
                if (!mobs.isEmpty()) flee(p, mobs, FLEE);
            }
            case FIGHT -> {
                LivingEntity t = nearestFoe(mc, p, FIGHT_CHASE);
                if (t == null && Combat.canShoot(p, settings)) t = Combat.rangedFoe(mc, p, BOW_RANGE);
                if (t == null || since > FIGHT_TICKS || p.getHealth() <= settings.retreatHealth.get() && settings.lowHpRetreat.get()) {
                    release();
                    return;
                }
                if (settings.fightOrFlee.get() && outmatched(mc, p)) {
                    combat.reset(mc);
                    take(Danger.OUTMATCHED, p);
                    return;
                }
                fight(mc, p, t);
            }
            case OUTMATCHED -> {
                List<LivingEntity> mobs = enemies(mc, p, MOB_NEAR);
                if (mobs.isEmpty() || since > 20 * 20) {
                    trip.cancel();
                    release();
                    return;
                }
                flee(p, mobs, FLEE);
            }
            case NONE -> release();
        }
    }

    private void fight(Minecraft mc, LocalPlayer p, LivingEntity t) {
        combat.tick(mc, p, t, input, settings);
    }

    /** Shooting range for the bow. */
    private static final double BOW_RANGE = 16;
    private final Combat combat = new Combat();

    /** Too many monsters around for the player's health, armor and weapon. */
    private boolean outmatched(Minecraft mc, LocalPlayer p) {
        List<String> ids = new java.util.ArrayList<>();
        for (LivingEntity e : enemies(mc, p, 10)) {
            if (e instanceof net.minecraft.world.entity.monster.EnderMan
                || e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin) continue;
            ids.add(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        }
        return ids.size() > 1 && dev.lazuli.core.control.Threat.outmatched(ids, p.getHealth(), p.getArmorValue(),
            dev.lazuli.mod.control.Melee.bestDamage(p));
    }

    /** When a heal last timed out (tick, -1: never) and at what HP; how long before trying again. */
    private int healGaveUpAt = -1;
    private float healGaveUpHp;
    private static final int HEAL_COOLDOWN = 20 * 60 * 5;

    private Danger spot(Minecraft mc, LocalPlayer p) {
        if (settings.dodgeCreepers.get()) {
            for (LivingEntity e : creepers(mc, p, CREEPER_NEAR)) {
                if (((Creeper) e).getSwellDir() > 0 || ((Creeper) e).getSwelling(0f) > 0) return Danger.CREEPER;
            }
        }
        // after a heal that didn't work: not again for a while, unless it gets worse
        boolean gaveUp = healGaveUpAt >= 0 && p.tickCount - healGaveUpAt < HEAL_COOLDOWN && p.tickCount >= healGaveUpAt
            && p.getHealth() > healGaveUpHp - 3;
        if (settings.lowHpRetreat.get() && p.getHealth() <= settings.retreatHealth.get() && !gaveUp) return Danger.LOW_HP;
        if (settings.fightOrFlee.get() && nearestFoe(mc, p, 8) != null && outmatched(mc, p)) return Danger.OUTMATCHED;
        if (settings.defend.get() && nearestFoe(mc, p, FIGHT_NEAR) != null) return Danger.FIGHT;
        if (Combat.canShoot(p, settings) && Combat.rangedFoe(mc, p, BOW_RANGE) != null) return Danger.FIGHT;
        return Danger.NONE;
    }

    private void take(Danger d, LocalPlayer p) {
        danger = d;
        active = true;
        healing = d == Danger.LOW_HP;
        since = flees = 0;
        tookOverAt = session.snapshot().trip();
        trip.clear();
        if (p.containerMenu != p.inventoryMenu) {
            // a chest or table open when it came: shut it, or it can't run or fight
            dev.lazuli.mod.inventory.ClickDriver.get().cancel();
            p.closeContainer();
        }
        if (d == Danger.OUTMATCHED) {
            chat.accept(Chat.warn("Too many monsters: running"));
        } else if (d == Danger.FIGHT) {
            session.stop(true); // stand and fight (the job's trip is picked up again after)
        } else if (d == Danger.LOW_HP) {
            session.stop(true); // stand still to heal (the job's trip is picked up again after)
            chat.accept(Chat.warn("Low health ({} HP): pausing to heal", (int) p.getHealth()));
        } else {
            chat.accept(Chat.warn("Creeper! Running"));
        }
    }

    private void release() {
        if (danger == Danger.LOW_HP) chat.accept(Chat.ok("Healed; carrying on"));
        tookOverAt = Math.max(tookOverAt, session.snapshot().trip());
        dev.lazuli.core.goal.Goal g = resume;
        resume = null;
        end();
        LocalPlayer p = Minecraft.getInstance().player;
        if (g != null && p != null) trip.go(g, p, true); // back on the way to where it was going
        trip.clear();
    }

    /** Heads away from the threats' middle, a fresh trip whenever the last one is done and they're still close. */
    private void flee(LocalPlayer p, List<LivingEntity> threats, double dist) {
        if (trip.running()) return;
        if (flees >= FLEE_TRIES * 4) return; // nowhere to go: stay and hope (healing still counts down)
        flees++;
        Vec3 c = Vec3.ZERO;
        for (LivingEntity e : threats) c = c.add(e.position());
        c = c.scale(1.0 / threats.size());
        Vec3 away = p.position().subtract(c).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) away = Vec3.directionFromRotation(0, p.getYRot());
        // a little to the side on each retry, in case straight away is a wall
        double turn = Math.toRadians(((flees % 3) - 1) * 40);
        Vec3 dir = away.normalize();
        dir = new Vec3(dir.x * Math.cos(turn) - dir.z * Math.sin(turn), 0, dir.x * Math.sin(turn) + dir.z * Math.cos(turn));
        Vec3 to = p.position().add(dir.scale(dist));
        trip.go(new GoalXZ((int) Math.floor(to.x), (int) Math.floor(to.z)), p, true);
        tookOverAt = Math.max(tookOverAt, session.snapshot().trip() + 1);
    }

    /**
     * The nearest monster within {@code r} worth hitting: not a creeper (hitting one doesn't stop it), not one that's
     * only dangerous once provoked (endermen, zombified piglins, piglins).
     */
    private static LivingEntity nearestFoe(Minecraft mc, LocalPlayer p, double r) {
        LivingEntity best = null;
        double bd = r;
        for (LivingEntity e : enemies(mc, p, r)) {
            if (e instanceof Creeper || e instanceof net.minecraft.world.entity.monster.EnderMan
                || e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
                || e instanceof net.minecraft.world.entity.monster.piglin.AbstractPiglin) continue;
            double d = e.distanceTo(p);
            if (d < bd && p.hasLineOfSight(e)) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Hostile mobs around, for paths to keep away from (neutral ones that only fight back are left out). */
    public static dev.lazuli.core.move.Hazards hazards(Minecraft mc, LocalPlayer p, Settings settings) {
        double r = settings.mobAvoidRadius.get(), cost = settings.mobAvoidCost.get();
        List<dev.lazuli.core.move.Hazards.Hazard> out = new java.util.ArrayList<>();
        for (LivingEntity e : enemies(mc, p, 40)) {
            if (e instanceof net.minecraft.world.entity.monster.EnderMan
                || e instanceof net.minecraft.world.entity.monster.zombie.ZombifiedPiglin) continue;
            boolean creeper = e instanceof Creeper;
            // on a 2-block grid, so a mob shuffling about doesn't re-price the trip every time
            out.add(new dev.lazuli.core.move.Hazards.Hazard(e.getBlockX() & ~1, e.getBlockY(), e.getBlockZ() & ~1,
                creeper ? r * 1.5 : r, creeper ? cost * 1.5 : cost));
        }
        out.sort(java.util.Comparator.comparingInt((dev.lazuli.core.move.Hazards.Hazard h) -> h.x()).thenComparingInt(h -> h.z())
            .thenComparingInt(h -> h.y()));
        return dev.lazuli.core.move.Hazards.of(out);
    }

    private static List<LivingEntity> creepers(Minecraft mc, LocalPlayer p, double r) {
        return mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
            e -> e instanceof Creeper && e.isAlive() && e.distanceTo(p) <= r);
    }

    private static List<LivingEntity> enemies(Minecraft mc, LocalPlayer p, double r) {
        return mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
            e -> e instanceof Enemy && e.isAlive() && e.distanceTo(p) <= r);
    }
}
