package dev.lazuli.core.area;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectionTest {
    @Test
    void cornersInAnyOrder() {
        var b = new Selection.Box(5, 70, -2, 3, 64, 1);
        assertEquals(3, b.minX());
        assertEquals(5, b.maxX());
        assertEquals(64, b.minY());
        assertEquals(3 * 7 * 4, b.volume());
        assertTrue(b.contains(4, 64, 0));
        assertFalse(b.contains(4, 63, 0));
    }

    @Test
    void clearingGoesTopDownAndFillingBottomUp() {
        var b = new Selection.Box(0, 0, 0, 1, 2, 1);
        List<int[]> clear = b.order(true, 0, 5, 0);
        assertEquals(2, clear.get(0)[1]);
        assertEquals(0, clear.get(clear.size() - 1)[1]);
        List<int[]> fill = b.order(false, 0, 5, 0);
        assertEquals(0, fill.get(0)[1]);
        assertEquals(2, fill.get(fill.size() - 1)[1]);
        assertEquals(12, fill.size());
    }

    @Test
    void withinALayerTheNearestFirst() {
        var b = new Selection.Box(0, 0, 0, 9, 0, 0);
        List<int[]> o = b.order(false, 9, 1, 0);
        assertEquals(9, o.get(0)[0]);
        assertEquals(0, o.get(o.size() - 1)[0]);
    }
}
