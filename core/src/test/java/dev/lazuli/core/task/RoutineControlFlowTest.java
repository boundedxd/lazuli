package dev.lazuli.core.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RoutineControlFlowTest {
    private static final class W implements Cond.World {
        Map<String, Integer> inv = Map.of();
        int free = 10, xp = 0, y = 64, players = 0, hostiles = 0, time = 6000;
        double health = 20, armor = 100;
        boolean night, rain, thunder;
        String dim = "overworld";
        String holding = "", effect = "";

        public int items(String i) { return inv.getOrDefault(i, 0); }
        public int chestItems(String i) { return 0; }
        public int freeSlots() { return free; }
        public double health() { return health; }
        public int hunger() { return 20; }
        public boolean night() { return night; }
        public String dimension() { return dim; }
        public boolean busy() { return false; }
        public double armorDurability() { return armor; }
        public int xpLevel() { return xp; }
        public int timeOfDay() { return time; }
        public boolean raining() { return rain; }
        public boolean thundering() { return thunder; }
        public int y() { return y; }
        public int playersNearby() { return players; }
        public int hostilesNearby() { return hostiles; }
        public boolean holding(String i) { return holding.equals(i); }
        public boolean hasEffect(String n) { return effect.equals(n); }
        public double distanceTo(String where) { return where.equals("@home") ? 12 : 1e9; }
    }

    private static final class H implements RoutineRunner.Host {
        final List<String> ran = new ArrayList<>(), said = new ArrayList<>();
        final W world = new W();
        boolean failAll;

        public boolean run(String line) {
            ran.add(line);
            return !failAll;
        }

        public Cond.World world() { return world; }
        public void say(String t) { said.add(t); }
    }

    private static String drive(RoutineRunner r, H h, int max) {
        for (int t = 0; t < max; t++) {
            String m = r.tick(false, false, h);
            if (m != null) return m;
        }
        return null;
    }

    private static List<String> run(H h, String... lines) {
        RoutineRunner r = new RoutineRunner();
        r.start("t", List.of(lines), 1);
        assertEquals("t done", drive(r, h, 100000), String.valueOf(h.ran));
        return h.ran;
    }

    // ---- parsing ---------------------------------------------------------------------------------------------

    @Test
    void blockStepsRoundTripAsText() {
        for (String l : new String[]{"if inv.free>4", "else", "else if night and health<5", "while inv.free>0", "repeat 3",
            "break", "continue", "set count = 0", "add count 1", "add count $n * 2", "repeat $count"}) {
            Step s = Step.parse(l);
            assertEquals(s, Step.parse(s.text()), l);
            assertEquals(l, s.text());
        }
        assertEquals(Step.Kind.IF, Step.parse("if night").kind());
        assertEquals(Step.Kind.ELSE, Step.parse("else if night").kind());
        assertEquals("count", Step.parse("set Count = 4").variable());
        assertEquals("1", Step.parse("add count").expression());
    }

    @Test
    void oldWordsKeepTheirMeaning() {
        // "#set name value" is the settings command, "set x = 1" a variable
        assertEquals(Step.Kind.COMMAND, Step.parse("#set mineDropJunk false").kind());
        assertEquals(Step.Kind.COMMAND, Step.parse("set mineDropJunk false").kind());
        assertEquals(Step.Kind.END, Step.parse("end @if night").kind());
        assertTrue(Step.isStepWord("while night"));
        assertFalse(Step.isStepWord("mine 3 dirt"));
    }

    @Test
    void naturalConditionWordingReadsAsDottedNames() {
        W w = new W();
        w.free = 3;
        w.armor = 10;
        w.xp = 30;
        w.hostiles = 2;
        w.thunder = true;
        w.rain = true;
        w.holding = "iron_pickaxe";
        w.effect = "speed";
        for (String t : new String[]{"free slots < 4", "armor durability <= 10", "xp level >= 30", "hostile nearby", "hostiles > 1",
            "weather == thunder", "weather != clear", "holding iron_pickaxe", "has effect speed", "distance to @home < 20",
            "y level == 64", "time >= 6000 and time < 12000", "random 100", "not random 0", "rain", "not players nearby"}) {
            Cond c = Cond.parse(t);
            assertEquals(c, Cond.parse(c.text()), t);
            assertTrue(c.eval(w), t);
        }
        assertFalse(Cond.parse("distance to @home > 20").eval(w));
        assertFalse(Cond.parse("holding diamond").eval(w));
        assertEquals("inv.free<4", Cond.parse("free slots < 4").text());
    }

    @Test
    void badBlocksNameTheLine() {
        var e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("a", "", "if night", "b")));
        assertTrue(e.getMessage().startsWith("step 3:") && e.getMessage().contains("never closed"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("else")));
        assertTrue(e.getMessage().startsWith("step 1:"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("if night", "else", "else", "end")));
        assertTrue(e.getMessage().startsWith("step 3:"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("if night", "break", "end")));
        assertTrue(e.getMessage().contains("outside a loop"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("while banana", "end")));
        assertTrue(e.getMessage().startsWith("step 1:") && e.getMessage().contains("unknown condition"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("if night @retry 2", "end")));
        assertTrue(e.getMessage().contains("modifiers"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Routine.compile(List.of("set round = 4")));
        assertTrue(e.getMessage().contains("own"), e.getMessage());
    }

    @Test
    void routineCheckValidatesBalanceAndVariables() {
        assertTrue(Routine.check(List.of("set n = 0", "while $n < 3", "add n", "end", "say $n"), null, null).isEmpty());
        var p = Routine.check(List.of("if night", "a"), null, null);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("step 1") && p.get(0).contains("never closed"));
        p = Routine.check(List.of("say $nope"), null, null);
        assertTrue(p.get(0).contains("unknown variable $nope"), p.toString());
        // a plain end with nothing open still ends the routine
        assertTrue(Routine.check(List.of("a", "end"), null, null).isEmpty());
    }

    // ---- running -----------------------------------------------------------------------------------------------

    @Test
    void ifElseIfElseChain() {
        for (int hp : new int[]{3, 8, 20}) {
            H h = new H();
            h.world.health = hp;
            run(h, "if health<5", "a", "else if health<10", "b", "else", "c", "end", "d");
            assertEquals(hp == 3 ? List.of("a", "d") : hp == 8 ? List.of("b", "d") : List.of("c", "d"), h.ran, "hp " + hp);
        }
    }

    @Test
    void nestedIfsAndTheOldEnd() {
        H h = new H();
        h.world.night = true;
        run(h, "if night", "a", "if health<5", "x", "else", "b", "end", "c", "else", "z", "end", "d", "end", "never");
        assertEquals(List.of("a", "b", "c", "d"), h.ran);
    }

    @Test
    void repeatAndNestedRepeat() {
        H h = new H();
        run(h, "repeat 2", "a", "repeat 3", "b", "end", "end");
        assertEquals(List.of("a", "b", "b", "b", "a", "b", "b", "b"), h.ran);
        h = new H();
        run(h, "repeat 0", "a", "end", "z");
        assertEquals(List.of("z"), h.ran);
    }

    @Test
    void whileWithBreakAndContinue() {
        H h = new H();
        run(h, "set n = 0", "while $n < 10", "add n", "if $n == 2", "continue", "end", "if $n > 4", "break", "end", "mine $n", "end", "say after $n");
        assertEquals(List.of("mine 1", "mine 3", "mine 4"), h.ran);
        assertEquals(List.of("after 5"), h.said);
    }

    @Test
    void breakLeavesOnlyTheInnerLoop() {
        H h = new H();
        run(h, "repeat 2", "repeat 5", "i", "break", "end", "o", "end");
        assertEquals(List.of("i", "o", "i", "o"), h.ran);
    }

    @Test
    void variablesExpressionsAndArgs() {
        H h = new H();
        h.world.inv = Map.of("iron_ingot", 7);
        run(h, "set want = inv.iron_ingot * 2 - 4", "set half = ($want + 1) / 2", "add want 10", "withdraw string $want", "say $half $round $fails $loop");
        assertEquals(List.of("withdraw string 20"), h.ran);
        assertEquals(List.of("5.5 1 0 0"), h.said);
    }

    @Test
    void loopCountAndComparisonBetweenVariables() {
        H h = new H();
        run(h, "set a = 3", "set b = 3", "if $a == $b", "x", "end", "repeat 3", "if loop count == 2", "y", "end", "end");
        assertEquals(List.of("x", "y"), h.ran);
    }

    @Test
    void lastStepAndFailsCounter() {
        H h = new H();
        h.failAll = true;
        RoutineRunner r = new RoutineRunner();
        r.start("t", List.of("a", "if last step failed", "say bad $fails", "end", "b", "if not last step failed", "say fine", "end"), 1);
        drive(r, h, 1000);
        assertEquals(List.of("bad 1"), h.said);
        assertEquals(2, (int) r.variable("fails"));
        h = new H();
        r = new RoutineRunner();
        r.start("t", List.of("a", "if last step succeeded", "say ok", "end"), 1);
        drive(r, h, 1000);
        assertEquals(List.of("ok"), h.said);
    }

    @Test
    void randomUsesTheDice() {
        H h = new H();
        RoutineRunner r = new RoutineRunner();
        r.setRandom(new Random(1));
        r.start("t", List.of("repeat 1000", "if random 30", "add hits", "end", "end", "say $hits"), 1);
        drive(r, h, 100000);
        int hits = Integer.parseInt(h.said.get(0));
        assertTrue(hits > 230 && hits < 370, "hits " + hits);
    }

    @Test
    void roundCounterAcrossRounds() {
        H h = new H();
        RoutineRunner r = new RoutineRunner();
        r.start("t", List.of("say r$round"), 3);
        assertEquals("t done", drive(r, h, 1000));
        assertEquals(List.of("r1", "r2", "r3"), h.said);
    }

    @Test
    void jumpOutOfALoopDropsIt() {
        H h = new H();
        run(h, "repeat 5", "a", "jump out", "end", "label out", "b", "repeat 2", "c", "end");
        assertEquals(List.of("a", "b", "c", "c"), h.ran);
    }

    // ---- the guard -----------------------------------------------------------------------------------------------

    @Test
    void anEndlessWhileIsStoppedByTheLoopLimit() {
        H h = new H();
        RoutineRunner r = new RoutineRunner();
        r.setLoopLimit(1000);
        r.start("t", List.of("while night or day", "add n", "end"), 1);
        String m = drive(r, h, 100000);
        assertNotNull(m);
        assertTrue(m.contains("went round more than 1000 times") && m.contains("step 1"), m);
        assertFalse(r.running());
    }

    @Test
    void aTightLoopDoesNotHogATickAndStopWorksAtOnce() {
        H h = new H();
        RoutineRunner r = new RoutineRunner();
        r.start("t", List.of("while day", "add n", "end"), 1);
        assertNull(r.tick(false, false, h));
        assertTrue(r.running());
        assertTrue(r.variable("n") <= 5001, "one tick did " + r.variable("n"));
        r.stop();
        assertFalse(r.running());
        assertNull(r.tick(false, false, h));
    }

    @Test
    void oldRoutinesRunTheSame() {
        H h = new H();
        run(h, "a", "b @if night", "c @unless night", "end", "d");
        assertEquals(List.of("a", "c"), h.ran);
    }
}
