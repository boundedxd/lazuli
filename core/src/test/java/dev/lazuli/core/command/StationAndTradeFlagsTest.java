package dev.lazuli.core.command;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.Brewing;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.trade.TradeOptions;
import dev.lazuli.core.trade.Want;
import dev.lazuli.core.world.BlockView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@code at x y z} on the work-block commands and the {@code #trade --flags}. */
class StationAndTradeFlagsTest {
    private final List<String> replies = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private Settings settings;

    private CommandHandler handler() {
        BlockView world = GridWorld.flatFloor(0, 0, 20, 20);
        settings = new Settings();
        PathingSession session = new PathingSession(world, () -> -64, () -> 319, settings, Runnable::run, m -> replies.add(Chat.strip(m)));
        return new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{10, 64, -3}; }
            public BlockView world() { return world; }
            public PathingSession session() { return session; }
            public Settings settings() { return settings; }
            public void reply(String msg) { replies.add(Chat.strip(msg)); }
            public void settingsChanged() {}
            public void stationAt(int[] at) { calls.add("at " + (at == null ? "nearest" : Arrays.toString(at))); }
            public void craft(List<ItemCount> items) { calls.add("craft " + items); }
            public void smelt(ItemCount item) { calls.add("smelt " + item); }
            public void brew(String potion, Brewing.Form form, int count) { calls.add("brew " + potion + " " + form + " " + count); }
            public void enchant(String item, int count, int option) { calls.add("enchant " + item + " " + count + " " + option); }
            public void anvil(String item, String with) { calls.add("anvil " + item + " " + with); }
            public void smith(ItemCount item) { calls.add("smith " + item); }
            public boolean isEnchantment(String id) { return id.equals("minecraft:sharpness"); }
            public void trade(List<Want> wants, TradeOptions o) { calls.add("trade " + wants.size() + " " + o); }
            public void tradeSell(String item, int count, TradeOptions o) { calls.add("sell " + item + " " + count + " " + o.max()); }
            public void tradeScan(TradeOptions o) { calls.add("scan " + o.range()); }
        });
    }

    @Test
    void atPicksTheStation() {
        CommandHandler h = handler();
        h.handle("#smelt iron_ingot 8 at 5 70 -2");
        h.handle("#brew fire_resistance 3 splash at ~ ~1 ~-4");
        h.handle("#craft stick 4 at 1 2 3");
        h.handle("#enchant diamond_sword 1 2 at 0 0 0");
        h.handle("#repair iron_pickaxe at 9 9 9");
        h.handle("#combine iron_pickaxe enchanted_book at 9 9 9");
        h.handle("#smith netherite_pickaxe at 8 8 8");
        assertEquals(List.of(
            "at [5, 70, -2]", "smelt iron_ingot 8",
            "at [10, 65, -7]", "brew fire_resistance SPLASH 3",
            "at [1, 2, 3]", "craft [stick 4]",
            "at [0, 0, 0]", "enchant diamond_sword 1 2",
            "at [9, 9, 9]", "anvil iron_pickaxe null",
            "at [9, 9, 9]", "anvil iron_pickaxe enchanted_book",
            "at [8, 8, 8]", "smith netherite_pickaxe 1"), calls);
    }

    @Test
    void withoutAtTheNearestIsUsed() {
        CommandHandler h = handler();
        h.handle("#smelt iron_ingot 8");
        assertEquals(List.of("at nearest", "smelt iron_ingot 8"), calls);
    }

    @Test
    void badAtIsRefused() {
        CommandHandler h = handler();
        h.handle("#smelt iron_ingot at 1 2");
        h.handle("#smelt iron_ingot at 1 two 3");
        h.handle("#smelt iron_ingot at 1 2 3 4");
        assertEquals(List.of(), calls);
        assertEquals(3, replies.stream().filter(r -> r.startsWith("Usage: ... at")).count(), replies.toString());
    }

    @Test
    void tradeFlagsReachTheRun() {
        CommandHandler h = handler();
        h.handle("#trade sharpness 5 --only-known --max 4 --wait-restock");
        h.handle("#trade sell wheat all --max 9");
        h.handle("#trade --range 40 scan");
        assertEquals(3, calls.size(), replies.toString());
        assertTrue(calls.get(0).startsWith("trade 1 TradeOptions[with=null, onlyKnown=true, scan=null, max=4"), calls.get(0));
        assertTrue(calls.get(0).contains("waitRestock=true"));
        assertEquals("sell minecraft:wheat -1 9", calls.get(1));
        assertEquals("scan 40", calls.get(2));
    }

    @Test
    void badTradeFlagsAreExplained() {
        CommandHandler h = handler();
        h.handle("#trade sharpness --max");
        h.handle("#trade sharpness --bogus");
        assertEquals(List.of(), calls);
        assertTrue(replies.get(0).contains("--max needs a value"), replies.toString());
        assertTrue(replies.get(1).contains("Unknown trade flag --bogus"), replies.toString());
    }
}
