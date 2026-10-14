package dev.lazuli.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PosTest {
    @Test
    void roundTripsPositiveNegativeAndExtremeCoordinates() {
        int[][] cases = {{0, 0, 0}, {1, 64, -1}, {-30_000_000, -64, 30_000_000}, {12345, 319, -54321}, {-1, -1, -1}};
        for (int[] c : cases) {
            long p = Pos.pack(c[0], c[1], c[2]);
            assertEquals(c[0], Pos.x(p));
            assertEquals(c[1], Pos.y(p));
            assertEquals(c[2], Pos.z(p));
        }
    }

    @Test
    void offsetMovesEachAxis() {
        assertEquals(Pos.pack(-1, 72, 1), Pos.offset(Pos.pack(5, 70, -3), -6, 2, 4));
    }
}
