package dev.lazuli.core.control;

/** Smooth, speed- and acceleration-limited turning toward a target view direction. */
public final class RotationController {
    private float yawVel;
    private float pitchVel;

    public void reset() {
        yawVel = 0;
        pitchVel = 0;
    }

    /** Returns {dYaw, dPitch} to apply this tick; |d| never exceeds maxSpeed. */
    public float[] step(float yaw, float pitch, float targetYaw, float targetPitch, float maxSpeed) {
        float yawErr = wrap(targetYaw - yaw);
        float pitchErr = Math.max(-90f, Math.min(90f, targetPitch)) - pitch;
        float dYaw = axis(yawErr, true, maxSpeed);
        float dPitch = axis(pitchErr, false, maxSpeed);
        return new float[]{dYaw, dPitch};
    }

    private float axis(float err, boolean isYaw, float maxSpeed) {
        float vel = isYaw ? yawVel : pitchVel;
        float desired = clamp(err * 0.6f, maxSpeed);
        vel += clamp(desired - vel, maxSpeed * 0.6f);
        vel = clamp(vel, maxSpeed);
        float out = vel;
        if (Math.abs(err) < 0.05f && Math.abs(vel) < 0.5f) {
            out = err;
            vel = 0;
        }
        if (isYaw) yawVel = vel; else pitchVel = vel;
        return out;
    }

    private static float clamp(float v, float limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    /** Wraps degrees into (-180, 180]. */
    public static float wrap(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }
}
