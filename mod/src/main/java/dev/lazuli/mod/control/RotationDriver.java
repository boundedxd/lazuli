package dev.lazuli.mod.control;

import net.minecraft.client.player.LocalPlayer;

/**
 * Where the player looks while Lazuli drives. Each client tick the executor picks next tick's rotation; the player
 * ticks (moves, sends its rotation to the server) with exactly that rotation.
 * <ul>
 *   <li>Normal: the camera follows, eased in frame by frame instead of jumping once a tick.</li>
 *   <li>Free look: only the server sees it (like Baritone's freeLook). The rotation is swapped in for the player's
 *   tick and the camera's own rotation put back afterwards, so the mouse stays free.</li>
 * </ul>
 */
public final class RotationDriver {
    private static volatile boolean active;
    private static volatile boolean freeLook;
    /** Rotation for the coming tick, and where the frame-by-frame easing starts from. */
    private static float yaw, pitch, fromYaw, fromPitch;
    /** The camera's rotation, held aside while the player ticks with the bot's (free look). */
    private static float camYaw, camPitch;
    private static boolean swapped;

    private RotationDriver() {}

    /** Starts driving from the player's rotation now. */
    public static void begin(LocalPlayer p, boolean free) {
        if (!active) {
            yaw = fromYaw = p.getYRot();
            pitch = fromPitch = p.getXRot();
        }
        freeLook = free;
        active = true;
    }

    public static void end() {
        active = false;
    }

    /** The rotation the player has as far as movement and the server go. */
    public static float yaw(LocalPlayer p) {
        return active && freeLook ? yaw : p.getYRot();
    }

    public static float pitch(LocalPlayer p) {
        return active && freeLook ? pitch : p.getXRot();
    }

    /** Next tick's rotation. */
    public static void set(LocalPlayer p, float newYaw, float newPitch) {
        fromYaw = yaw(p);
        fromPitch = pitch(p);
        yaw = newYaw;
        pitch = newPitch;
    }

    /** LocalPlayer.tick, before: tick with exactly the chosen rotation. */
    public static void beforeTick(LocalPlayer p) {
        if (!active) return;
        if (freeLook) {
            camYaw = p.getYRot();
            camPitch = p.getXRot();
            swapped = true;
        }
        p.setYRot(yaw);
        p.setXRot(pitch);
    }

    /** LocalPlayer.tick, after: free look gives the camera its own rotation back. */
    public static void afterTick(LocalPlayer p) {
        if (!swapped) return;
        swapped = false;
        p.setYRot(camYaw);
        p.setXRot(camPitch);
        p.yRotO = camYaw;
        p.xRotO = camPitch;
    }

    /** Each frame: ease the camera from last tick's rotation to the coming one. */
    public static void frame(LocalPlayer p, float partial) {
        if (!active || freeLook || p == null) return;
        float t = Math.max(0, Math.min(1, partial));
        p.setYRot(fromYaw + net.minecraft.util.Mth.wrapDegrees(yaw - fromYaw) * t);
        p.setXRot(fromPitch + (pitch - fromPitch) * t);
    }
}
