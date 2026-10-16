package dev.lazuli.mod.render;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The Material 3 look for the HUD: a dark tonal scheme taken from one accent colour (every surface a little of its
 * hue, lighter as it rises), plus rounded boxes and the linear progress indicator drawn from plain fills.
 */
final class Material {
    private Material() {}

    /** The colour roles, all opaque except the card (a little see-through). */
    record Scheme(int surface, int surfaceHigh, int track, int primary, int onPrimary, int onSurface, int onSurfaceVariant,
                  int chip, int onChip, int outline) {
        static Scheme of(int accentRgb) {
            float[] hsb = java.awt.Color.RGBtoHSB(accentRgb >> 16 & 255, accentRgb >> 8 & 255, accentRgb & 255, null);
            float h = hsb[0], s = hsb[1];
            return new Scheme(
                hsb(h, 0.12f, 0.135f, 0xE6), hsb(h, 0.14f, 0.17f, 0xFF), hsb(h, 0.20f, 0.27f, 0xFF),
                hsb(h, Math.min(s, 0.5f), 0.93f, 0xFF), hsb(h, 0.6f, 0.2f, 0xFF),
                hsb(h, 0.04f, 0.94f, 0xFF), hsb(h, 0.08f, 0.78f, 0xFF),
                hsb(h, 0.38f, 0.30f, 0xFF), hsb(h, 0.22f, 0.97f, 0xFF), hsb(h, 0.10f, 0.32f, 0xFF));
        }
    }

    private static int hsb(float h, float s, float b, int alpha) {
        return alpha << 24 | java.awt.Color.HSBtoRGB(h, s, b) & 0xFFFFFF;
    }

    /** How many pixels the row {@code i} (0 = the outermost) of a corner of radius {@code r} is cut in from the edge. */
    static int inset(int i, int r) {
        double d = r - i - 0.5;
        return (int) Math.ceil(r - Math.sqrt(Math.max(0, r * r - d * d)));
    }

    /** A filled box with rounded corners. */
    static void rrect(GuiGraphics g, int x0, int y0, int x1, int y1, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(x1 - x0, y1 - y0) / 2));
        for (int i = 0; i < r; i++) {
            int in = inset(i, r);
            g.fill(x0 + in, y0 + i, x1 - in, y0 + i + 1, color);
            g.fill(x0 + in, y1 - i - 1, x1 - in, y1 - i, color);
        }
        g.fill(x0, y0 + r, x1, y1 - r, color);
    }

    /**
     * The linear progress indicator: a rounded track with the filled part in the primary colour and a small stop dot
     * at the far end. Pass a negative {@code progress} for the indeterminate one, a bar sliding along the track.
     */
    static void linear(GuiGraphics g, Scheme c, int x, int y, int w, int h, double progress, float seconds) {
        rrect(g, x, y, x + w, y + h, h / 2, c.track());
        if (progress >= 0) {
            int fw = (int) Math.round(w * Math.min(1, progress));
            if (fw >= h) rrect(g, x, y, x + fw, y + h, h / 2, c.primary());
            else if (fw > 0) rrect(g, x, y, x + h, y + h, h / 2, c.primary());
            int d = Math.max(1, h / 2);
            g.fill(x + w - d - 1, y + (h - d) / 2, x + w - 1, y + (h - d) / 2 + d, progress >= 1 ? c.onPrimary() : c.primary());
        } else {
            // a bar a third of the track long, eased across it, clipped at both ends
            float t = seconds * 0.9f % 1f;
            float e = t * t * (3 - 2 * t);
            int len = w / 3;
            int a = x - len + Math.round((w + len) * e), b = a + len;
            int a2 = Math.max(a, x), b2 = Math.min(b, x + w);
            if (b2 - a2 >= h) rrect(g, a2, y, b2, y + h, h / 2, c.primary());
            else if (b2 > a2) g.fill(a2, y, b2, y + h, c.primary());
        }
    }

    /** A chip: a small rounded label. Returns its width. */
    static int chip(GuiGraphics g, net.minecraft.client.gui.Font font, Scheme c, int x, int y, String text, int r) {
        int w = font.width(text) + 8;
        rrect(g, x, y, x + w, y + 12, Math.min(r, 6), c.chip());
        g.drawString(font, text, x + 4, y + 2, c.onChip(), false);
        return w;
    }
}
