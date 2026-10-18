package dev.lazuli.core.craft;

import dev.lazuli.core.craft.ToolChoice.Cand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolChoiceTest {
    // Cand: slot, drops, ticks, durability left
    @Test void toolThatDropsBeatsFasterOneThatDoesNot() {
        List<Cand> c = List.of(new Cand(0, true, 40, 2), new Cand(1, false, 30, 100));
        assertEquals(0, ToolChoice.best(c, 1, true, 3, true));
    }

    @Test void freshSpareBeatsNearlyBrokenSameTier() {
        List<Cand> c = List.of(new Cand(0, true, 20, 2), new Cand(5, true, 20, 200));
        assertEquals(5, ToolChoice.best(c, 0, true, 3, true));
    }

    @Test void wornToolStillUsedWhenItIsTheOnlyOneThatDrops() {
        List<Cand> c = List.of(new Cand(0, true, 20, 2), new Cand(1, false, 20, 100));
        assertEquals(0, ToolChoice.best(c, 1, true, 3, false));
    }

    @Test void noToolThatDropsGivesMinusOneWhenRequired() {
        List<Cand> c = List.of(new Cand(1, false, 30, 100));
        assertEquals(-1, ToolChoice.best(c, 1, true, 3, true));
    }

    @Test void whenNothingNeedsToolFastestWins() {
        List<Cand> c = List.of(new Cand(0, false, 50, 100), new Cand(2, false, 10, 100));
        assertEquals(2, ToolChoice.best(c, 0, false, 3, true));
    }

    @Test void selectedWinsTies() {
        List<Cand> c = List.of(new Cand(0, true, 10, 100), new Cand(3, true, 10, 100));
        assertEquals(3, ToolChoice.best(c, 3, true, 3, true));
    }

    @Test void nearlyBrokenPickDoesNotCountForALongJob() {
        var picks = List.of(new ToolChoice.Pick(3, 10), new ToolChoice.Pick(2, 100));
        assertEquals(2, ToolChoice.pickTier(picks, 50, true));
        assertEquals(3, ToolChoice.pickTier(picks, 5, true));
        assertEquals(3, ToolChoice.pickTier(picks, 50, false));
        assertEquals(0, ToolChoice.pickTier(List.of(new ToolChoice.Pick(3, 2)), 1, true));
    }
}
