package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.FreeCam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the mouse turns the camera, not the player. */
@Mixin(Entity.class)
public abstract class EntityMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void lazuli$turnCamera(double dx, double dy, CallbackInfo ci) {
        if (FreeCam.active() && (Object) this == Minecraft.getInstance().player) {
            FreeCam.turn(dx, dy);
            ci.cancel();
        }
    }
}
