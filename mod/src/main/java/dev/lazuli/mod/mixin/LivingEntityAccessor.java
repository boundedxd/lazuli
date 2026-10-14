package dev.lazuli.mod.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The jump cooldown: a held jump only fires once it reaches 0, which the input planner must know. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("noJumpDelay")
    int lazuli$noJumpDelay();
}
