package dev.lazuli.core.command;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.goal.GoalYLevel;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandTest {
    private final List<String> replies = new ArrayList<>();
    private int saved;
    private Settings settings;
    private PathingSession session;

    private CommandHandler handler() {
        BlockView world = GridWorld.flatFloor(0, 0, 20, 20);
        settings = new Settings();
        session = new PathingSession(world, () -> -64, () -> 319, settings, Runnable::run, replies::add);
        return new CommandHandler(new CommandHandler.Context() {
            public int[] playerFeet() { return new int[]{0, 1, 0}; }
            public BlockView world() { return world; }
            public PathingSession session() { return session; }
            public Settings settings() { return settings; }
            public void reply(String msg) { replies.add(msg); }
            public void settingsChanged() { saved++; }
        });
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
}
