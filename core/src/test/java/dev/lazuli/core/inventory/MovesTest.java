package dev.lazuli.core.inventory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MovesTest {
    static final int[] CHEST = Menu.range(0, 27), PLAYER = Menu.range(27, 63);

    /** A single chest: shift-click sends chest items to the player (hotbar end first), player items into the chest. */
    static Menu chest(Object... contents) {
        Stack[] s = new Stack[63];
        Arrays.fill(s, Stack.EMPTY);
        for (int i = 0; i < contents.length; i += 3) s[(int) contents[i]] = new Stack((String) contents[i + 1], (int) contents[i + 2], 64);
        int[] toPlayer = Menu.range(27, 63);
        for (int i = 0; i < toPlayer.length / 2; i++) {
            int t = toPlayer[i];
            toPlayer[i] = toPlayer[toPlayer.length - 1 - i];
            toPlayer[toPlayer.length - 1 - i] = t;
        }
        return new Menu(s, slot -> slot < 27 ? toPlayer : CHEST);
    }

    @Test
    void takesExactlyTheNumberAsked() {
        Menu m = chest(0, "iron", 3, 1, "iron", 4);
        List<Click> clicks = Moves.transfer(m, CHEST, PLAYER, "iron"::equals, 5);
        m.applyAll(clicks);
        assertEquals(5, m.count(PLAYER, "iron"::equals), m.toString());
        assertEquals(2, m.count(CHEST, "iron"::equals), m.toString());
        assertTrue(m.cursor().empty(), m.toString());
    }

    @Test
    void putsAllOfAnItemAway() {
        Menu m = chest(40, "cobblestone", 64, 41, "cobblestone", 20, 42, "dirt", 5);
        m.applyAll(Moves.transfer(m, PLAYER, CHEST, "cobblestone"::equals, -1));
        assertEquals(84, m.count(CHEST, "cobblestone"::equals), m.toString());
        assertEquals(0, m.count(PLAYER, "cobblestone"::equals), m.toString());
        assertEquals(5, m.count(PLAYER, "dirt"::equals), m.toString());
    }

    @Test
    void movesWhatFitsIntoAFullChest() {
        Object[] c = new Object[28 * 3];
        for (int i = 0; i < 26; i++) {
            c[i * 3] = i;
            c[i * 3 + 1] = "stone";
            c[i * 3 + 2] = 64;
        }
        c[78] = 26;
        c[79] = "dirt";
        c[80] = 60;
        c[81] = 50;
        c[82] = "dirt";
        c[83] = 10;
        Menu m = chest(c);
        m.applyAll(Moves.transfer(m, PLAYER, CHEST, "dirt"::equals, -1));
        assertEquals(64, m.count(CHEST, "dirt"::equals), m.toString());
        assertEquals(6, m.count(PLAYER, "dirt"::equals), m.toString());
        assertTrue(m.cursor().empty());
    }

    @Test
    void dropsAnExactNumber() {
        Menu m = chest(30, "dirt", 64, 31, "dirt", 10);
        List<Click> clicks = Moves.drop(m, PLAYER, "dirt"::equals, 70);
        m.applyAll(clicks);
        assertEquals(70, m.dropped());
        assertEquals(4, m.count(PLAYER, "dirt"::equals));
    }

    @Test
    void dropsEverything() {
        Menu m = chest(30, "dirt", 64, 31, "dirt", 10, 32, "stone", 1);
        m.applyAll(Moves.drop(m, PLAYER, "dirt"::equals, -1));
        assertEquals(0, m.count(PLAYER, "dirt"::equals));
        assertEquals(1, m.count(PLAYER, "stone"::equals));
    }

    @Test
    void quickPutUsesOneShiftClickForAWholeStack() {
        Menu m = chest(30, "ingot", 8, 31, "ingot", 3);
        List<Click> clicks = Moves.put(m, PLAYER, 5, "ingot", 8, Moves.Quick.EXACT);
        assertEquals(List.of(Click.shift(30)), clicks); // (the real game routes it to the slot; the test menu doesn't)
    }

    @Test
    void quickPutFallsBackToPickupsForAPartialStack() {
        Menu m = chest(30, "ingot", 20);
        List<Click> clicks = Moves.put(m, PLAYER, 5, "ingot", 8, Moves.Quick.EXACT);
        assertTrue(clicks.stream().allMatch(c -> c.kind() == Click.Kind.PICKUP), clicks.toString());
        m.applyAll(clicks);
        assertEquals(8, m.get(5).count());
        assertEquals(12, m.get(30).count());
        assertTrue(m.cursor().empty());
    }

    @Test
    void quickPutPicksTheBiggestStackFirstAndOnlyShiftsIfItFits() {
        Menu m = chest(30, "ingot", 5, 31, "ingot", 20);
        List<Click> clicks = Moves.put(m, PLAYER, 5, "ingot", 8, Moves.Quick.EXACT);
        assertEquals(Click.left(31), clicks.get(0)); // 20 is more than the 8 needed, so no shift
    }

    @Test
    void overfillShiftsABiggerStackWhenTheSlotCanHoldIt() {
        Menu m = chest(30, "lapis", 40);
        List<Click> clicks = Moves.put(m, PLAYER, 5, "lapis", 3, Moves.Quick.OVERFILL);
        assertEquals(List.of(Click.shift(30)), clicks);
    }

    @Test
    void quickPutDoesNotShiftOntoADifferentItem() {
        Menu m = chest(5, "other", 1, 30, "ingot", 1);
        assertThrows(IllegalStateException.class, () -> Moves.put(m, PLAYER, 5, "ingot", 1, Moves.Quick.EXACT));
    }
}
