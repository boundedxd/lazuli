package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Looking at blocks the legit way, for the processes that break and place on their own (#mine, #tunnel, #farm):
 * find a part of the block the eye can see, turn there at the configured speed, and only act once the crosshair (the
 * server-side rotation, with free look) is really on it.
 */
public final class Aim {
    private static final double[][] POINTS = {{0.5, 0.5, 0.5}, {0.5, 0.95, 0.5}, {0.5, 0.05, 0.5}, {0.05, 0.5, 0.5},
        {0.95, 0.5, 0.5}, {0.5, 0.5, 0.05}, {0.5, 0.5, 0.95}, {0.2, 0.8, 0.2}, {0.8, 0.8, 0.8}, {0.2, 0.2, 0.8}, {0.8, 0.2, 0.2}};

    public enum Result { WORKING, DONE, BLOCKED }

    private Aim() {}

    /** Yaw and pitch putting the crosshair on {@code bp} (on {@code face}, if not null) within reach, or null. */
    public static float[] at(Minecraft mc, LocalPlayer player, BlockPos bp, Direction face) {
        return at(mc, player, player.getEyePosition(), bp, face);
    }

    /** {@link #at} from an eye somewhere else (where the player could stand). */
    public static float[] at(Minecraft mc, LocalPlayer player, Vec3 eye, BlockPos bp, Direction face) {
        double reach = player.blockInteractionRange();
        for (double[] q : POINTS) {
            double qx = q[0], qy = q[1], qz = q[2];
            if (face != null) {
                // a point on that face: pin its axis to the face, keep the spread on the other two
                if (face.getStepX() != 0) qx = face.getStepX() > 0 ? 0.999 : 0.001;
                if (face.getStepY() != 0) qy = face.getStepY() > 0 ? 0.999 : 0.001;
                if (face.getStepZ() != 0) qz = face.getStepZ() > 0 ? 0.999 : 0.001;
            }
            Vec3 t = new Vec3(bp.getX() + qx, bp.getY() + qy, bp.getZ() + qz);
            if (t.distanceTo(eye) > reach) continue;
            double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            BlockHitResult h = crosshair(mc, player, eye, yaw, pitch);
            if (h != null && h.getBlockPos().equals(bp) && (face == null || h.getDirection() == face)) return new float[]{yaw, pitch};
        }
        return null;
    }

    /** What a view along yaw/pitch from the eye hits within reach, or null. */
    public static BlockHitResult crosshair(Minecraft mc, LocalPlayer player, float yaw, float pitch) {
        return crosshair(mc, player, player.getEyePosition(), yaw, pitch);
    }

    private static BlockHitResult crosshair(Minecraft mc, LocalPlayer player, Vec3 eye, float yaw, float pitch) {
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(player.blockInteractionRange())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    /** What the player's current rotation hits. */
    public static BlockHitResult current(Minecraft mc, LocalPlayer player) {
        return crosshair(mc, player, RotationDriver.yaw(player), RotationDriver.pitch(player));
    }

    /** Turns toward {@code aim} this tick, standing still. */
    public static void turn(Minecraft mc, LocalPlayer player, InputDriver input, float[] aim, Settings settings) {
        float maxTurn = settings.rotationSpeed.get().floatValue();
        float yaw0 = RotationDriver.yaw(player), pitch0 = RotationDriver.pitch(player);
        float yaw = InputPlanner.approach(yaw0, aim[0], maxTurn);
        float pitch = pitch0 + Math.max(-maxTurn, Math.min(maxTurn, aim[1] - pitch0));
        input.apply(mc, new Controls(false, false, false, false, aim[0], aim[1], 0, false), yaw, pitch, settings.freeLook.get());
    }

    /** One tick of breaking {@code bp}: DONE once it's gone, BLOCKED if no part of it is in sight. */
    public static Result breakTick(Minecraft mc, LocalPlayer player, BlockPos bp, InputDriver input, Settings settings) {
        var state = mc.level.getBlockState(bp);
        if (state.isAir() || !state.getFluidState().isEmpty() && state.getCollisionShape(mc.level, bp).isEmpty()) return Result.DONE;
        float[] aim = at(mc, player, bp, null);
        if (aim == null) return Result.BLOCKED;
        BlockHitResult hit = current(mc, player);
        if (hit != null && hit.getBlockPos().equals(bp)) {
            Tools.equipBestTool(mc, player, state, settings);
            Interactor.mineAt(bp, hit.getDirection());
        }
        turn(mc, player, input, aim, settings);
        return Result.WORKING;
    }

    /**
     * One tick of right-clicking face {@code face} (any face, if null) of {@code against} with the selected item: true
     * once used, false while turning (or if it can't be seen, see {@link #at}).
     */
    public static boolean useOn(Minecraft mc, LocalPlayer player, BlockPos against, Direction face, InputDriver input, Settings settings) {
        float[] aim = at(mc, player, against, face);
        if (aim == null) return false;
        BlockHitResult hit = current(mc, player);
        turn(mc, player, input, aim, settings);
        if (hit == null || !hit.getBlockPos().equals(against) || face != null && hit.getDirection() != face) return false;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        return false;
    }
}
