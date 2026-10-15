package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Sprint-swims (the game's swim mode) along stretches of open water on the path, just under the surface, and comes up
 * for air before it runs low. Near the end of the water it lets go, so the usual planner climbs out.
 */
final class Swimmer {
    /** Swim nodes ahead it takes to bother with swim mode (a short splash isn't worth diving for). */
    private static final int MIN_RUN = 4;
    /** Swim nodes before the bank where it stops swimming, to climb out normally. */
    private static final int EXIT = 2;
    /** Nodes ahead it steers for. */
    private static final int AHEAD = 3;
    /** The eyes stay this far under the surface (deep enough to stay in swim mode, shallow enough to come up fast). */
    private static final double DEPTH = 0.35;
    private static final double WATER_TOP = 8.0 / 9.0;

    private final BlockView world;
    private boolean breathing;

    Swimmer(BlockView world) {
        this.world = world;
    }

    void reset() {
        breathing = false;
    }

    /** Controls for open water ahead, or null to leave it to the usual movement. */
    Controls tick(LocalPlayer p, List<Long> path, int idx, float yaw0) {
        if (!p.isInWater() || idx < 0 || idx >= path.size()) {
            breathing = false;
            return null;
        }
        int end = idx;
        while (end + 1 < path.size() && swims(path.get(end + 1))) end++;
        if (!swims(path.get(idx)) && !p.isSwimming()) return null;
        if (end - idx < (p.isSwimming() ? EXIT : MIN_RUN)) return null; // the bank is close: climb out as usual
        long target = path.get(Math.min(end - EXIT + 1, idx + AHEAD));
        double tx = Pos.x(target) + 0.5, tz = Pos.z(target) + 0.5;
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - p.getX()), tz - p.getZ()));
        int max = p.getMaxAirSupply();
        if (p.getAirSupply() < max / 3) breathing = true;
        if (breathing && p.getAirSupply() >= max) breathing = false;
        if (breathing) {
            // out of swim mode (no sprint), jump held, looking up: up to the air, still heading on
            if (p.isSprinting()) p.setSprinting(false);
            return new Controls(true, false, true, false, yaw, -30f);
        }
        if (!p.isSwimming()) {
            // swim mode needs the eyes under: sink a little (no jump) sprinting forward, looking down
            return new Controls(true, false, false, true, yaw, 30f);
        }
        // swimming goes where the view points: steer the eyes to just under the surface
        double surface = Pos.y(target) + WATER_TOP;
        double pitch = Math.max(-25, Math.min(25, (p.getEyeY() - (surface - DEPTH)) * 60));
        return new Controls(true, false, false, true, yaw, (float) pitch);
    }

    private boolean swims(long n) {
        return Standing.swimmable(world, Pos.x(n), Pos.y(n), Pos.z(n));
    }
}
