package dev.lazuli.core.inventory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemKeysTest {
    @Test
    void aPlainNameMatchesEveryVariant() {
        var m = ItemKeys.matcher("potion");
        assertTrue(m.test("minecraft:potion"));
        assertTrue(m.test("minecraft:potion#123@minecraft:swiftness"));
        assertFalse(m.test("minecraft:splash_potion#5@minecraft:swiftness"));
    }

    @Test
    void aPotionNameMatchesOnlyThatPotion() {
        var m = ItemKeys.matcher(ItemKeys.potion("minecraft:splash_potion", "minecraft:swiftness"));
        assertTrue(m.test("minecraft:splash_potion#5@minecraft:swiftness"));
        assertFalse(m.test("minecraft:splash_potion#5@minecraft:long_swiftness"));
        assertFalse(m.test("minecraft:potion#5@minecraft:swiftness"));
    }

    @Test
    void stripsTheVariantForThePlainId() {
        assertEquals("minecraft:potion", ItemKeys.base("minecraft:potion@minecraft:swiftness"));
        assertEquals("minecraft:dirt", ItemKeys.base("minecraft:dirt"));
    }

    @Test
    void namesWhatsInsideAShulkerBox() {
        String n = ItemKeys.inShulker("diamond");
        assertTrue(ItemKeys.isInShulker(n));
        assertEquals("minecraft:diamond", ItemKeys.shulkerContent(n));
        assertFalse(ItemKeys.isInShulker("minecraft:diamond"));
    }
}
