package dev.lazuli.core.command;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.goal.GoalYLevel;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandTest {
    private final List<String> replies = new ArrayList<>();
    private int saved;
    private final List<String> mined = new ArrayList<>();
    private Settings settings;
    private PathingSession session;

    private CommandHandler handler() {
        BlockView world = GridWorld.flatFloor(0, 0, 20, 20);
        settings = new Settings();
        session = new PathingSession(world, () -> -64, () -> 319, settings, Runnable::run, m -> replies.add(Chat.strip(m)));
        return new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{0, 1, 0}; }
            public BlockView world() { return world; }
            public PathingSession session() { return session; }
            public Settings settings() { return settings; }
            public void reply(String msg) { replies.add(Chat.strip(msg)); }
            public void settingsChanged() { saved++; }
            public void mine(int quantity, List<String> blocks) {
                mined.add(quantity + " " + blocks);
            }
            public void follow(String mode, List<String> args) {
                mined.add("follow " + mode + " " + args);
            }
            public void explore(int[] origin) {
                mined.add("explore " + (origin == null ? "here" : origin[0] + " " + origin[1]));
            }
            public void tunnel(int h, int w, int d) {
                mined.add("tunnel " + h + " " + w + " " + d);
            }
            public void farm(int range) {
                mined.add("farm " + range);
            }
            public void container(boolean deposit, List<ItemCount> items, int[] at) {
                mined.add((deposit ? "deposit " : "withdraw ") + items + (at == null ? "" : " at " + at[0] + " " + at[1] + " " + at[2]));
            }
            public void drop(List<ItemCount> items) {
                mined.add("drop " + items);
            }
            public int[] cameraFeet() { return new int[]{12, 1, 7}; }
            public void toggleFreecam() { mined.add("freecam"); }
        });
    }

    @Test
    void comeGoesToTheCameraAndFreecamToggles() {
        CommandHandler h = handler();
        h.handle("#come");
        assertEquals(PathingSession.State.PATHING, session.snapshot().state(), replies.toString());
        assertEquals(Pos.pack(12, 1, 7), session.snapshot().path().get(session.snapshot().path().size() - 1));
        h.handle("#freecam");
        assertEquals(List.of("freecam"), mined);
    }

    @Test
    void itemCommandsTakeCountsAndPlaces() {
        CommandHandler h = handler();
        for (String c : new String[]{"#deposit all", "#deposit Cobblestone 32", "#withdraw iron_ingot 5 10 64 ~-2",
            "#withdraw diamond ~ ~1 ~", "#drop dirt", "#drop dirt 3"}) h.handle(c);
        h.handle("#deposit");
        assertEquals(List.of("deposit [all -1]", "deposit [cobblestone 32]", "withdraw [iron_ingot 5] at 10 64 -2",
            "withdraw [diamond -1] at 0 2 0", "drop [dirt -1]", "drop [dirt 3]"), mined);
        assertTrue(replies.get(replies.size() - 1).startsWith("Usage: deposit"), replies.toString());
    }

    @Test
    void itemCommandsTakeSeveralItems() {
        CommandHandler h = handler();
        for (String c : new String[]{"#drop chest 1 oak_log 3", "#drop dirt sand 2 gravel", "#deposit dirt 5 cobblestone 10 64 ~-2",
            "#withdraw iron_ingot 3 Gold_Ingot 1 2 3"}) h.handle(c);
        assertEquals(List.of("drop [chest 1, oak_log 3]", "drop [dirt -1, sand 2, gravel -1]",
            "deposit [dirt 5, cobblestone -1] at 10 64 -2", "withdraw [iron_ingot 3, gold_ingot -1] at 1 2 3"), mined);
        h.handle("#drop 5 dirt");
        h.handle("#drop");
        assertEquals(2, replies.size(), replies.toString());
    }

    @Test
    void mineTakesBaritonesArguments() {
        CommandHandler h = handler();
        h.handle("#mine diamond_ore");
        h.handle("#mine 64 iron_ore minecraft:Coal_Ore");
        h.handle("#mine 5");
        assertEquals(List.of("0 [diamond_ore]", "64 [iron_ore, minecraft:coal_ore]"), mined);
        assertTrue(replies.get(0).startsWith("Usage: mine"), replies.toString());
    }

    @Test
    void processCommandsTakeBaritonesArguments() {
        CommandHandler h = handler();
        for (String c : new String[]{"#follow players", "#follow entities", "#follow player Steve Alex", "#follow entity Zombie",
            "#follow Notch", "#explore", "#explore 100 -200", "#tunnel", "#tunnel 3 3 50", "#farm", "#farm 16"}) h.handle(c);
        assertEquals(List.of("follow players []", "follow entities []", "follow player [steve, alex]", "follow entity [zombie]",
            "follow player [Notch]", "explore here", "explore 100 -200", "tunnel 2 1 0", "tunnel 3 3 50", "farm 0", "farm 16"), mined);
        h.handle("#tunnel 0 1 5");
        h.handle("#follow player");
        h.handle("#explore x 1");
        assertEquals(3, replies.size(), replies.toString());
    }

    @Test
    void repliesAreColoured() {
        List<String> raw = new ArrayList<>();
        Settings st = new Settings();
        CommandHandler h = new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{0, 1, 0}; }
            public BlockView world() { return GridWorld.flatFloor(0, 0, 4, 4); }
            public PathingSession session() { return null; }
            public Settings settings() { return st; }
            public void reply(String msg) { raw.add(msg); }
            public void settingsChanged() {}
        });
        h.handle("#bogus");
        h.handle("#set allowBreak");
        assertTrue(raw.get(0).startsWith(Chat.RED), raw.get(0));
        assertEquals("Unknown command: bogus. Try #help", Chat.strip(raw.get(0)));
        assertTrue(raw.get(1).contains(Chat.GREEN + "true"), raw.get(1));
    }

    @Test
    void parserForms() {
        assertEquals(new GoalBlock(1, 2, 3), GoalParser.parse(new String[]{"1", "2", "3"}, 0, 0, 0).goal());
        assertEquals(new GoalXZ(1, -3), GoalParser.parse(new String[]{"1", "-3"}, 0, 0, 0).goal());
        assertEquals(new GoalYLevel(12), GoalParser.parse(new String[]{"12"}, 0, 0, 0).goal());
        assertEquals(new GoalBlock(5, 6, 7), GoalParser.parse(new String[]{}, 5, 6, 7).goal());
        assertNull(GoalParser.parse(new String[]{"clear"}, 0, 0, 0).goal());
    }

    @Test
    void parserRelativeAndFallback() {
        GoalParser.ParsedGoal p = GoalParser.parse(new String[]{"~", "~-2", "~10", "TRUE"}, 100, 64, -5);
        assertEquals(new GoalBlock(100, 62, 5), p.goal());
        assertTrue(p.fallback());
        assertFalse(GoalParser.parse(new String[]{"1", "2", "3", "false"}, 0, 0, 0).fallback());
    }

    @Test
    void parserGarbage() {
        assertThrows(IllegalArgumentException.class, () -> GoalParser.parse(new String[]{"a", "b", "c"}, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> GoalParser.parse(new String[]{"1", "2", "3", "4"}, 0, 0, 0));
    }

    @Test
    void nonCommandsPassThrough() {
        assertFalse(handler().handle("hello"));
    }

    @Test
    void gotoStartsPathing() {
        assertTrue(handler().handle("#goto 10 1 0"));
        assertEquals(PathingSession.State.PATHING, session.snapshot().state());
    }

    @Test
    void pathWithoutGoal() {
        handler().handle("#path");
        assertTrue(replies.contains("No goal set"));
    }

    @Test
    void rejectsUnstandableExactGoal() {
        CommandHandler h = handler();
        settings.allowBreak.set(false);
        settings.allowPlace.set(false);
        h.handle("#goal 5 0 5");
        assertTrue(replies.get(0).startsWith("Can't stand at"));
    }

    @Test
    void acceptsAirGoalWhenItCanBuild() {
        handler().handle("#goal 5 4 5");
        assertTrue(replies.get(0).startsWith("Goal set"), replies.toString());
    }

    @Test
    void badGoalArgsReplyInsteadOfThrowing() {
        assertTrue(handler().handle("#goal a b c"));
        assertEquals("Not a coordinate: a", replies.get(0));
    }

    @Test
    void setPersists() {
        handler().handle("#set maxFallHeight 5");
        assertEquals(5, settings.maxFallHeight.get());
        assertEquals(1, saved);
    }

    @Test
    void customPrefix() {
        CommandHandler h = handler();
        h.handle("#set prefix !");
        assertTrue(h.handle("!stop"));
        assertFalse(h.handle("#stop"));
    }

    @Test
    void clickSetsTheGoalFromTheGame() {
        CommandHandler h = handler();
        assertTrue(h.handle("#click"));
        assertEquals("Clicking isn't available here", replies.get(0));
        h.setGoal(new GoalBlock(3, 1, 4));
        assertEquals(new GoalBlock(3, 1, 4), h.goal());
        assertTrue(h.handle("#path"));
        assertEquals(PathingSession.State.PATHING, session.snapshot().state());
    }
}
