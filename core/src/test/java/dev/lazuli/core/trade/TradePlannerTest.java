package dev.lazuli.core.trade;

import dev.lazuli.core.inventory.ItemCount;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.lazuli.core.trade.TradePlanner.EMERALD;
import static org.junit.jupiter.api.Assertions.*;

class TradePlannerTest {
    private static final String BOOK = "minecraft:book", EBOOK = "minecraft:enchanted_book";
    private static final double NO = Double.POSITIVE_INFINITY;

    private static Offer sells(int i, int emeralds, String item, int n) {
        return new Offer(i, new ItemCount(EMERALD, emeralds), null, new ItemCount(item, n), null, 0, 12);
    }

    private static Offer book(int i, int emeralds, String ench, int level) {
        return new Offer(i, new ItemCount(EMERALD, emeralds), new ItemCount(BOOK, 1), new ItemCount(EBOOK, 1), ench, level, 12);
    }

    private static Offer buys(int i, String item, int n) {
        return new Offer(i, new ItemCount(item, n), null, new ItemCount(EMERALD, 1), null, 0, 16);
    }

    private static Trader trader(String id, String prof, int x, Offer... offers) {
        return new Trader(id, prof, "overworld", x, 64, 0, List.of(offers));
    }

    private static TradePlanner.Plan plan(List<Want> wants, Map<String, Integer> have, List<Trader> traders, boolean sell,
                                          java.util.function.ToDoubleFunction<String> gather) {
        return TradePlanner.plan(wants, w -> 0, have, traders, 0, 64, 0, sell, gather);
    }

    @Test
    void buysAnEnchantedBookWithEmeraldsItHas() {
        Trader lib = trader("a", "librarian", 10, book(0, 20, "minecraft:sharpness", 5), sells(1, 1, "minecraft:bookshelf", 1));
        var p = plan(List.of(new Want(EBOOK, "minecraft:sharpness", 5, 1)), Map.of(EMERALD, 30, BOOK, 2), List.of(lib), false, i -> NO);
        assertTrue(p.ok(), p.problem());
        assertEquals(1, p.steps().size());
        TradePlanner.Step s = p.steps().get(0);
        assertEquals(TradePlanner.Step.Kind.TRADE, s.kind());
        assertEquals(0, s.offer().index());
        assertEquals(1, s.times());
    }

    @Test
    void aLowerLevelBookDoesntCount() {
        Trader lib = trader("a", "librarian", 10, book(0, 9, "minecraft:sharpness", 3));
        var p = plan(List.of(new Want(EBOOK, "minecraft:sharpness", 5, 1)), Map.of(EMERALD, 30, BOOK, 2), List.of(lib), false, i -> NO);
        assertFalse(p.ok());
    }

    @Test
    void noEmeraldsSellsStringFirstThenGetsTheBook() {
        Trader lib = trader("a", "librarian", 10, book(0, 10, "minecraft:sharpness", 5));
        Trader fisher = trader("f", "fisherman", 20, buys(0, "minecraft:string", 20));
        Trader fletcher = trader("g", "fletcher", 5, buys(0, "minecraft:stick", 32));
        // string costs little (it's in a chest), sticks a lot more
        var p = plan(List.of(new Want(EBOOK, "minecraft:sharpness", 5, 1)), Map.of(BOOK, 1), List.of(lib, fisher, fletcher), true,
            i -> i.equals("minecraft:string") ? 0.2 : i.equals("minecraft:stick") ? 3 : NO);
        assertTrue(p.ok(), p.problem());
        List<TradePlanner.Step> st = p.steps();
        assertEquals(TradePlanner.Step.Kind.GATHER, st.get(0).kind());
        assertEquals(new ItemCount("minecraft:string", 200), st.get(0).item(), "10 emeralds = 10 trades of 20 string");
        assertEquals(10, st.get(1).times());
        assertEquals("f", st.get(1).trader().id());
        assertEquals(TradePlanner.Step.Kind.TRADE, st.get(2).kind());
        assertEquals("a", st.get(2).trader().id(), "the book last");
    }

