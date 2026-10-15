package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Lights the way underground (autoTorch): where it's dark enough for mobs to spawn and no sky reaches, it stops on
 * the spot, looks down and places a torch at its feet, so it never walks back into a dark tunnel it dug itself.
 */
final class Torcher {
    /** Ticks a placement may take before it's given up on for a while. */
    private static final int TRY_TICKS = 30, COOLDOWN = 100;
    /** Sky light at or above which it's open enough not to bother (caves near the surface, the surface at night). */
    private static final int SKY = 8;

    private final Settings settings;
    private int ticks, cooldown, prevSlot = -1;
    private boolean active;

    Torcher(Settings settings) {
        this.settings = settings;
    }

    void reset(Minecraft mc) {
        if (active) restoreSlot(mc.player);
        active = false;
        ticks = 0;
    }

    /** Controls while placing a torch (standing still), else null. */
    Controls tick(Minecraft mc, LocalPlayer p, float yaw0, float pitch0) {
        if (cooldown > 0) cooldown--;
        if (!active) {
            if (!wanted(mc, p)) return null;
            active = true;
            ticks = 0;
            prevSlot = p.getInventory().getSelectedSlot();
        }
        ticks++;
        BlockPos feet = p.blockPosition(), floor = feet.below();
        int slot = torchSlot(p);
        if (ticks > TRY_TICKS || slot < 0 || !p.onGround() || !mc.level.getBlockState(feet).isAir()) return done(p, false);
        p.getInventory().setSelectedSlot(slot);
        float[] aim = Aim.at(mc, p, floor, Direction.UP);
        if (aim == null) return done(p, false);
        BlockHitResult hit = Aim.current(mc, p);
        if (hit != null && hit.getBlockPos().equals(floor) && hit.getDirection() == Direction.UP
            && mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            p.swing(InteractionHand.MAIN_HAND);
            return done(p, true);
        }
        return new Controls(false, false, false, false, aim[0], aim[1]);
    }

    private Controls done(LocalPlayer p, boolean placed) {
        restoreSlot(p);
        active = false;
        // a placed torch lights the spot up at once; a failed try waits a while before the next
        cooldown = placed ? 20 : COOLDOWN;
        return null;
    }

    private void restoreSlot(LocalPlayer p) {
        if (p != null && prevSlot >= 0) p.getInventory().setSelectedSlot(prevSlot);
        prevSlot = -1;
    }

    /** Dark, under cover, on solid ground with a free spot at the feet, and a torch in the hotbar. */
    private boolean wanted(Minecraft mc, LocalPlayer p) {
        if (!settings.autoTorch.get() || cooldown > 0 || !p.onGround() || p.isInWater() || mc.screen != null) return false;
        BlockPos feet = p.blockPosition();
        if (mc.level.getBrightness(LightLayer.BLOCK, feet) >= settings.torchLight.get()) return false;
        if (mc.level.getBrightness(LightLayer.SKY, feet) >= SKY) return false;
        if (!mc.level.getBlockState(feet).isAir() || !mc.level.getBlockState(feet.below()).isFaceSturdy(mc.level, feet.below(), Direction.UP)) {
            return false;
        }
        return torchSlot(p) >= 0;
    }

    private static int torchSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            var s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (id.equals("torch") || id.equals("soul_torch")) return i;
        }
        return -1;
    }
}
