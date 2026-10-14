package dev.lazuli.mod.mixin;

import dev.lazuli.mod.LazuliClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("HEAD"))
    private void lazuli$onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        LazuliClient.onBlockChanged((ClientLevel) (Object) this, pos, newState);
    }
}
