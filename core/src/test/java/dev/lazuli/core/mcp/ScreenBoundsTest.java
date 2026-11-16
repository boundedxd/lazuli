package dev.lazuli.core.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenBoundsTest {
    @Test void clipsAnOffscreenButtonToTheScrollViewport() {
        var viewport = new ScreenBounds(10, 50, 210, 150);
        var clipped = viewport.clip(new ScreenBounds(20, 140, 180, 170));
        assertTrue(clipped.visible());
        assertEquals(145.0, clipped.centerY());
        assertEquals(100.0, clipped.centerX());
    }
    @Test void hiddenOrEdgeTouchingButtonsCannotBeClicked() {
        var viewport = new ScreenBounds(10, 50, 210, 150);
        assertFalse(viewport.clip(new ScreenBounds(20, 150, 180, 170)).visible());
        assertFalse(viewport.clip(new ScreenBounds(20, 0, 180, 20)).visible());
        assertFalse(viewport.clip(new ScreenBounds(211, 60, 250, 80)).visible());
    }
    @Test void nestedClipKeepsTheMostRestrictiveBounds() {
        var screen = new ScreenBounds(0, 0, 300, 200);
        var list = screen.clip(new ScreenBounds(10, 50, 200, 180));
        var row = list.clip(new ScreenBounds(0, 20, 250, 100));
        assertEquals(new ScreenBounds(10, 50, 200, 100), row);
        assertEquals(row, row.clip(screen));
    }
}
