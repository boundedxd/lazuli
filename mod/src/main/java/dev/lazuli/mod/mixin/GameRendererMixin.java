package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.FreeCam;
import dev.lazuli.mod.control.RotationDriver;
import org.joml.Matrix4f;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Eases Lazuli's turning across the frames between ticks; freecam's hand and crosshair. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void lazuli$smoothTurn(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
        RotationDriver.frame(Minecraft.getInstance().player, delta.getGameTimeDeltaPartialTick(true));
    }

    /** Freecam: no hand in view. */
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void lazuli$noHand(float partial, boolean b, Matrix4f m, CallbackInfo ci) {
        if (FreeCam.active()) ci.cancel();
    }

    /** Freecam: the crosshair (and block outline) is the camera's. */
    @Inject(method = "pick", at = @At("TAIL"))
    private void lazuli$freecamPick(float partial, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (!FreeCam.active() || mc.level == null || mc.player == null) return;
        mc.hitResult = FreeCam.pick(mc, partial);
        mc.crosshairPickEntity = null;
    }
}
