package dev.lazuli.core.task;

import dev.lazuli.core.settings.Settings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class RoutineLanguageTest {
    // ---- parsing and printing ---------------------------------------------------------------------------------

    @Test
    void aPlainStepIsJustTheCommand() {
        Step s = Step.parse("#mine 8 iron_ore");
        assertEquals("#mine 8 iron_ore", s.command());
        assertEquals("#mine 8 iron_ore", s.text());
        assertEquals(0, s.retry());
        assertEquals(1, s.times());
        assertEquals(Step.OnFail.CONTINUE, s.onFail());
        assertTrue(s.enabled());
    }

    @Test
    void theFullFormParsesAndPrintsBack() {
        String line = "getitem string 64 @retry 3 @timeout 5m @onfail continue @if inv.free>4 @with mineDropJunk=false";
        Step s = Step.parse(line);
        assertEquals("getitem string 64", s.command());
        assertEquals(3, s.retry());
        assertEquals(5 * 1200, s.timeoutTicks());
        assertEquals(Map.of("mineDropJunk", "false"), s.with());
        assertEquals("inv.free>4", s.ifCond().text());
        // continue is the default, so it isn't printed
        assertEquals("getitem string 64 @retry 3 @timeout 5m @if inv.free>4 @with mineDropJunk=false", s.text());
        assertEquals(s, Step.parse(s.text()));
    }

    @Test
    void everyModifierRoundTrips() {
        String line = "farm @retry 2 @timeout 90s @onfail goto restock @times 3 @before 5s @after 1m @if not night @unless dim==nether "
            + "@until inv.wheat>=64 and (health>10 or has.golden_apple) @with a=1 b=2 @off";
        Step s = Step.parse(line);
        assertEquals(Step.OnFail.GOTO, s.onFail());
        assertEquals("restock", s.failLabel());
        assertFalse(s.enabled());
        assertEquals(s, Step.parse(s.text()));
        assertEquals(line, s.text());
    }

    @Test
    void durationsParseAndPrint() {
        assertEquals(600, Step.parseDuration("30s"));
        assertEquals(30 * 20, Step.parseDuration("30"));
        assertEquals(72000 + 30 * 1200, Step.parseDuration("1h30m"));
        assertEquals(7, Step.parseDuration("7t"));
        assertEquals("1h", Step.durationText(72000));
        assertEquals("5m", Step.durationText(6000));
        assertEquals("7t", Step.durationText(7));
        assertThrows(IllegalArgumentException.class, () -> Step.parseDuration("soon"));
    }

    @Test
    void unknownModifiersAreReportedNotIgnored() {
        var e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @retry 2 @retyr 3"));
        assertTrue(e.getMessage().contains("unknown modifier @retyr"), e.getMessage());
        assertTrue(e.getMessage().contains("did you mean @retry"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @retry 2 banana"));
        assertTrue(e.getMessage().contains("needs one number"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @retry two"));
        assertTrue(e.getMessage().contains("whole number"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @timeout"));
        assertTrue(e.getMessage().contains("duration"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @onfail explode"));
        assertTrue(e.getMessage().contains("stop, continue, skip-rest or goto"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @retry 1 @retry 2"));
        assertTrue(e.getMessage().contains("twice"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("farm @if inv.free>"));
        assertTrue(e.getMessage().contains("needs a value"), e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Step.parse("@retry 3"));
        assertTrue(e.getMessage().contains("no command"), e.getMessage());
    }

    @Test
    void anAtWordBeforeAnyModifierIsATargetInTheCommand() {
        Step s = Step.parse("withdraw string all @duper @retry 2");
        assertEquals("withdraw string all @duper", s.command());
        assertEquals(2, s.retry());
        assertEquals("trade sell string @fisher", Step.parse("trade sell string @fisher").command());
    }

    @Test
    void stepWords() {
        assertEquals(Step.Kind.WAIT, Step.parse("wait 30s").kind());
        assertEquals(600, Step.parse("wait 30s").waitTicks());
        assertEquals(Step.Kind.SAY, Step.parse("say hello there").kind());
        assertEquals("hello there", Step.parse("say hello there").args());
        assertEquals(Step.Kind.NOTIFY, Step.parse("notify done").kind());
        assertEquals(Step.Kind.SKIP, Step.parse("skip @if inv.full").kind());
        assertEquals(Step.Kind.END, Step.parse("end").kind());
        assertEquals("loop", Step.parse("label loop").label());
        assertEquals(Step.Kind.JUMP, Step.parse("jump loop").kind());
        assertEquals(Step.Kind.JUMP, Step.parse("goto :loop").kind());
        assertEquals("loop", Step.parse("goto :loop").label());
        // a goto without the colon is the #goto command
        assertEquals(Step.Kind.COMMAND, Step.parse("goto 1 2 3").kind());
        assertEquals(Step.Kind.COMMAND, Step.parse("#goto athens").kind());
        assertThrows(IllegalArgumentException.class, () -> Step.parse("wait"));
        assertThrows(IllegalArgumentException.class, () -> Step.parse("wait soon"));
        assertThrows(IllegalArgumentException.class, () -> Step.parse("say"));
        assertThrows(IllegalArgumentException.class, () -> Step.parse("label"));
    }

    @Test
    void theModelEditsLikeText() {
        Step s = Step.of("farm").withRetry(2).withTimeout(1200).withOnFail(Step.OnFail.GOTO, "x").withTimes(2)
            .withWaits(20, 40).withConds(Cond.parse("night"), null, Cond.parse("inv.free<2")).withSettings(Map.of("allowSprint", "false"))
            .withEnabled(false);
        assertEquals(s, Step.parse(s.text()));
        assertEquals("farm", Step.parse(s.text()).command());
    }

    // ---- checking -----------------------------------------------------------------------------------------------

    @Test
    void checkFindsEveryKindOfProblem() {
        List<String> problems = Routine.check(List.of(
            "#mine 8 iron_ore",
            "#getitm string",
            "farm @retyr 3 @retry",
            "farm @with noSuchSetting=1",
            "farm @with allowSprint=maybe",
            "jump nowhere",
            "label a", "label a",
            "withdraw string @ghost"
        ), w -> w.matches("mine|farm|withdraw"), t -> false);
        String all = String.join("\n", problems);
        assertTrue(all.contains("step 2: unknown command 'getitm'"), all);
        assertTrue(all.contains("step 3:"), all);
        assertTrue(all.contains("no setting called 'noSuchSetting'"), all);
        assertTrue(all.contains("allowSprint=maybe"), all);
        assertTrue(all.contains("jumps to label 'nowhere'"), all);
        assertTrue(all.contains("'a' is there twice"), all);
        assertTrue(all.contains("no target called @ghost"), all);
        assertTrue(Routine.check(List.of("#mine 8 iron_ore", "#deposit all", "farm @retry 3 @onfail stop"), w -> true, t -> true).isEmpty());
    }

    @Test
    void aTypoedModifierOnATargetIsHinted() {
        List<String> p = Routine.check(List.of("farm @retyr 3"), w -> true, t -> false);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("@retry"), p.get(0));
    }

    @Test
    void startingARoutineThatDoesntParseSaysWhichStep() {
        RoutineRunner r = new RoutineRunner();
        var e = assertThrows(IllegalArgumentException.class, () -> r.start("x", List.of("farm", "mine @bad 1 @retry"), 1));
        assertTrue(e.getMessage().startsWith("step 2:"), e.getMessage());
        assertFalse(r.running());
        e = assertThrows(IllegalArgumentException.class, () -> r.start("x", List.of("jump nowhere"), 1));
        assertTrue(e.getMessage().contains("nowhere"));
    }

    // ---- conditions ---------------------------------------------------------------------------------------------

    /** A pretend game for conditions. */
    private static final class Fake implements Cond.World {
        Map<String, Integer> inv = Map.of();
        Map<String, Integer> chests = Map.of();
        int free = 10, hunger = 20;
        double health = 20;
        boolean night, busy;
        String dim = "overworld";

        public int items(String item) { return inv.getOrDefault(item.replace("minecraft:", ""), 0); }
        public int chestItems(String item) { return chests.getOrDefault(item.replace("minecraft:", ""), 0); }
        public int freeSlots() { return free; }
        public double health() { return health; }
        public int hunger() { return hunger; }
        public boolean night() { return night; }
        public String dimension() { return dim; }
        public boolean busy() { return busy; }
    }

    private static boolean holds(String cond, Fake w) {
        Cond c = Cond.parse(cond);
        assertEquals(c, Cond.parse(c.text()), "round trip of " + cond);
        return c.eval(w);
    }

    @Test
    void conditionsLookAtTheGame() {
        Fake w = new Fake();
        w.inv = Map.of("iron_ingot", 12, "golden_apple", 1);
        w.chests = Map.of("string", 300);
        w.free = 3;
        w.health = 8;
        w.hunger = 14;
        assertTrue(holds("inv.iron_ingot>=12", w));
        assertFalse(holds("inv.iron_ingot>12", w));
        assertTrue(holds("inv.minecraft:iron_ingot == 12", w));
        assertTrue(holds("chest.string>256", w));
        assertTrue(holds("inv.free<4", w));
        assertFalse(holds("inv.full", w));
        w.free = 0;
        assertTrue(holds("inv.full", w));
        assertTrue(holds("has.golden_apple", w));
        assertFalse(holds("has.diamond", w));
        assertTrue(holds("health<10 and hunger>=14", w));
        assertTrue(holds("health>10 or has.golden_apple", w));
        assertTrue(holds("not has.diamond", w));
        assertTrue(holds("not (health>10 or has.diamond)", w));
        assertTrue(holds("day", w));
        assertFalse(holds("night", w));
        w.night = true;
        assertTrue(holds("night and not day", w));
        assertTrue(holds("time==night", w));
        assertTrue(holds("time!=day", w));
        assertTrue(holds("dim==overworld", w));
        w.dim = "minecraft:the_nether";
        assertTrue(holds("dim==nether", w));
        assertTrue(holds("dim!=end", w));
        w.busy = true;
        assertTrue(holds("busy", w));
    }

    @Test
    void andBindsTighterThanOr() {
        Fake w = new Fake();
        w.night = true;
        w.health = 5;
        // night or (health>10 and has.x): true because of night
        assertTrue(holds("night or health>10 and has.x", w));
        // (night or health>10) and has.x: false
        assertFalse(holds("(night or health>10) and has.x", w));
    }

    @Test
    void badConditionsExplainThemselves() {
        for (String[] c : new String[][]{
            {"", "empty"}, {"inv.free", "needs a comparison"}, {"banana", "unknown condition"}, {"health>lots", "must be a number"},
            {"dim==mars", "overworld, nether or end"}, {"time>day", "only takes"}, {"(night", "missing )"}, {"night and", "ends too soon"},
            {"night night", "unexpected"}}) {
            var e = assertThrows(IllegalArgumentException.class, () -> Cond.parse(c[0]), c[0]);
            assertTrue(e.getMessage().contains(c[1]), c[0] + " -> " + e.getMessage());
        }
    }

    // ---- running ------------------------------------------------------------------------------------------------

    /** A host that scripts how each command goes. */
    private static final class Script implements RoutineRunner.Host {
        final List<String> ran = new ArrayList<>(), said = new ArrayList<>(), notified = new ArrayList<>();
        final Fake world = new Fake();
        int stops, busyLeft;
        /** Ticks a command keeps things busy (0: over at once). */
        Function<String, Integer> busyFor = c -> 0;
        /** Whether a command is accepted (false: refused at once). */
        Function<String, Boolean> accepts = c -> true;
        Runnable onRun = () -> {};

        public boolean run(String line) {
            ran.add(line);
            busyLeft = busyFor.apply(line);
            onRun.run();
            return accepts.apply(line);
        }

        public void stop() {
            stops++;
            busyLeft = 0;
        }

        public Cond.World world() { return world; }
        public void say(String t) { said.add(t); }
        public void notify(String t) { notified.add(t); }
    }

    /** Ticks until the routine ends or {@code max}; returns the final message (null if still running). */
    private static String drive(RoutineRunner r, Script h, int max, List<String> notes) {
        for (int t = 0; t < max; t++) {
            boolean busy = h.busyLeft > 0;
            if (busy) h.busyLeft--;
            String m = r.tick(busy, h.world.free <= 0, h);
            if (m != null) return m;
        }
        return null;
    }

    private static String drive(RoutineRunner r, Script h, int max) {
        return drive(r, h, max, new ArrayList<>());
    }

    @Test
    void theOldSyntaxRunsUnchanged() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> 15;
        r.start("iron", List.of("#mine 8 iron_ore", "#smelt raw_iron 8", "#deposit iron_ingot"), 1);
        assertEquals("iron done", drive(r, h, 300));
        assertEquals(List.of("#mine 8 iron_ore", "#smelt raw_iron 8", "#deposit iron_ingot"), h.ran);
    }

    @Test
    void ifAndUnlessDecideWhetherAStepRuns() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.world.night = true;
        r.start("x", List.of("a @if night", "b @if day", "c @unless night", "d @unless day"), 1);
        assertEquals("x done", drive(r, h, 300));
        assertEquals(List.of("a", "d"), h.ran);
    }

    @Test
    void disabledStepsAreSkipped() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        r.start("x", List.of("a", "b @off", "c"), 1);
        drive(r, h, 300);
        assertEquals(List.of("a", "c"), h.ran);
    }

    @Test
    void timesRepeatsAStep() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> 5;
        r.start("x", List.of("a @times 3", "b"), 1);
        assertEquals("x done", drive(r, h, 500));
        assertEquals(List.of("a", "a", "a", "b"), h.ran);
    }

    @Test
    void untilRepeatsUntilTheConditionHolds() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> 5;
        int[] runs = {0};
        h.onRun = () -> {
            if (++runs[0] == 4) h.world.inv = Map.of("wheat", 64);
        };
        r.start("x", List.of("harvest @until inv.wheat>=64", "b"), 1);
        assertEquals("x done", drive(r, h, 2000));
        assertEquals(List.of("harvest", "harvest", "harvest", "harvest", "b"), h.ran);
    }

    @Test
    void untilStopsAnEndlessJobOnceItHolds() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> c.startsWith("farm") ? Integer.MAX_VALUE : 5;
        r.start("x", List.of("farm @until inv.wheat>=64", "b"), 1);
        for (int t = 0; t < 100; t++) {
            boolean busy = h.busyLeft > 0;
            if (busy && h.busyLeft != Integer.MAX_VALUE) h.busyLeft--;
            if (t == 50) h.world.inv = Map.of("wheat", 64);
            r.tick(busy, false, h);
        }
        assertEquals(List.of("farm", "b"), h.ran);
        assertEquals(1, h.stops);
    }

    @Test
    void anEndlessStepWithATimeoutEndsThereAsSuccess() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> c.startsWith("farm") ? Integer.MAX_VALUE : 0;
        r.start("x", List.of("farm @timeout 10s @onfail stop", "b"), 1);
        String end = null;
        for (int t = 0; t < 1000 && end == null; t++) end = r.tick(h.busyLeft > 0, false, h);
        assertEquals("x done", end);
        assertEquals(List.of("farm", "b"), h.ran);
    }

    @Test
    void waitsBeforeAndAfter() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        r.start("x", List.of("a @before 5s @after 5s", "b"), 1);
        int firstA = -1, firstB = -1;
        for (int t = 0; t < 1000 && (firstA < 0 || firstB < 0); t++) {
            r.tick(false, false, h);
            if (firstA < 0 && h.ran.size() == 1) firstA = t;
            if (firstB < 0 && h.ran.size() == 2) firstB = t;
        }
        assertTrue(firstA >= 100, "waited 5s first, ran at " + firstA);
        assertTrue(firstB - firstA >= 100 + RoutineRunner.START_TICKS - 1, "5s after a, b at " + firstB + " after " + firstA);
    }

    @Test
    void waitSayNotifyEndAndSkip() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        r.start("x", List.of("say hi all", "wait 10s", "notify ready", "a", "skip", "never"), 1);
        int ticks = 0;
        String end = null;
        while (end == null && ticks < 2000) {
            end = r.tick(false, false, h);
            ticks++;
        }
        assertEquals("x done", end);
        assertEquals(List.of("hi all"), h.said);
        assertEquals(List.of("ready"), h.notified);
        assertEquals(List.of("a"), h.ran);
        assertTrue(ticks >= 200, "waited 10 s, took " + ticks);
        RoutineRunner e = new RoutineRunner();
        Script h2 = new Script();
        e.start("y", List.of("a", "end", "b"), 0);
        assertEquals("y done", drive(e, h2, 500));
        assertEquals(List.of("a"), h2.ran);
    }

    @Test
    void skipCanBeConditional() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.world.free = 0;
        r.start("x", List.of("a", "skip @if inv.full", "b"), 1);
        drive(r, h, 300);
        assertEquals(List.of("a"), h.ran);
    }

    @Test
    void jumpsAndLabelsMakeLoops() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        int[] runs = {0};
        h.onRun = () -> {
            if (h.ran.get(h.ran.size() - 1).equals("a") && ++runs[0] == 3) h.world.night = true;
        };
        r.start("x", List.of("label top", "a", "jump top @unless night", "b"), 1);
        assertEquals("x done", drive(r, h, 1000));
        assertEquals(List.of("a", "a", "a", "b"), h.ran);
    }

    @Test
    void aJumpLoopWithNothingInItStops() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        r.start("x", List.of("label top", "jump top"), 1);
        String end = drive(r, h, 100);
        assertNotNull(end);
        assertTrue(end.contains("stopped"), end);
        assertFalse(r.running());
    }

    // retries and failure

    @Test
    void aRefusedStepIsRetriedThenTheOnFailRuns() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.accepts = c -> !c.equals("flaky");
        List<String> notes = new ArrayList<>();
        r.setIdlePolicy(0, 20, notes::add);
        r.start("x", List.of("flaky @retry 2 @onfail continue", "b"), 1);
        r.setStepTimeout(0);
        // the host reports the failure like the mod does: failStep after the refused run
        String end = null;
        for (int t = 0; t < 500 && end == null; t++) end = r.tick(false, false, h);
        assertEquals(List.of("flaky", "flaky", "flaky", "b"), h.ran);
        assertEquals("x done", end);
        assertTrue(notes.stream().anyMatch(n -> n.contains("retrying (1/2)")), notes.toString());
        assertTrue(notes.stream().anyMatch(n -> n.contains("moving on")), notes.toString());
    }

    @Test
    void onFailStopEndsTheRoutine() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.accepts = c -> !c.equals("bad");
        r.start("x", List.of("a", "bad @onfail stop", "b"), 1);
        String end = drive(r, h, 500);
        assertEquals("x stopped: step 2 'bad' didn't start", end);
        assertEquals(List.of("a", "bad"), h.ran);
        assertFalse(r.running());
    }

    @Test
    void onFailSkipRestStartsTheNextRound() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.accepts = c -> !c.equals("bad");
        h.busyFor = c -> 5;
        r.start("x", List.of("a", "bad @onfail skip-rest", "b"), 2);
        assertEquals("x done", drive(r, h, 800));
        assertEquals(List.of("a", "bad", "a", "bad"), h.ran);
    }

    @Test
    void onFailGotoJumpsToTheLabel() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.accepts = c -> !c.equals("bad");
        r.start("x", List.of("bad @onfail goto fix", "skipped", "label fix", "recover"), 1);
        assertEquals("x done", drive(r, h, 800));
        assertEquals(List.of("bad", "recover"), h.ran);
    }

    @Test
    void aTimeoutFailsAStepAndRetriesIt() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> c.equals("hang") ? Integer.MAX_VALUE : 5;
        r.start("x", List.of("hang @timeout 10s @retry 1 @onfail stop", "b"), 1);
        String end = null;
        for (int t = 0; t < 3000 && end == null; t++) {
            boolean busy = h.busyLeft > 0;
            if (busy && h.busyLeft != Integer.MAX_VALUE) h.busyLeft--;
            end = r.tick(busy, false, h);
        }
        assertEquals(List.of("hang", "hang"), h.ran);
        assertEquals("x stopped: step 1 'hang' took too long", end);
        assertEquals(2, h.stops);
    }

    @Test
    void theGameCanFailAStepThatWasRunning() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> 1000;
        r.start("x", List.of("job @retry 1 @onfail stop", "b"), 1);
        r.tick(false, false, h); // launches
        for (int t = 0; t < 30; t++) r.tick(h.busyLeft > 0, false, h);
        r.failStep("no path");
        h.busyLeft = 0;
        String end = null;
        for (int t = 0; t < 100 && end == null; t++) {
            boolean busy = h.busyLeft > 0;
            if (busy) h.busyLeft--;
            if (h.ran.size() == 2 && t > 40) r.failStep("no path again");
            end = r.tick(busy, false, h);
        }
        assertEquals(List.of("job", "job"), h.ran);
        assertEquals("x stopped: step 1 'job' no path again", end);
    }

    @Test
    void aPlainStepIgnoresFailureReports() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> 20;
        r.start("x", List.of("job", "b"), 1);
        r.tick(false, false, h);
        r.failStep("no path");
        assertEquals("x done", drive(r, h, 300));
        assertEquals(List.of("job", "b"), h.ran);
    }

    // settings

    @Test
    void withChangesSettingsForTheStepOnlyAndPutsThemBack() {
        Settings s = new Settings();
        assertTrue(s.mineDropJunk.get());
        RoutineRunner r = new RoutineRunner();
        r.setSettings(s);
        Script h = new Script();
        List<Boolean> during = new ArrayList<>();
        h.busyFor = c -> 5;
        h.onRun = () -> during.add(s.mineDropJunk.get());
        r.start("x", List.of("a @with mineDropJunk=false", "b"), 1);
        assertEquals("x done", drive(r, h, 300));
        assertEquals(List.of(false, true), during);
        assertTrue(s.mineDropJunk.get());
    }

    @Test
    void withIsPutBackWhenTheRoutineIsStoppedMidStep() {
        Settings s = new Settings();
        RoutineRunner r = new RoutineRunner();
        r.setSettings(s);
        Script h = new Script();
        h.busyFor = c -> 1000;
        r.start("x", List.of("a @with mineDropJunk=false allowSprint=false"), 1);
        r.tick(false, false, h);
        assertFalse(s.mineDropJunk.get());
        assertFalse(s.allowSprint.get());
        r.stop();
        assertTrue(s.mineDropJunk.get());
        assertTrue(s.allowSprint.get());
    }

    @Test
    void withIsPutBackAfterAFailureToo() {
        Settings s = new Settings();
        RoutineRunner r = new RoutineRunner();
        r.setSettings(s);
        Script h = new Script();
        h.accepts = c -> false;
        r.start("x", List.of("a @with mineDropJunk=false @onfail stop"), 1);
        drive(r, h, 100);
        assertTrue(s.mineDropJunk.get());
    }

    @Test
    void statusShowsTheStepAndRetries() {
        RoutineRunner r = new RoutineRunner();
        Script h = new Script();
        h.busyFor = c -> 100;
        r.start("job", List.of("a @times 2 @retry 1"), 1);
        r.tick(false, false, h);
        assertTrue(r.status().startsWith("job step 1/1"), r.status());
        assertTrue(r.status().contains("x1/2"), r.status());
        assertTrue(r.detail().endsWith("a @retry 1 @times 2"), r.detail());
    }

    @Test
    void routinesKeepStepsWithModifiersInTheFileAndPlainOnesToo() throws Exception {
        var f = java.nio.file.Files.createTempDirectory("lazuli").resolve("routines.txt");
        Routines rs = new Routines(f);
        List<String> lines = List.of("#mine 8 iron_ore", "#getitem string 64 @retry 3 @if inv.free>4 @with mineDropJunk=false", "wait 5m", "label x");
        rs.set("night", lines);
        assertEquals(lines, new Routines(f).get("night"));
        // an old file with plain commands still loads
        java.nio.file.Files.writeString(f, "old\t#mine 8 iron_ore ; #deposit all\n");
        assertEquals(List.of("#mine 8 iron_ore", "#deposit all"), new Routines(f).get("old"));
        assertTrue(Routine.check(new Routines(f).get("old"), w -> true, null).isEmpty());
    }
}
