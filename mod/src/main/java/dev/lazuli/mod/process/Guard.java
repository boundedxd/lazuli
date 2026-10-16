package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
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
 *   <li>defend: a monster comes within reach, so it turns and hits it (full swings, best weapon) until it's gone.</li>
 * </ul>
 */
public final class Guard implements Process {
    private enum Danger { NONE, CREEPER, LOW_HP, FIGHT }

    /** How close a hissing creeper has to be to run, and how far away it runs. */
    private static final double CREEPER_NEAR = 6, CREEPER_SAFE = 9;
    /** Monsters within this are run from while healing; it heads this far off. */
    private static final double MOB_NEAR = 12, FLEE = 18;
    /** Healing gives up after this long (no food, no regeneration) and lets the job go on. */
    private static final int HEAL_TICKS = 20 * 120;
    private static final int FLEE_TRIES = 3;
    /** A monster this close starts a fight; it's chased this far; a fight gives up after FIGHT_TICKS. */
    private static final double FIGHT_NEAR = 3.0, FIGHT_CHASE = 6, MELEE = 2.9;
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
                    if (since > HEAL_TICKS) chat.accept(Chat.warn("Not healing (no food?); carrying on at {} HP", (int) p.getHealth()));
                    trip.cancel();
                    release();
                    return;
                }
                List<LivingEntity> mobs = enemies(mc, p, MOB_NEAR);
                if (!mobs.isEmpty()) flee(p, mobs, FLEE);
            }
            case FIGHT -> {
                LivingEntity t = nearestFoe(mc, p, FIGHT_CHASE);
                if (t == null || since > FIGHT_TICKS || p.getHealth() <= settings.retreatHealth.get() && settings.lowHpRetreat.get()) {
                    release();
                    return;
                }
                fight(mc, p, t);
            }
            case NONE -> release();
        }
    }

    /** Turns to the target, walks up if it's out of reach, and swings once the cooldown is full and it's in the crosshair. */
    private void fight(Minecraft mc, LocalPlayer p, LivingEntity t) {
        weapon(p);
        Vec3 at = t.position().add(0, t.getBbHeight() * 0.6, 0);
        double dx = at.x - p.getX(), dy = at.y - p.getEyeY(), dz = at.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float maxTurn = settings.rotationSpeed.get().floatValue();
        float yaw0 = dev.lazuli.mod.control.RotationDriver.yaw(p), pitch0 = dev.lazuli.mod.control.RotationDriver.pitch(p);
        float nyaw = dev.lazuli.core.control.InputPlanner.approach(yaw0, yaw, maxTurn);
        float npitch = pitch0 + Math.max(-maxTurn, Math.min(maxTurn, pitch - pitch0));
        double dist = p.distanceTo(t);
        boolean close = dist <= MELEE;
        input.apply(mc, new dev.lazuli.core.control.Controls(!close && dist > 1.5, false, false, false, yaw, pitch, 0, false),
            nyaw, npitch, settings.freeLook.get());
        // the crosshair on it: the view ray passes through its box
        Vec3 eye = p.getEyePosition(), dir = Vec3.directionFromRotation(npitch, nyaw);
        boolean onIt = t.getBoundingBox().inflate(0.1).clip(eye, eye.add(dir.scale(MELEE + 0.5))).isPresent();
        if (close && onIt && p.hasLineOfSight(t) && p.getAttackStrengthScale(0.5f) >= 0.95f) {
            mc.gameMode.attack(p, t);
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    /** The best sword (else axe) in the hotbar into the hand. */
    private static void weapon(LocalPlayer p) {
        int best = -1;
        double bestDmg = 0;
        for (int i = 0; i < 9; i++) {
            var s = p.getInventory().getItem(i);
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!id.endsWith("_sword") && !id.endsWith("_axe")) continue;
            double dmg = tier(id) * 2 + (id.endsWith("_sword") ? 1 : 0);
            if (dmg > bestDmg) {
                bestDmg = dmg;
                best = i;
            }
        }
        if (best >= 0) p.getInventory().setSelectedSlot(best);
    }

    private static int tier(String id) {
        if (id.startsWith("netherite")) return 6;
        if (id.startsWith("diamond")) return 5;
        if (id.startsWith("iron")) return 4;
        if (id.startsWith("stone") || id.startsWith("copper")) return 3;
        if (id.startsWith("golden")) return 2;
        return 1;
    }

    private Danger spot(Minecraft mc, LocalPlayer p) {
        if (settings.dodgeCreepers.get()) {
            for (LivingEntity e : creepers(mc, p, CREEPER_NEAR)) {
                if (((Creeper) e).getSwellDir() > 0 || ((Creeper) e).getSwelling(0f) > 0) return Danger.CREEPER;
            }
        }
        if (settings.lowHpRetreat.get() && p.getHealth() <= settings.retreatHealth.get()) return Danger.LOW_HP;
        if (settings.defend.get() && nearestFoe(mc, p, FIGHT_NEAR) != null) return Danger.FIGHT;
        return Danger.NONE;
    }

    private void take(Danger d, LocalPlayer p) {
        danger = d;
        active = true;
        healing = d == Danger.LOW_HP;
        since = flees = 0;
        tookOverAt = session.snapshot().trip();
        trip.clear();
        if (d == Danger.FIGHT) {
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

    private static List<LivingEntity> creepers(Minecraft mc, LocalPlayer p, double r) {
        return mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
            e -> e instanceof Creeper && e.isAlive() && e.distanceTo(p) <= r);
    }

    private static List<LivingEntity> enemies(Minecraft mc, LocalPlayer p, double r) {
        return mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
            e -> e instanceof Enemy && e.isAlive() && e.distanceTo(p) <= r);
    }
}
