package dev.lazuli.mod.mixin;

import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The boat's spin and the keys it was last handed: #iceboat's sim starts from exactly what the game has. */
@Mixin(AbstractBoat.class)
public interface AbstractBoatAccessor {
    @Accessor("deltaRotation")
    float lazuli$spin();

    @Accessor("inputLeft")
    boolean lazuli$left();

    @Accessor("inputRight")
    boolean lazuli$right();

    @Accessor("inputUp")
    boolean lazuli$forward();

    @Accessor("inputDown")
    boolean lazuli$back();
}
