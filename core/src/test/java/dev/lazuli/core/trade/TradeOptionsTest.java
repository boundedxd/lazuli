package dev.lazuli.core.trade;

import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TradeOptionsTest {
    private static TradeOptions.Parsed parse(String line) {
        return TradeOptions.parse(line.isBlank() ? new String[0] : line.split(" "));
    }

    @Test
    void flagsAreTakenOutAnywhere() {
        var p = parse("sharpness 5 --max 3 bread --only-known --range 40 --with librarian");
        assertEquals(List.of("sharpness", "5", "bread"), p.rest());
        assertEquals(3, p.options().max());
        assertEquals(40, p.options().range());
        assertEquals("librarian", p.options().with());
        assertTrue(p.options().onlyKnown());
        assertNull(p.options().scan());
        assertEquals(TradeOptions.NONE, parse("sharpness 5").options());
        assertFalse(parse("sharpness 5").options().any());
    }

    @Test
    void stayAndNoWalkAreTheSame() {
        assertTrue(parse("--stay").options().stay());
        assertTrue(parse("--no-walk").options().stay());
        assertTrue(parse("--wait-restock --wait-day").options().waitRestock());
        assertTrue(parse("--wait-day").options().waitDay());
    }

    @Test
    void badFlagsSayWhy() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--max")).getMessage().contains("needs a value"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--max x")).getMessage().contains("whole number"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--range 0")).getMessage().contains("1 or more"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--min-rate abc")).getMessage().contains("number"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--nope")).getMessage().contains("Unknown trade flag"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> parse("--with --max 3")).getMessage().contains("needs a value"));
    }

    @Test
    void keepAddsUpWithTheSetting() {
        Settings s = new Settings();
        s.tradeKeepItems.parse("emerald:10");
        var o = parse("--keep diamond 4 --keep bread").options();
        Map<String, Integer> keep = o.keepItems(s);
        assertEquals(10, keep.get("minecraft:emerald"));
        assertEquals(4, keep.get("minecraft:diamond"));
        assertEquals(Integer.MAX_VALUE, keep.get("minecraft:bread"));
        Map<String, Integer> left = o.spendable(s, Map.of("minecraft:emerald", 25, "minecraft:diamond", 3, "minecraft:bread", 9, "minecraft:stick", 7));
        assertEquals(15, left.get("minecraft:emerald"));
        assertEquals(0, left.get("minecraft:diamond"));
        assertEquals(0, left.get("minecraft:bread"));
        assertEquals(7, left.get("minecraft:stick"));
    }

    @Test
    void optionsOverTheSettings() {
        Settings s = new Settings();
        assertTrue(TradeOptions.NONE.mayScan(s));
        assertFalse(TradeOptions.NONE.onlyKnown(s));
        assertFalse(parse("--only-known").options().mayScan(s));
        s.tradeScanUnknown.set(false);
        assertFalse(TradeOptions.NONE.mayScan(s));
        assertTrue(parse("--scan").options().mayScan(s));
        s.tradeOnlyKnown.set(true);
        assertFalse(TradeOptions.NONE.mayScan(s));
        assertTrue(parse("--scan").options().mayScan(s), "--scan beats the only-known setting for this run");
        assertEquals(0, TradeOptions.NONE.maxTrades(s));
        assertEquals(7, parse("--max 7").options().maxTrades(s));
        assertEquals(96, TradeOptions.NONE.range(s));
        assertEquals(30, parse("--range 30").options().range(s));
    }

    @Test
    void waitingForRestocks() {
        Settings s = new Settings();
        assertEquals(10, TradeOptions.NONE.waitMinutes(s, true));
        assertEquals(0, TradeOptions.NONE.waitMinutes(s, false), "selling only waits when asked");
        assertEquals(10, parse("--wait-restock").options().waitMinutes(s, false));
        assertEquals(20, parse("--wait-day").options().waitMinutes(s, false));
        s.tradeRestockMinutes.set(0);
        assertEquals(0, TradeOptions.NONE.waitMinutes(s, true));
        assertEquals(10, parse("--wait-restock").options().waitMinutes(s, true));
    }

    @Test
    void villagerFilterAndRate() {
        var o = parse("--with libr").options();
        assertTrue(o.allows("librarian", "abc"));
        assertFalse(o.allows("farmer", "abc"));
        assertTrue(parse("--with 1234").options().allows("farmer", "1234-abcd"));
        assertTrue(TradeOptions.NONE.allows("farmer", "x"));
        Settings s = new Settings();
        Offer sell = new Offer(0, new ItemCount("minecraft:wheat", 20), null, new ItemCount("minecraft:emerald", 1), null, 0, 5);
        assertTrue(TradeOptions.NONE.rateOk(s, sell));
        assertFalse(parse("--min-rate 0.5").options().rateOk(s, sell));
        assertTrue(parse("--min-rate 0.05").options().rateOk(s, sell));
    }

    @Test
    void bareWordsWorkLikeDashedOnes() {
        var p = parse("sharpness 5 max 3 bread only-known range 40 with librarian");
        assertEquals(parse("sharpness 5 --max 3 bread --only-known --range 40 --with librarian").options(), p.options());
        assertEquals(java.util.List.of("sharpness", "5", "bread"), p.rest());
    }
}
