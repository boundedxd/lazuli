package dev.lazuli.core.control;

/** Player state at the end of a client tick; velocity is what the next tick starts with. */
public record PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround,
                          boolean horizontalCollision, double vx, double vy, double vz, boolean sprinting) {
    public PlayerState(double x, double y, double z, float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
        this(x, y, z, yaw, pitch, onGround, horizontalCollision, 0, 0, 0, false);
    }
}
