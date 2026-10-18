package dev.lazuli.core.task;

import dev.lazuli.core.trade.TradeForm;
import dev.lazuli.core.trade.TradeOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandFormsTest {
    @Test
    void tradeFormReadsAndWritesTheBareWords() {
        String text = "sell string 64 @fisher with fisherman only-known no-scan max 64 range 20 min-rate 1.5 keep emerald 10 keep diamond wait-day walk";
        TradeForm f = TradeForm.parse(text);
        assertNotNull(f);
        assertTrue(f.sell);
        assertEquals("string", f.item);
        assertEquals(64, f.count);
        assertEquals("fisher", f.target);
        assertEquals("fisherman", f.with);
        assertEquals(Boolean.TRUE, f.onlyKnown);
        assertEquals(Boolean.FALSE, f.scan);
        assertNull(f.waitRestock);
        assertEquals(Boolean.TRUE, f.waitDay);
        assertEquals(Boolean.FALSE, f.stay);
        assertEquals(64, f.max);
        assertEquals(20, f.range);
        assertEquals(1.5, f.minRate);
        assertEquals(2, f.keep.size());
        assertEquals(-1, f.keep.get(1).count());
        assertEquals(text, f.text());
    }

    @Test
    void dashedWordsAreReadAndWrittenBare() {
        TradeForm f = TradeForm.parse("sharpness 5 --with librarian --only-known --max 3");
        assertEquals("sharpness 5 with librarian only-known max 3", f.text());
        assertEquals("", TradeForm.parse("").text());
    }

    @Test
    void otherTradeShapesStayText() {
        assertNull(TradeForm.parse("list"));
        assertNull(TradeForm.parse("scan"));
        assertNull(TradeForm.parse("sharpness 5 mending"));
        assertNull(TradeForm.parse("string max"));
    }

    @Test
    void tradeOptionsAcceptExplicitOff() {
        var p = TradeOptions.parse("string no-scan no-only-known walk no-wait-day".split(" "));
        assertEquals(Boolean.FALSE, p.options().scan());
        assertEquals(Boolean.FALSE, p.options().onlyKnown());
        assertEquals(Boolean.FALSE, p.options().stay());
        assertEquals(Boolean.FALSE, p.options().waitDay());
        assertEquals(java.util.List.of("string"), p.rest());
    }

    @Test
    void stationAtAndGetitemTailsSplitOff() {
        Forms.Extras e = Forms.splitExtras("smelt", "raw_iron 8 at 10 64 ~");
        assertEquals("raw_iron 8", e.rest());
        assertArrayEquals(new String[]{"10", "64", "~"}, e.at());
        assertEquals("raw_iron 8 at 10 64 ~", Forms.joinExtras(e));
        assertNull(Forms.splitExtras("smelt", "raw_iron 8").at());
        e = Forms.splitExtras("getitem", "string 64 then back deposit dry");
        assertEquals("string 64", e.rest());
        assertTrue(e.dry() && e.deposit());
        assertEquals("back", e.then());
        assertEquals("string 64 then back deposit dry", Forms.joinExtras(e));
        // "at" is only a tail for stations
        assertEquals("a at 1 2 3", Forms.splitExtras("getitem", "a at 1 2 3").rest());
        assertFalse(Forms.splitExtras("getitem", "dry").dry(), "a lone word is the item");
    }
}
