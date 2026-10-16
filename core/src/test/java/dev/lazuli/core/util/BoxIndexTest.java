package dev.lazuli.core.util;

import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BoxIndexTest {
    @Test
    void agreesWithCheckingEveryBox() {
        Random r = new Random(7);
        List<int[]> boxes = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            int x = r.nextInt(400) - 200, y = r.nextInt(300) - 64, z = r.nextInt(400) - 200;
            int w = r.nextInt(20), h = r.nextInt(10), d = r.nextInt(20);
            boxes.add(new int[]{x, y, z, x + w, y + h, z + d});
        }
        boxes.add(new int[]{-3000, -64, -3000, 3000, 0, 3000}); // one huge box
        BoxIndex idx = new BoxIndex(boxes);
        for (int i = 0; i < 200_000; i++) {
            int x = r.nextInt(500) - 250, y = r.nextInt(400) - 64, z = r.nextInt(500) - 250;
            boolean slow = false;
            for (int[] b : boxes) slow |= x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5];
            assertEquals(slow, idx.contains(Pos.pack(x, y, z)), x + " " + y + " " + z);
        }
    }

    @Test
    void negativeCoordinatesLandInTheRightChunk() {
        BoxIndex idx = new BoxIndex(List.of(new int[]{-17, 60, -1, -16, 61, 0}));
        assertTrue(idx.contains(Pos.pack(-17, 60, -1)));
        assertTrue(idx.contains(Pos.pack(-16, 61, 0)));
        assertFalse(idx.contains(Pos.pack(-18, 60, -1)));
        assertFalse(idx.contains(Pos.pack(-16, 62, 0)));
    }
}
