package dev.lazuli.core.settings;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Test
    void defaults() {
        Settings s = new Settings();
        assertTrue(s.allowSprint.get());
        assertEquals(3, s.maxFallHeight.get());
        assertEquals("#", s.prefix.get());
    }

    @Test
    void parsesAndRejects() {
        Settings s = new Settings();
        s.byName("MAXFALLHEIGHT").orElseThrow().parse("5");
        assertEquals(5, s.maxFallHeight.get());
        assertThrows(IllegalArgumentException.class, () -> s.maxFallHeight.parse("99"));
        assertThrows(IllegalArgumentException.class, () -> s.allowSprint.parse("maybe"));
        assertThrows(IllegalArgumentException.class, () -> s.prefix.parse("a b"));
        assertEquals(5, s.maxFallHeight.get());
    }

    @Test
    void roundTripsThroughMapAndIgnoresJunk() {
        Settings a = new Settings();
        a.rotationSpeed.parse("12.5");
        a.allowSprint.parse("false");
        Map<String, String> m = new HashMap<>(a.toMap());
        m.put("nope", "1");
        m.put("evalHorizon", "x");
        Settings b = new Settings();
        b.load(m);
        assertEquals(12.5, b.rotationSpeed.get());
        assertFalse(b.allowSprint.get());
        assertEquals(32, b.evalHorizon.get());
    }

    @Test
    void restockListIsTheToggledThingsPlusOtherItems() {
        Settings s = new Settings();
        assertEquals("food:16,pickaxe:1,torch:16,blocks:64", s.restockSpec());
        s.restockTorch.set(false);
        s.restockBlocks.set(false);
        s.restockSword.set(true);
        // an old saved list still naming torches and blocks doesn't bring them back
        s.restockItems.parse("food:16,pickaxe:1,torch:16,blocks:64,arrow:32");
        assertEquals("food:16,pickaxe:1,sword:1,arrow:32", s.restockSpec());
    }
}
