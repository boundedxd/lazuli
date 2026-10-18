package dev.lazuli.core.control;

import dev.lazuli.core.move.Standing;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.List;

/**
 * Drives the edges under the water's surface (see {@code MoveGraph.deep}): up a column of water with jump held
 * (about 0.12 blocks a tick), down by letting go, level by holding the depth, steering to the next cell's center.
 * Edges between two surface cells are left to the usual swimming.
 */
public final class WaterRiser {
    /** The feet aim this far above the cell's floor: inside it, with the head well under. */
    private static final double HOLD = 0.3;
    /** Off the next cell's center by more than this, it swims toward it (blocks). */
    private static final double SEEK = 0.12;

    private final BlockView world;

    public WaterRiser(BlockView world) {
        this.world = world;
    }

    /** The controls for the submerged edge the player is on, or null if the next edge isn't one. */
    public Controls tick(PlayerState p, List<Long> path, int idx) {
        if (idx < 0 || idx + 1 >= path.size()) return null;
        long u = path.get(idx), v = path.get(idx + 1);
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u), vx = Pos.x(v), vy = Pos.y(v), vz = Pos.z(v);
        if (world.get(ux, uy, uz) != BlockKind.WATER || world.get(vx, vy, vz) != BlockKind.WATER) return null;
        if (Standing.swimmable(world, ux, uy, uz) && Standing.swimmable(world, vx, vy, vz)) return null;
        double tx = vx + 0.5, tz = vz + 0.5;
        float yaw = PathFollower.yawTo(p.x(), p.z(), tx, tz);
        boolean seek = Math.hypot(tx - p.x(), tz - p.z()) > SEEK;
        // the cell it's headed for or stays in: hold the feet a little above its floor
        boolean jump = vy >= uy && p.y() < vy + HOLD;
        return new Controls(seek, false, jump, false, yaw, 0f, 0, false);
    }
}
