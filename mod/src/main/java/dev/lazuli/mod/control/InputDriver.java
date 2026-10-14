package dev.lazuli.mod.control;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lazuli.core.control.Controls;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Baritone-style input: the next tick's movement input is overridden directly (see KeyboardInputMixin) and the
 * view is set directly, instead of faking key presses and mouse movement.
 */
public final class InputDriver {
    private static volatile Controls current;

    /** The input the player's next movement tick uses, or null to read the keyboard. */
    public static Controls current() {
        return current;
    }

    /** Sets next tick's input and rotation (see {@link RotationDriver}). */
    public void apply(Minecraft mc, Controls c, float yaw, float pitch, boolean freeLook) {
        current = c;
        RotationDriver.begin(mc.player, freeLook);
        RotationDriver.set(mc.player, yaw, pitch);
    }

    /** Lets go of the keys; the rotation stays Lazuli's until the trip ends (see {@link RotationDriver#end}). */
    public void release() {
        current = null;
    }

    /** True if the user is physically pressing a movement key. */
    public boolean userInterfering(Minecraft mc) {
        Options o = mc.options;
        return physical(mc, o.keyUp) || physical(mc, o.keyDown) || physical(mc, o.keyLeft)
            || physical(mc, o.keyRight) || physical(mc, o.keyJump);
    }

    private static boolean physical(Minecraft mc, KeyMapping k) {
        InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(k);
        return key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(mc.getWindow(), key.getValue());
    }
}
