package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Hitting a mob the legit way: best sword or axe in hand, turn to it, walk up, swing on a full cooldown with it in the crosshair. */
public final class Melee {
    /** Close enough to swing. */
    public static final double REACH = 2.9;

    private Melee() {}

    /** One tick of fighting {@code t}. */
    public static void tick(Minecraft mc, LocalPlayer p, LivingEntity t, InputDriver input, Settings settings) {
        weapon(p);
        Vec3 at = t.position().add(0, t.getBbHeight() * 0.6, 0);
        double dx = at.x - p.getX(), dy = at.y - p.getEyeY(), dz = at.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float maxTurn = settings.rotationSpeed.get().floatValue();
        float yaw0 = RotationDriver.yaw(p), pitch0 = RotationDriver.pitch(p);
        float nyaw = InputPlanner.approach(yaw0, yaw, maxTurn);
        float npitch = pitch0 + Math.max(-maxTurn, Math.min(maxTurn, pitch - pitch0));
        double dist = p.distanceTo(t);
        boolean close = dist <= REACH;
        input.apply(mc, new Controls(!close && dist > 1.5, false, false, false, yaw, pitch, 0, false), nyaw, npitch,
            settings.freeLook.get());
        // the crosshair on it: the view ray passes through its box
        Vec3 eye = p.getEyePosition(), dir = Vec3.directionFromRotation(npitch, nyaw);
        boolean onIt = t.getBoundingBox().inflate(0.1).clip(eye, eye.add(dir.scale(REACH + 0.5))).isPresent();
        if (close && onIt && !p.isUsingItem() && p.hasLineOfSight(t) && p.getAttackStrengthScale(0.5f) >= 0.95f) {
            mc.gameMode.attack(p, t);
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** The best sword (else axe) in the hotbar into the hand. */
    public static void weapon(LocalPlayer p) {
        int best = -1;
        double bestDmg = 0;
        for (int i = 0; i < 9; i++) {
            var s = p.getInventory().getItem(i);
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!id.endsWith("_sword") && !id.endsWith("_axe")) continue;
            double dmg = tier(id) * 2 + (id.endsWith("_sword") ? 1 : 0);
            if (dmg > bestDmg) {
                bestDmg = dmg;
                best = i;
            }
        }
        if (best >= 0) p.getInventory().setSelectedSlot(best);
    }

    /** Attack damage of the best sword or axe in the hotbar (1: fists). */
    public static double bestDamage(LocalPlayer p) {
        double best = 1;
        for (int i = 0; i < 9; i++) {
            var s = p.getInventory().getItem(i);
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!id.endsWith("_sword") && !id.endsWith("_axe")) continue;
            var m = s.getOrDefault(net.minecraft.core.component.DataComponents.ATTRIBUTE_MODIFIERS,
                net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
            best = Math.max(best, m.compute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, 1,
                net.minecraft.world.entity.EquipmentSlot.MAINHAND));
        }
        return best;
    }

    private static int tier(String id) {
        if (id.startsWith("netherite")) return 6;
        if (id.startsWith("diamond")) return 5;
        if (id.startsWith("iron")) return 4;
        if (id.startsWith("stone") || id.startsWith("copper")) return 3;
        if (id.startsWith("golden")) return 2;
        return 1;
    }
}
