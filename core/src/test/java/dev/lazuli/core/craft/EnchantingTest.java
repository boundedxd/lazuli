package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnchantingTest {
    @Test
    void picksTheBestOptionItCanPayFor() {
        int[] costs = {5, 12, 30};
        assertEquals(2, Enchanting.pick(costs, 30, 3, -1));
        assertEquals(1, Enchanting.pick(costs, 29, 3, -1), "not enough levels for the last");
        assertEquals(0, Enchanting.pick(costs, 29, 1, -1), "one lapis: the first only");
        assertEquals(-1, Enchanting.pick(costs, 4, 3, -1));
        assertEquals(-1, Enchanting.pick(new int[]{0, 0, 0}, 30, 3, -1), "nothing offered (no item yet)");
    }

    @Test
    void aChosenOptionOrNothing() {
        int[] costs = {5, 12, 30};
        assertEquals(1, Enchanting.pick(costs, 30, 3, 1));
        assertEquals(-1, Enchanting.pick(costs, 20, 3, 2));
    }

    @Test
    void repairMaterialsAreQuarters() {
        assertEquals(0, Enchanting.repairMaterials(0, 250));
        assertEquals(1, Enchanting.repairMaterials(62, 250));
        assertEquals(2, Enchanting.repairMaterials(63, 250));
        assertEquals(5, Enchanting.repairMaterials(249, 250), "62 a unit: 249, 187, 125, 63, 1");
    }
}
