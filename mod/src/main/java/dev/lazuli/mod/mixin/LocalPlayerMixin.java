package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.RotationDriver;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player moves and reports its rotation with the one Lazuli chose (see {@link RotationDriver}). */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void lazuli$rotate(CallbackInfo ci) {
        RotationDriver.beforeTick((LocalPlayer) (Object) this);
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void lazuli$restore(CallbackInfo ci) {
        RotationDriver.afterTick((LocalPlayer) (Object) this);
    }
}
