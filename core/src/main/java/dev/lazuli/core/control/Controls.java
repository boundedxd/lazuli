package dev.lazuli.core.control;

/** Desired key states plus the absolute view direction to turn toward (Minecraft yaw/pitch degrees). */
public record Controls(boolean forward, boolean jump, boolean sprint, float targetYaw, float targetPitch) {}
