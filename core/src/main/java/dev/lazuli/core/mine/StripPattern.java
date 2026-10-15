package dev.lazuli.core.mine;

/**
 * Strip mining back and forth over an area: a long tunnel, a short one over to the side, a long one back, a short one
 * further over... Tunnels {@link #SPACING} apart see every block in between (each shows the blocks beside it).
 * Directions are Minecraft's 2D values: 0 south, 1 west, 2 north, 3 east (clockwise).
 */
public record StripPattern(int start, int length) {
    public static final int SPACING = 4;

    public record Leg(int dir, int length) {}

    /** Leg {@code k}: even ones long (out, back, out...), odd ones over to the start's right side. */
    public Leg leg(int k) {
        if (k % 2 == 1) return new Leg((start + 1) & 3, SPACING);
        return new Leg(k % 4 == 0 ? start : (start + 2) & 3, length);
    }
}
