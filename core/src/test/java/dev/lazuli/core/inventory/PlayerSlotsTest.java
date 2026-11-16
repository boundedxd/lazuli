package dev.lazuli.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerSlotsTest {
    static Menu menu(Stack... hotbar) {
        Stack[] slots = new Stack[46];
        java.util.Arrays.fill(slots, Stack.EMPTY);
        for (int i = 0; i < hotbar.length; i++) slots[36 + i] = hotbar[i];
        return new Menu(slots, slot -> new int[0]);
    }

    @Test
    void theLayoutMatchesTheVanillaPlayerMenu() {
        assertEquals(27, PlayerSlots.MAIN.length);
        assertEquals(9, PlayerSlots.HOTBAR.length);
        assertEquals(9, PlayerSlots.MAIN[0]);
        assertEquals(35, PlayerSlots.MAIN[26]);
        assertEquals(36, PlayerSlots.HOTBAR[0]);
        assertEquals(44, PlayerSlots.HOTBAR[8]);
        assertEquals(36, PlayerSlots.carried().length);
        assertEquals("hotbar", PlayerSlots.zone(40));
        assertEquals("inventory", PlayerSlots.zone(20));
        assertEquals("offhand", PlayerSlots.zone(45));
    }

    @Test
    void aStackOfTheItemIsPreferredThenAnEmptySlot() {
        Menu m = menu(Stack.EMPTY, new Stack("minecraft:iron_pickaxe", 1, 64), Stack.EMPTY);
        assertEquals(37, PlayerSlots.hotbarFor(m, "minecraft:iron_pickaxe"));
        assertEquals(36, PlayerSlots.hotbarFor(m, "minecraft:diamond_pickaxe"));
    }

    @Test
    void noRoomInTheHotbarSaysSoInsteadOfGuessing() {
        Menu full = menu(new Stack("minecraft:stone", 64, 64), new Stack("minecraft:dirt", 64, 64),
            new Stack("minecraft:oak_log", 64, 64), new Stack("minecraft:sand", 64, 64),
            new Stack("minecraft:gravel", 64, 64), new Stack("minecraft:coal", 64, 64),
            new Stack("minecraft:iron_ingot", 64, 64), new Stack("minecraft:gold_ingot", 64, 64),
            new Stack("minecraft:redstone", 64, 64));
        assertEquals(-1, PlayerSlots.hotbarFor(full, "minecraft:diamond_pickaxe"));
    }
}
