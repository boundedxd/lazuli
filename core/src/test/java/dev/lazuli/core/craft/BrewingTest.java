package dev.lazuli.core.craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BrewingTest {
    @Test
    void fireResistanceFromWater() {
        Brewing.Route r = Brewing.route("fire_resistance", Brewing.Form.DRINK, Set.of("minecraft:water"));
        assertEquals("minecraft:water", r.from());
        assertEquals(List.of("minecraft:nether_wart", "minecraft:magma_cream"), r.ingredients());
    }

    @Test
    void longAndSplashAddTheirModifiers() {
        Brewing.Route r = Brewing.route("long_fire_resistance", Brewing.Form.SPLASH, Set.of("minecraft:water"));
        assertEquals(List.of("minecraft:nether_wart", "minecraft:magma_cream", "minecraft:redstone", "minecraft:gunpowder"),
            r.ingredients());
        Brewing.Route l = Brewing.route("minecraft:healing", Brewing.Form.LINGERING, Set.of("minecraft:water"));
        assertEquals(List.of("minecraft:nether_wart", "minecraft:glistering_melon_slice", "minecraft:gunpowder",
            "minecraft:dragon_breath"), l.ingredients());
    }

    @Test
    void corruptedPotionsGoThroughTheirBase() {
        Brewing.Route r = Brewing.route("invisibility", Brewing.Form.DRINK, Set.of("minecraft:water"));
        assertEquals(List.of("minecraft:nether_wart", "minecraft:golden_carrot", "minecraft:fermented_spider_eye"), r.ingredients());
        // weakness needs no wart
        assertEquals(List.of("minecraft:fermented_spider_eye"),
            Brewing.route("weakness", Brewing.Form.DRINK, Set.of("minecraft:water")).ingredients());
    }

    @Test
    void startsFromTheClosestPotionItHas() {
        Brewing.Route r = Brewing.route("long_swiftness", Brewing.Form.DRINK, Set.of("minecraft:water", "minecraft:awkward", "minecraft:swiftness"));
        assertEquals("minecraft:swiftness", r.from());
        assertEquals(List.of("minecraft:redstone"), r.ingredients());
    }

    @Test
    void unknownOrUnbrewableIsNull() {
        assertNull(Brewing.route("luck", Brewing.Form.DRINK, Set.of("minecraft:water")));
        assertNull(Brewing.route("nonsense", Brewing.Form.DRINK, Set.of("minecraft:water")));
        assertTrue(Brewing.known("strong_healing"));
        assertFalse(Brewing.known("nonsense"));
    }
}
