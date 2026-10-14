package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.RotationDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Free look: the player's model looks where the server sees it looking. Its head already turns with Lazuli's yaw (set
 * during the player's tick); the pitch would otherwise come from the camera.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
    private void lazuli$botPitch(LivingEntity entity, LivingEntityRenderState state, float partial, CallbackInfo ci) {
        if (entity != Minecraft.getInstance().player || !RotationDriver.freeLooking()) return;
        state.xRot = RotationDriver.renderPitch(partial);
        if (state.isUpsideDown) state.xRot *= -1f;
    }
}
