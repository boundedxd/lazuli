package dev.lazuli.mod.control;

import dev.lazuli.core.control.Archery;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/**
 * One fight, the legit way: a bow for skeletons and creepers that are still a way off (drawn fully, aimed with the
 * arrow's drop and the mob's movement, stepping back from one that gets close), else {@link Melee} with the shield up
 * between swings and against arrows.
 */
public final class Combat {
    /** Closer than this it's sword time (creepers: closer still, they're shot until they're about to hiss). */
    private static final double BOW_MIN = 5, CREEPER_BOW_MIN = 3.5;
    /** Shield up against melee this close. */
    private static final double BLOCK_NEAR = 4.5;
    /** Kite: step back from a mob this close while drawing. */
    private static final double KITE = 7;

    private boolean using;

    /** Whether there's a bow and arrows and shooting is on. */
    public static boolean canShoot(LocalPlayer p, Settings s) {
        if (!s.useBow.get()) return false;
        for (int i = 0; i < 36; i++) {
            ItemStack b = p.getInventory().getItem(i);
            if (b.getItem() instanceof BowItem) return !p.getProjectile(b).isEmpty();
        }
        return false;
    }

    /** The nearest mob worth shooting before it gets close: skeletons and other archers, creepers, witches, blazes. */
    public static LivingEntity rangedFoe(Minecraft mc, LocalPlayer p, double range) {
        LivingEntity best = null;
        double bd = range;
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(range),
            e -> e instanceof Enemy && e.isAlive())) {
            boolean shootable = e instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
                || e instanceof net.minecraft.world.entity.monster.illager.Pillager
                || e instanceof net.minecraft.world.entity.monster.Witch || e instanceof net.minecraft.world.entity.monster.Blaze
                || e instanceof Creeper && e.distanceTo(p) < 12;
            if (!shootable) continue;
            double d = e.distanceTo(p);
            if (d < bd && p.hasLineOfSight(e)) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** One tick of fighting {@code t}. */
    public void tick(Minecraft mc, LocalPlayer p, LivingEntity t, InputDriver input, Settings settings) {
        double dist = p.distanceTo(t);
        boolean shoot = canShoot(p, settings) && dist > (t instanceof Creeper ? CREEPER_BOW_MIN : BOW_MIN) && p.hasLineOfSight(t);
        if (shoot) {
            bow(mc, p, t, input, settings);
            return;
        }
        if (using && p.getMainHandItem().getItem() instanceof BowItem) {
            hold(mc, false); // put the bow away mid-draw: sword time
            return;
        }
        Melee.weapon(p);
        boolean shield = settings.useShield.get() && p.getOffhandItem().is(Items.SHIELD) && emptyOrWeapon(p.getMainHandItem());
        boolean arrowsComing = isArcher(t) && t.isUsingItem() && dist > BLOCK_NEAR;
        boolean block = shield && (dist < BLOCK_NEAR && p.getAttackStrengthScale(0.5f) < 0.9f || arrowsComing);
        hold(mc, block);
        Melee.tick(mc, p, t, input, settings);
    }

    /** Lets go of the use key (fight over). */
    public void reset(Minecraft mc) {
        if (using) hold(mc, false);
    }

    private void hold(Minecraft mc, boolean down) {
        mc.options.keyUse.setDown(down); // vanilla starts using the item and lets go when the key's up
        using = down;
    }

    private void bow(Minecraft mc, LocalPlayer p, LivingEntity t, InputDriver input, Settings settings) {
        if (!Hands.hold(p, ClickDriver.get(), s -> s.getItem() instanceof BowItem)) {
            hold(mc, false);
            return;
        }
        // where it'll be when the arrow gets there: aim, see how long that takes, aim there instead
        Vec3 eye = p.getEyePosition();
        Vec3 vel = new Vec3(t.getX() - t.xo, 0, t.getZ() - t.zo);
        Vec3 at = t.position().add(0, t.getBbHeight() * 0.5, 0);
        Archery.Shot shot = null;
        for (int i = 0; i < 2; i++) {
            double dx = at.x - eye.x, dz = at.z - eye.z;
            shot = Archery.aim(Math.hypot(dx, dz), at.y - eye.y);
            if (shot == null) break;
            at = t.position().add(0, t.getBbHeight() * 0.5, 0).add(vel.scale(shot.ticks()));
        }
        double dx = at.x - eye.x, dz = at.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = shot == null ? (float) -Math.toDegrees(Math.atan2(at.y - eye.y, Math.hypot(dx, dz))) : shot.pitch();
        float maxTurn = settings.rotationSpeed.get().floatValue();
        float yaw0 = RotationDriver.yaw(p), pitch0 = RotationDriver.pitch(p);
        float nyaw = InputPlanner.approach(yaw0, yaw, maxTurn);
        float npitch = pitch0 + Math.max(-maxTurn, Math.min(maxTurn, pitch - pitch0));
        boolean back = p.distanceTo(t) < KITE && safeBehind(mc, p, yaw);
        input.apply(mc, new Controls(false, back, false, false, yaw, pitch, 0, false), nyaw, npitch, settings.freeLook.get());
        boolean onTarget = Math.abs(wrap(nyaw - yaw)) < 1.5f && Math.abs(npitch - pitch) < 1.5f;
        if (!p.isUsingItem()) {
            hold(mc, true);
            return;
        }
        // full draw and lined up: let go to shoot (vanilla fires on release)
        if (shot != null && onTarget && p.getTicksUsingItem() >= Archery.FULL_DRAW) hold(mc, false);
        else hold(mc, true);
    }

    /** One step back (away from {@code yaw}) is ground to stand on, with no lava or drop. */
    private static boolean safeBehind(Minecraft mc, LocalPlayer p, float yaw) {
        Vec3 dir = Vec3.directionFromRotation(0, yaw);
        BlockPos b = BlockPos.containing(p.getX() - dir.x * 1.2, p.getY() + 0.2, p.getZ() - dir.z * 1.2);
        var level = mc.level;
        if (!level.getBlockState(b).getCollisionShape(level, b).isEmpty() || !level.getFluidState(b).isEmpty()) return false;
        BlockPos below = b.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty() && level.getFluidState(below).isEmpty();
    }

    private static boolean isArcher(LivingEntity t) {
        return t instanceof net.minecraft.world.entity.monster.skeleton.AbstractSkeleton
            || t instanceof net.minecraft.world.entity.monster.illager.Pillager;
    }

    private static boolean emptyOrWeapon(ItemStack s) {
        if (s.isEmpty()) return true;
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return id.endsWith("_sword") || id.endsWith("_axe");
    }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }
}
