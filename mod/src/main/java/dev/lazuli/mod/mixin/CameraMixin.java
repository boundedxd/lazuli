package dev.lazuli.mod.mixin;

import dev.lazuli.mod.control.FreeCam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the camera sits where {@link FreeCam} is, detached, so the player is drawn. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow private boolean detached;

    @Shadow protected abstract void setRotation(float yaw, float pitch);

    @Shadow protected abstract void setPosition(Vec3 pos);

    @Inject(method = "setup", at = @At("TAIL"))
    private void lazuli$freecam(Level level, Entity entity, boolean detached, boolean mirrored, float partial, CallbackInfo ci) {
        if (!FreeCam.active()) return;
        this.detached = true;
        setRotation(FreeCam.yaw(), FreeCam.pitch());
        setPosition(FreeCam.position(partial));
    }
}
