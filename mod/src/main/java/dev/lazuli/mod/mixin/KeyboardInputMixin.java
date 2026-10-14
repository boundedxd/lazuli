package dev.lazuli.mod.mixin;

import dev.lazuli.core.control.Controls;
import dev.lazuli.mod.control.InputDriver;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Baritone-style: while pathing, the movement input comes from Lazuli instead of the keyboard. */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void lazuli$override(CallbackInfo ci) {
        Controls c = InputDriver.current();
        if (c == null) return;
        this.keyPresses = new Input(c.forward(), c.back(), c.left(), c.right(), c.jump(), c.sneak(), c.sprint());
        this.moveVector = new Vec2(c.strafe(), c.impulse()).normalized();
    }
}
