package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.RotationDriver;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Elytra with free look: a rocket pushes along where the player looks, read on the rocket's own tick, when the
 * player has the camera's rotation back. Pushes along the bot's rotation instead, as the server sees it.
 */
@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketMixin {
    @Redirect(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;getLookAngle()Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 lazuli$botLook(LivingEntity entity) {
        if (entity instanceof LocalPlayer p && RotationDriver.freeLooking()) {
            return Vec3.directionFromRotation(RotationDriver.pitch(p), RotationDriver.yaw(p));
        }
        return entity.getLookAngle();
    }
}