    @Test
    void sellingOnlyAsMuchAsAVillagerTakesThenTheNext() {
        Trader lib = trader("a", "librarian", 10, book(0, 20, "minecraft:mending", 1));
        Trader fisher = trader("f", "fisherman", 20, buys(0, "minecraft:string", 20)); // 16 uses
        Trader fletcher = trader("g", "fletcher", 5, buys(0, "minecraft:stick", 32));
        var p = plan(List.of(new Want(EBOOK, "minecraft:mending", 1, 1)), Map.of(BOOK, 1), List.of(lib, fisher, fletcher), true,
            i -> i.equals("minecraft:string") ? 0.2 : i.equals("minecraft:stick") ? 3 : NO);
        assertTrue(p.ok(), p.problem());
        int sold = p.steps().stream().filter(s -> s.kind() == TradePlanner.Step.Kind.TRADE && s.offer().result().item().equals(EMERALD))
            .mapToInt(TradePlanner.Step::times).sum();
        assertEquals(20, sold);
        assertTrue(p.steps().stream().anyMatch(s -> s.trader() != null && s.trader().id().equals("g")), "the fletcher for the last 4");
    }

    @Test
    void sellingForEmeraldsOffSaysSo() {
        Trader lib = trader("a", "librarian", 10, book(0, 20, "minecraft:mending", 1));
        var p = plan(List.of(new Want(EBOOK, "minecraft:mending", 1, 1)), Map.of(BOOK, 1, EMERALD, 5), List.of(lib), false, i -> 1);
        assertFalse(p.ok());
        assertTrue(p.problem().contains("15"), p.problem());
    }

    @Test
    void severalWantsShareTheEmeralds() {
        Trader lib = trader("a", "librarian", 10, book(0, 20, "minecraft:mending", 1), book(1, 10, "minecraft:unbreaking", 3));
        var ok = plan(List.of(new Want(EBOOK, "minecraft:mending", 1, 1), new Want(EBOOK, "minecraft:unbreaking", 3, 1)),
            Map.of(BOOK, 2, EMERALD, 30), List.of(lib), false, i -> NO);
        assertTrue(ok.ok(), ok.problem());
        var notEnough = plan(List.of(new Want(EBOOK, "minecraft:mending", 1, 1), new Want(EBOOK, "minecraft:unbreaking", 3, 1)),
            Map.of(BOOK, 2, EMERALD, 25), List.of(lib), false, i -> NO);
        assertFalse(notEnough.ok());
    }

    @Test
    void manyOfAnItemOverSeveralTrades() {
        Trader farmer = trader("c", "farmer", 10, sells(0, 1, "minecraft:bread", 6));
        var p = plan(List.of(new Want("minecraft:bread", null, 0, 20)), Map.of(EMERALD, 10), List.of(farmer), false, i -> NO);
        assertTrue(p.ok(), p.problem());
        assertEquals(4, p.steps().get(0).times());
    }

    @Test
    void parsesEnchantsWithLevelsAndItemsWithCounts() {
        Set<String> enchants = Set.of("minecraft:sharpness", "minecraft:mending");
        List<Want> w = Want.parse(new String[]{"sharpness", "v", "mending", "bread", "20"}, enchants::contains);
        assertEquals(List.of(new Want(EBOOK, "minecraft:sharpness", 5, 1), new Want(EBOOK, "minecraft:mending", 1, 1),
            new Want("minecraft:bread", null, 0, 20)), w);
        assertEquals(List.of(new Want(EBOOK, "minecraft:sharpness", 5, 2)),
            Want.parse(new String[]{"sharpness", "5", "2"}, enchants::contains));
    }

    @Test
    void memoryKeepsTradersInAFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.txt");
        TradeMemory m = new TradeMemory(f);
        m.record(trader("a", "librarian", 10, book(0, 20, "minecraft:sharpness", 5), buys(1, "minecraft:paper", 24)));
        TradeMemory back = new TradeMemory(f);
        assertEquals(1, back.all("overworld").size());
        Trader t = back.get("a");
        assertEquals("librarian", t.profession());
        assertEquals(book(0, 20, "minecraft:sharpness", 5), t.offers().get(0));
        assertEquals(buys(1, "minecraft:paper", 24), t.offers().get(1));
        assertTrue(Files.size(f) > 0);
    }
}
