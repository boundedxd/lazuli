package dev.lazuli.mod.control;

import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Actions paced the way a player performs them: turning capped at the rotation speed, one break tick at a time
 * instead of instant destruction, and clicks only once the crosshair really is on the block.
 */
public final class Legit {
    private Legit() {}

    /** Turns one tick toward yaw/pitch; true once it is within a degree of it. */
    public static boolean aimTick(Minecraft mc, LocalPlayer p, float yaw, float pitch, Settings settings) {
        float max = settings.rotationSpeed.get().floatValue();
        float curYaw = RotationDriver.yaw(p), curPitch = RotationDriver.pitch(p);
        float dYaw = Mth.wrapDegrees(yaw - curYaw);
        float dPitch = pitch - curPitch;
        RotationDriver.begin(p, settings.freeLook.get());
        RotationDriver.set(p, curYaw + Mth.clamp(dYaw, -max, max), curPitch + Mth.clamp(dPitch, -max, max));
        return Math.abs(dYaw) <= 1 && Math.abs(dPitch) <= 1;
    }

    public enum Break { WORKING, DONE, OUT_OF_SIGHT }

    /** One tick of breaking {@code pos}: aim first, then vanilla's own start/continue destroy. */
    public static Break breakTick(Minecraft mc, LocalPlayer p, BlockPos pos, Settings settings) {
        if (mc.level == null) return Break.OUT_OF_SIGHT;
        if (mc.level.getBlockState(pos).isAir()) return Break.DONE;
        BlockHitResult hit = Aim.current(mc, p);
        if (hit == null || !hit.getBlockPos().equals(pos)) {
            float[] aim = Aim.at(mc, p, pos, null);
            if (aim == null) return Break.OUT_OF_SIGHT;
            aimTick(mc, p, aim[0], aim[1], settings);
            return Break.WORKING;
        }
        Tools.equipBestTool(mc, p, mc.level.getBlockState(pos), settings);
        if (mc.gameMode.isDestroying()) mc.gameMode.continueDestroyBlock(pos, hit.getDirection());
        else mc.gameMode.startDestroyBlock(pos, hit.getDirection());
        p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        return Break.WORKING;
    }

    /** One tick of placing at {@code pos}, against a neighbour that is in sight; true once a place really was sent. */
    public static boolean placeAt(Minecraft mc, LocalPlayer p, BlockPos pos, Settings settings) {
        if (mc.level == null) return false;
        for (Direction d : Direction.values()) {
            BlockPos against = pos.relative(d.getOpposite());
            if (mc.level.getBlockState(against).canBeReplaced()) continue;
            BlockHitResult hit = Aim.current(mc, p);
            if (hit != null && hit.getBlockPos().equals(against) && hit.getDirection() == d) {
                var result = mc.gameMode.useItemOn(p, net.minecraft.world.InteractionHand.MAIN_HAND, hit);
                if (result.consumesAction()) {
                    p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                    return true;
                }
                continue;
            }
            float[] aim = Aim.at(mc, p, against, d);
            if (aim != null) {
                aimTick(mc, p, aim[0], aim[1], settings);
                return false; // turning toward it: the next call clicks
            }
        }
        return false;
    }
}
