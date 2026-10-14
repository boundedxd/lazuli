package dev.lazuli.mod.control;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lazuli.core.control.Controls;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/** Drives the movement keys and mouse look using only vanilla input paths. */
public final class InputDriver {
    /** Applies key states and a rotation delta (degrees) as if it came from the mouse. */
    public void apply(Minecraft mc, Controls c, float[] rot) {
        Options o = mc.options;
        o.keyUp.setDown(c.forward());
        o.keySprint.setDown(c.sprint());
        o.keyJump.setDown(c.jump());
        // Entity.turn scales its arguments by 0.15, exactly like raw mouse deltas.
        mc.player.turn(rot[0] / 0.15, rot[1] / 0.15);
    }

    /** Returns each driven key to whatever the physical keyboard says. */
    public void release(Minecraft mc) {
        Options o = mc.options;
        restore(mc, o.keyUp);
        restore(mc, o.keySprint);
        restore(mc, o.keyJump);
    }

    /** True if the user is physically pressing a movement key. */
    public boolean userInterfering(Minecraft mc) {
        Options o = mc.options;
        return physical(mc, o.keyUp) || physical(mc, o.keyDown) || physical(mc, o.keyLeft)
            || physical(mc, o.keyRight) || physical(mc, o.keyJump);
    }

    private static void restore(Minecraft mc, KeyMapping k) {
        k.setDown(physical(mc, k));
    }

    private static boolean physical(Minecraft mc, KeyMapping k) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(k);
        return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(mc.getWindow(), key.getValue());
    }
}
