package dev.lazuli.core.control;

import dev.lazuli.core.physics.PlayerSim;
import dev.lazuli.core.world.BlockView;

/**
 * Player state at the end of a client tick; velocity is what the next tick starts with. The jump cooldown and last
 * collision carry over into the next tick too (they decide whether a held jump fires and whether sprint survives).
 */
public record PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround,
                          boolean horizontalCollision, double vx, double vy, double vz, boolean sprinting,
                          int noJumpDelay, boolean minorHorizontalCollision, double fallDistance) {
    /** @param fallDistance blocks fallen so far in the current fall (0 on the ground) */
    public PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision,
                       double vx, double vy, double vz, boolean sprinting, int noJumpDelay, boolean minorHorizontalCollision) {
        this(x, y, z, yaw, pitch, onGround, horizontalCollision, vx, vy, vz, sprinting, noJumpDelay, minorHorizontalCollision, 0);
    }

    public PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision,
                       double vx, double vy, double vz, boolean sprinting) {
        this(x, y, z, yaw, pitch, onGround, horizontalCollision, vx, vy, vz, sprinting, 0, false, 0);
    }

    public PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
        this(x, y, z, yaw, pitch, onGround, horizontalCollision, 0, 0, 0, false);
    }

    /** A simulation starting from this state. */
    public PlayerSim sim(BlockView world) {
        PlayerSim s = new PlayerSim(world, x, y, z, vx, vy, vz, onGround, sprinting);
        s.noJumpDelay = noJumpDelay;
        s.horizontalCollision = horizontalCollision;
        s.minorHorizontalCollision = minorHorizontalCollision;
        s.peakY = y + fallDistance;
        return s;
    }
}
