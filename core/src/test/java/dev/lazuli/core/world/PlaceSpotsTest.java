package dev.lazuli.core.world;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlaceSpotsTest {
    /** Solid everywhere except the dug-out cells. */
    static final class Dug implements PlaceSpots.Probe {
        final Set<List<Integer>> air = new HashSet<>();

        Dug dig(int x, int y, int z) {
            air.add(List.of(x, y, z));
            return this;
        }

        @Override
        public boolean free(int x, int y, int z) {
            return air.contains(List.of(x, y, z));
        }

        @Override
        public boolean support(int x, int y, int z) {
            return !free(x, y, z);
        }
    }

    @Test
    void openFloorPlacesOnTopOfTheGround() {
        Dug w = new Dug();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 3; y++) w.dig(x, y, z);
        PlaceSpots.Spot s = PlaceSpots.around(w, 0, 0, 0).get(0);
        assertEquals(-1, s.againstY() - s.y(), "against the block below: " + s);
        assertFalse(s.x() == 0 && s.z() == 0, "not where the player stands: " + s);
    }

    @Test
    void dugInWithOneSideOpenOverADrop() {
        // a 1x2 nook (walls left, right, back, ceiling, floor), open to the east where the floor falls away
        Dug w = new Dug().dig(0, 0, 0).dig(0, 1, 0);
        for (int y = -5; y <= 1; y++) w.dig(1, y, 0);
        List<PlaceSpots.Spot> spots = PlaceSpots.around(w, 0, 0, 0);
        assertFalse(spots.isEmpty());
        PlaceSpots.Spot s = spots.get(0);
        assertEquals(1, s.x());
        assertTrue(s.y() == 0 || s.y() == 1, s.toString());
        assertTrue(w.support(s.againstX(), s.againstY(), s.againstZ()), "against a wall: " + s);
    }

    @Test
    void fullyBoxedInHasNoSpotSoItMoves() {
        Dug w = new Dug().dig(0, 0, 0).dig(0, 1, 0);
        assertTrue(PlaceSpots.around(w, 0, 0, 0).isEmpty());
        // a corridor to the east with a roomier spot at its end
        for (int x = 1; x <= 4; x++) w.dig(x, 0, 0).dig(x, 1, 0);
        List<int[]> stands = PlaceSpots.standsNear(w, 0, 0, 0, 6);
        assertFalse(stands.isEmpty());
        int[] first = stands.get(0);
        assertNotEquals(0, first[0], "somewhere else");
        assertFalse(PlaceSpots.around(w, first[0], first[1], first[2]).isEmpty());
    }
}
