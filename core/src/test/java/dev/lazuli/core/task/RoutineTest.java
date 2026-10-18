package dev.lazuli.core.task;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutineTest {
    /** Runs the runner with each command keeping Lazuli busy for {@code busyFor} ticks (0: over at once). */
    private static List<String> drive(RoutineRunner r, int busyFor, int ticks, List<String> messages) {
        List<String> ran = new ArrayList<>();
        int[] busyLeft = {0};
        for (int t = 0; t < ticks; t++) {
            boolean busy = busyLeft[0] > 0;
            if (busy) busyLeft[0]--;
            String m = r.tick(busy, cmd -> {
                ran.add(cmd);
                busyLeft[0] = busyFor;
            });
            if (m != null) messages.add(m);
        }
        return ran;
    }

    @Test
    void runsEachStepAfterTheLastIsDone() {
        RoutineRunner r = new RoutineRunner();
        r.start("iron", List.of("#mine 8 iron_ore", "#smelt raw_iron 8", "#deposit iron_ingot"), 1);
        List<String> msgs = new ArrayList<>();
        List<String> ran = drive(r, 30, 200, msgs);
        assertEquals(List.of("#mine 8 iron_ore", "#smelt raw_iron 8", "#deposit iron_ingot"), ran);
        assertEquals(List.of("iron done"), msgs);
        assertFalse(r.running());
    }

    @Test
    void waitsForAStepToFinish() {
        RoutineRunner r = new RoutineRunner();
        r.start("x", List.of("a", "b"), 1);
        List<String> ran = drive(r, 100, 50, new ArrayList<>());
        assertEquals(List.of("a"), ran); // a is still going
    }

    @Test
    void instantCommandsMoveOnAfterAMoment() {
        RoutineRunner r = new RoutineRunner();
        r.start(null, List.of("#set allowSprint false", "#drop dirt"), 1);
        List<String> msgs = new ArrayList<>();
        assertEquals(List.of("#set allowSprint false", "#drop dirt"), drive(r, 0, 40, msgs));
        assertEquals(List.of("Queue done"), msgs);
    }

    @Test
    void repeatsAndForever() {
        RoutineRunner r = new RoutineRunner();
        r.start("loop", List.of("a", "b"), 3);
        assertEquals(List.of("a", "b", "a", "b", "a", "b"), drive(r, 5, 500, new ArrayList<>()));
        r.start("loop", List.of("a"), 0);
        assertTrue(drive(r, 5, 500, new ArrayList<>()).size() > 20);
        assertTrue(r.running());
        assertTrue(r.status().contains("forever"));
        r.stop();
        assertFalse(r.running());
    }

    @Test
    void savesRoutines() throws Exception {
        Path f = Files.createTempDirectory("lazuli").resolve("routines.txt");
        Routines rs = new Routines(f);
        rs.set("Iron", Routines.split("#mine 8 iron_ore ; #smelt raw_iron 8;#deposit iron_ingot"));
        Routines again = new Routines(f);
        assertEquals(List.of("#mine 8 iron_ore", "#smelt raw_iron 8", "#deposit iron_ingot"), again.get("iron"));
        assertTrue(again.remove("IRON"));
        assertNull(new Routines(f).get("iron"));
    }

    @Test
    void thenSplitsLikeASemicolon() {
        assertEquals(List.of("farm", "deposit wheat"), Routines.split("farm then deposit wheat"));
        assertEquals(List.of("mine 8 iron_ore", "smelt raw_iron 8", "deposit"), Routines.split("mine 8 iron_ore THEN smelt raw_iron 8; deposit"));
        assertEquals(List.of("goto athens"), Routines.split("goto athens"));
    }

    @Test
    void jobsWithNoEndAreTold() {
        assertTrue(RoutineRunner.endless("#farm"));
        assertTrue(RoutineRunner.endless("#mine iron_ore"));
        assertFalse(RoutineRunner.endless("#mine 8 iron_ore"));
        assertTrue(RoutineRunner.endless("#fish"));
        assertFalse(RoutineRunner.endless("#fish 10"));
        assertFalse(RoutineRunner.endless("#deposit"));
    }

    @Test
    void anEndlessJobHandsOverWhenTheInventoryFillsThenComesBack() {
        RoutineRunner r = new RoutineRunner();
        r.start(null, List.of("#farm", "#deposit"), 0);
        List<String> ran = new ArrayList<>();
        int[] stops = {0};
        boolean busy = false, full = false;
        int busyLeft = 0;
        for (int t = 0; t < 400; t++) {
            // farming fills up at t=50 and t=250; a deposit takes 20 ticks and empties it
            if (t == 50 || t == 250) full = true;
            String last = ran.isEmpty() ? "" : ran.get(ran.size() - 1);
            busy = last.equals("#farm") || busyLeft > 0;
            if (busyLeft > 0 && --busyLeft == 0) full = false;
            r.tick(busy, full, cmd -> {
                ran.add(cmd);
            }, () -> stops[0]++);
            if (!ran.isEmpty() && ran.get(ran.size() - 1).equals("#deposit") && busyLeft == 0 && full) busyLeft = 20;
        }
        assertEquals(List.of("#farm", "#deposit", "#farm", "#deposit", "#farm"), ran);
        assertEquals(2, stops[0]);
    }

    @Test
    void renamingKeepsTheStepsAndTheOrder() {
        Routines r = new Routines(null);
        r.set("a", List.of("#farm"));
        r.set("b", List.of("#mine 8 iron_ore", "#deposit"));
        r.set("c", List.of());
        assertTrue(r.rename("b", "iron"));
        assertFalse(r.rename("a", "c"), "taken");
        assertFalse(r.rename("nope", "x"));
        assertEquals(List.of("a", "iron", "c"), List.copyOf(r.list().keySet()));
        assertEquals(List.of("#mine 8 iron_ore", "#deposit"), r.get("iron"));
    }

    @Test
    void blankStepsBeingTypedAreSkipped() {
        RoutineRunner r = new RoutineRunner();
        r.start("x", List.of("#farm", "", "  ", "#deposit"), 1);
        List<String> ran = drive(r, 5, 100, new ArrayList<>());
        assertEquals(List.of("#farm", "#deposit"), ran);
    }

    @Test
    void aForeverRoutineWaitsWhenARoundDidNothingThenRetriesAndStopsAfterTheLimit() {
        RoutineRunner r = new RoutineRunner();
        List<String> notes = new ArrayList<>();
        r.setIdlePolicy(100, 3, notes::add);
        r.start("stringtrade", List.of("withdraw", "sell"), 0);
        List<String> msgs = new ArrayList<>();
        List<String> ran = drive(r, 0, 2000, msgs);
        assertEquals(List.of("withdraw", "sell", "withdraw", "sell", "withdraw", "sell"), ran);
        assertEquals(List.of("stringtrade: nothing to do, waiting 5 s"), notes);
        assertEquals(List.of("stringtrade stopped: nothing to do for 3 rounds"), msgs);
    }

    @Test
    void theIdleWaitIsCountedInTicks() {
        RoutineRunner r = new RoutineRunner();
        r.setIdlePolicy(100, 50, m -> {});
        r.start("x", List.of("a"), 0);
        int[] runs = {0};
        int t = 0;
        while (runs[0] < 2 && t < 1000) {
            r.tick(false, c -> runs[0]++);
            t++;
        }
        // first run at tick 1, instant step done after START_TICKS, then 100 ticks of waiting
        assertTrue(t > 100 + RoutineRunner.START_TICKS, "waited, took " + t);
        assertTrue(t < 130, "but not much longer, took " + t);
    }

    @Test
    void aBusyRoundResetsTheIdleCount() {
        RoutineRunner r = new RoutineRunner();
        r.setIdlePolicy(20, 2, m -> {});
        r.start("x", List.of("a"), 0);
        // busy for 30 ticks after every 3rd run, idle otherwise: never two idle rounds in a row
        int[] runs = {0}, busyLeft = {0};
        String done = null;
        for (int t = 0; t < 3000 && done == null; t++) {
            boolean busy = busyLeft[0] > 0;
            if (busy) busyLeft[0]--;
            done = r.tick(busy, c -> {
                if (++runs[0] % 2 == 0) busyLeft[0] = 30;
            });
        }
        assertNull(done);
        assertTrue(runs[0] > 10);
    }

    @Test
    void withoutAWaitAForeverRoutineStopsLikeBefore() {
        RoutineRunner r = new RoutineRunner();
        r.setIdlePolicy(0, 20, m -> {});
        r.start("x", List.of("a"), 0);
        List<String> msgs = new ArrayList<>();
        drive(r, 0, 100, msgs);
        assertEquals(List.of("x stopped: none of its steps did anything"), msgs);
    }

    @Test
    void aForeverRoutineWhoseStepsAllFailStops() {
        RoutineRunner r = new RoutineRunner();
        r.start("broken", List.of("#mine nothing", "#deposit"), 0);
        List<String> msgs = new ArrayList<>();
        List<String> ran = drive(r, 0, 200, msgs);
        assertEquals(List.of("#mine nothing", "#deposit"), ran);
        assertEquals(List.of("broken stopped: none of its steps did anything"), msgs);
        assertFalse(r.running());
    }
}
