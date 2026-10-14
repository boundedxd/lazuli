package dev.lazuli.mod.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A camera detached from the player that flies with WASD, space and shift (sprint key: faster). The mouse turns it
 * instead of the player, the player's keys do nothing to the player, and Lazuli keeps driving the player meanwhile
 * (with or without free look). Its crosshair picks blocks anywhere in render distance: a left-click goes there.
 */
public final class FreeCam {
    private static final double SPEED = 0.6, SPRINT_SPEED = 2.0;
    private static volatile boolean active;
    private static double x, y, z, xo, yo, zo;
    private static float yaw, pitch;

    private FreeCam() {}

    public static boolean active() {
        return active;
    }

    /** Turns it on at the player's eyes, looking where the camera looks now, or off again. */
    public static void toggle(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (active || p == null) {
            active = false;
            return;
        }
        Vec3 eye = p.getEyePosition();
        x = xo = eye.x;
        y = yo = eye.y;
        z = zo = eye.z;
        yaw = p.getYRot();
        pitch = p.getXRot();
        active = true;
    }

    public static void off() {
        active = false;
    }

    /** End of each client tick: fly with the movement keys. */
    public static void tick(Minecraft mc) {
        xo = x;
        yo = y;
        zo = z;
        if (!active || mc.screen != null) return;
        Options o = mc.options;
        int fwd = (InputDriver.physical(mc, o.keyUp) ? 1 : 0) - (InputDriver.physical(mc, o.keyDown) ? 1 : 0);
        int side = (InputDriver.physical(mc, o.keyLeft) ? 1 : 0) - (InputDriver.physical(mc, o.keyRight) ? 1 : 0);
        int up = (InputDriver.physical(mc, o.keyJump) ? 1 : 0) - (InputDriver.physical(mc, o.keyShift) ? 1 : 0);
        double speed = InputDriver.physical(mc, o.keySprint) ? SPRINT_SPEED : SPEED;
        double len = Math.sqrt(fwd * fwd + side * side);
        if (len > 0) {
            double rad = Math.toRadians(yaw);
            double sin = Math.sin(rad), cos = Math.cos(rad);
            double f = fwd / len * speed, s = side / len * speed;
            x += -sin * f + cos * s;
            z += cos * f + sin * s;
        }
        y += up * speed;
    }

    /** The mouse turned: the camera turns, not the player. */
    public static void turn(double dx, double dy) {
        yaw += (float) dx * 0.15f;
        pitch = Mth.clamp(pitch + (float) dy * 0.15f, -90f, 90f);
    }

    public static Vec3 position(float partial) {
        return new Vec3(Mth.lerp(partial, xo, x), Mth.lerp(partial, yo, y), Mth.lerp(partial, zo, z));
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    /** The block the camera is in. */
    public static int[] feet() {
        return new int[]{Mth.floor(x), Mth.floor(y), Mth.floor(z)};
    }

    /** What the camera's crosshair is on, up to render distance. */
    public static HitResult pick(Minecraft mc, float partial) {
        Vec3 from = position(partial);
        double range = mc.options.getEffectiveRenderDistance() * 16.0;
        Vec3 to = from.add(Vec3.directionFromRotation(pitch, yaw).scale(range));
        return mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
    }

    /** The block a left-click picks, or null. */
    public static BlockHitResult clicked(Minecraft mc) {
        HitResult hit = pick(mc, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
        return hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK ? bh : null;
    }
}
