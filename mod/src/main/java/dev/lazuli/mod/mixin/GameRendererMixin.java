package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.RotationDriver;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Eases Lazuli's turning across the frames between ticks. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void lazuli$smoothTurn(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        RotationDriver.frame(Minecraft.getInstance().player, delta.getGameTimeDeltaPartialTick(true));
    }
}
