package dev.lazuli.core.mcp;

/** Visible intersection of a GUI control with its screen and ancestor scroll viewports. */
public record ScreenBounds(int left, int top, int right, int bottom) {
    public ScreenBounds clip(ScreenBounds other) {
        return new ScreenBounds(Math.max(left, other.left), Math.max(top, other.top),
            Math.min(right, other.right), Math.min(bottom, other.bottom));
    }
    public boolean visible() { return right > left && bottom > top; }
    public double centerX() { return left / 2.0 + right / 2.0; }
    public double centerY() { return top / 2.0 + bottom / 2.0; }
}
