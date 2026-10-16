package dev.lazuli.mod.control;

import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Water bucket clutch: falling far enough to get hurt with a water bucket, look straight down, put water on the ground
 * once it's in reach (just before landing), and scoop it back up after.
 */
public final class Clutch {
    /** Falls longer than this (blocks, counting what's fallen already) get clutched; 3 and under never hurt. */
    private static final double HURTS = 3.5;
    private static final int PICKUP_TICKS = 40;

    private final Settings settings;
    private BlockPos placed, tried;
    private int pickupTicks, usedAt = -100, ticks;

    public Clutch(Settings settings) {
        this.settings = settings;
    }

    /** What the clutch wants this tick, or null when it isn't doing anything. */
    public record Aim(float pitch, boolean still) {}

    /** Whether a water bucket is at hand to clutch with (and water can be placed in this dimension). */
    public static boolean ready(Minecraft mc, LocalPlayer p) {
        return slot(p, Items.WATER_BUCKET) >= 0 && !ultraWarm(mc.level);
    }

    public Aim tick(Minecraft mc, LocalPlayer p) {
        ticks++;
        if (!settings.waterClutch.get() || p.getAbilities().flying || p.isFallFlying() || p.isPassenger()) {
            placed = null;
            return null;
        }
        // the water (or the emptied bucket) shows up a tick or two after the click
        if (tried != null && placed == null && (mc.level.getFluidState(tried).is(Fluids.WATER) || ticks - usedAt <= 10
            && slot(p, Items.WATER_BUCKET) < 0)) {
            placed = tried;
            pickupTicks = 0;
        }
        if (ticks - usedAt > 10) tried = null;
        if (placed != null) return pickUp(mc, p);
        if (p.onGround() || p.isInWater() || p.onClimbable() || p.getDeltaMovement().y > -0.2) return null;
        // where it lands: the first block with something to stand on under the feet (water: nothing to do)
        BlockPos ground = null;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int feet = (int) Math.floor(p.getY());
        for (int y = feet; y >= Math.max(mc.level.getMinY(), feet - 400); y--) {
            at.set(p.getBlockX(), y, p.getBlockZ());
            var state = mc.level.getBlockState(at);
            if (!state.getFluidState().isEmpty()) return null;
            if (!state.getCollisionShape(mc.level, at).isEmpty()) {
                ground = at.immutable();
                break;
            }
        }
        if (ground == null) return null;
        double drop = p.getY() - (ground.getY() + 1);
        if (p.fallDistance + drop < HURTS || ultraWarm(mc.level)) return null;
        int slot = slot(p, Items.WATER_BUCKET);
        if (slot < 0) return null;
        if (slot >= 9) {
            // into the hand first
            ClickDriver clicks = ClickDriver.get();
            if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, p.getInventory().getSelectedSlot())));
            return new Aim(90f, false);
        }
        p.getInventory().setSelectedSlot(slot);
        // straight down: the top of the ground in reach, the bucket goes on it
        BlockHitResult hit = view(mc, p, ClipContext.Fluid.NONE);
        if (RotationDriver.pitch(p) > 80 && hit != null && hit.getBlockPos().equals(ground) && hit.getDirection() == Direction.UP
            && ticks - usedAt > 3) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            usedAt = ticks;
            tried = ground.above();
        }
        return new Aim(90f, false);
    }

    /** Landed in it: scoop the water back up (or leave it, when that's off or it's taking too long). */
    private Aim pickUp(Minecraft mc, LocalPlayer p) {
        if (!p.onGround() && !p.isInWater()) return new Aim(90f, false); // still coming down
        if (!settings.clutchPickup.get() || ++pickupTicks > PICKUP_TICKS || !mc.level.getFluidState(placed).isSource()) {
            placed = null;
            return null;
        }
        int slot = slot(p, Items.BUCKET);
        if (slot < 0 || slot >= 9) {
            placed = null;
            return null;
        }
        p.getInventory().setSelectedSlot(slot);
        BlockHitResult hit = view(mc, p, ClipContext.Fluid.SOURCE_ONLY);
        if (RotationDriver.pitch(p) > 80 && hit != null && hit.getBlockPos().equals(placed) && ticks - usedAt > 3) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            usedAt = ticks;
        }
        if (p.getInventory().getItem(slot).is(Items.WATER_BUCKET)) {
            placed = null;
            return null;
        }
        return new Aim(90f, true);
    }

    private static BlockHitResult view(Minecraft mc, LocalPlayer p, ClipContext.Fluid fluid) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(RotationDriver.pitch(p), RotationDriver.yaw(p));
        HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE, fluid, p));
        return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    private static int slot(LocalPlayer p, Item item) {
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
    }

    private static boolean ultraWarm(Level level) {
        return level.dimension() == Level.NETHER;
    }
}
