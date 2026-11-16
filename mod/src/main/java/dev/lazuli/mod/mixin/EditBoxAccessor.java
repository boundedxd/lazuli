package dev.lazuli.mod.mixin;

import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Dialog input metadata needed to avoid editing read-only or truncated fields. */
@Mixin(EditBox.class)
public interface EditBoxAccessor {
    @Accessor("isEditable") boolean lazuli$editable();
    @Accessor("maxLength") int lazuli$maxLength();
}
