package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.ClickGoal;
import dev.lazuli.mod.control.FreeCam;
import dev.lazuli.mod.control.Interactor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** #click takes the left-click; while Lazuli mines, it holds "attack" on its target instead of vanilla stopping the break every tick. */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow public MultiPlayerGameMode gameMode;
    @Shadow public LocalPlayer player;
    @Shadow public ClientLevel level;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void lazuli$click(CallbackInfoReturnable<Boolean> cir) {
        if (ClickGoal.click((Minecraft) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void lazuli$noUseInFreecam(CallbackInfo ci) {
        if (FreeCam.active()) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void lazuli$mine(boolean attacking, CallbackInfo ci) {
        if (ClickGoal.swallowHeld(attacking)) {
            ci.cancel();
            return;
        }
        BlockPos pos = Interactor.mining();
        if (pos == null && FreeCam.active()) {
            ci.cancel(); // the crosshair is the camera's, far from the player: never break what it's on
            return;
        }
        if (pos == null || gameMode == null || player == null || level == null) return;
        ci.cancel();
        Direction face = Interactor.miningFace();
        if (!level.getBlockState(pos).isAir() && gameMode.continueDestroyBlock(pos, face)) {
            level.addBreakingBlockEffect(pos, face);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
