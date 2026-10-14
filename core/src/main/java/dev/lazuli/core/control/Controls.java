package dev.lazuli.core.control;

/** Desired movement inputs plus the absolute view direction to turn toward (Minecraft yaw/pitch degrees). */
public record Controls(boolean forward, boolean back, boolean jump, boolean sprint, float targetYaw, float targetPitch,
                       int strafe) {
    public Controls(boolean forward, boolean back, boolean jump, boolean sprint, float targetYaw, float targetPitch) {
        this(forward, back, jump, sprint, targetYaw, targetPitch, 0);
    }

    public Controls(boolean forward, boolean jump, boolean sprint, float targetYaw, float targetPitch) {
        this(forward, false, jump, sprint, targetYaw, targetPitch);
    }

    /** -1, 0 or 1, as vanilla's forward impulse. */
    public int impulse() {
        return forward == back ? 0 : forward ? 1 : -1;
    }

    /** A (left) held. */
    public boolean left() {
        return strafe > 0;
    }

    /** D (right) held. */
    public boolean right() {
        return strafe < 0;
    }
}
