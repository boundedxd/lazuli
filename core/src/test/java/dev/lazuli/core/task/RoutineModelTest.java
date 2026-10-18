package dev.lazuli.core.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutineModelTest {
    private static String id(RoutineModel m, int index) {
        return m.rows().get(index).id;
    }

    private static RoutineModel sample() {
        return new RoutineModel(List.of("a", "if night", "b", "else", "c", "end", "while day", "d", "end", "e"));
    }

    @Test
    void depthsAndHeaders() {
        RoutineModel m = sample();
        int[] want = {0, 0, 1, 0, 1, 0, 0, 1, 0, 0};
        for (int i = 0; i < want.length; i++) assertEquals(want[i], m.depth(id(m, i)), "row " + i);
        assertTrue(m.isHeader(id(m, 1)));
        assertTrue(m.isElse(id(m, 3)));
        assertTrue(m.isBlockEnd(id(m, 5)));
        assertFalse(m.isBlockEnd(id(m, 0)));
        assertEquals(2, m.childCount(id(m, 1)));
        assertEquals(1, m.childCount(id(m, 6)));
    }

    @Test
    void aPlainEndOutsideBlocksIsNotABlockEnd() {
        RoutineModel m = new RoutineModel(List.of("a", "end", "b"));
        assertFalse(m.isBlockEnd(id(m, 1)));
        assertEquals(0, m.depth(id(m, 2)));
    }

    @Test
    void collapsingHidesTheBlockButNotItsHeader() {
        RoutineModel m = sample();
        String h = id(m, 1);
        m.toggle(h);
        assertTrue(m.collapsed(h));
        assertEquals(List.of(id(m, 0), h, id(m, 6), id(m, 7), id(m, 8), id(m, 9)), m.visibleIds());
        m.toggle(h);
        assertEquals(10, m.visibleIds().size());
        m.collapse(id(m, 0), true); // not a header: ignored
        assertFalse(m.collapsed(id(m, 0)));
    }

    @Test
    void movingABlockMovesItWhole() {
        RoutineModel m = sample();
        assertTrue(m.move(id(m, 1), 1));
        assertEquals(List.of("a", "while day", "d", "end", "if night", "b", "else", "c", "end", "e"), m.lines());
        assertTrue(m.move(id(m, 4), -1));
        assertEquals(List.of("a", "if night", "b", "else", "c", "end", "while day", "d", "end", "e"), m.lines());
        assertFalse(m.move(id(m, 0), -1));
    }

    @Test
    void stepsInsideABlockMoveWithinIt() {
        RoutineModel m = new RoutineModel(List.of("if night", "x", "y", "else", "z", "end"));
        assertTrue(m.move(id(m, 2), -1));
        assertEquals(List.of("if night", "y", "x", "else", "z", "end"), m.lines());
        assertFalse(m.move(id(m, 2), 1), "can't leave the branch");
        assertFalse(m.move(id(m, 3), 1), "else rows don't move");
    }

    @Test
    void duplicatingABlockCopiesItAll() {
        RoutineModel m = sample();
        String first = m.duplicate(id(m, 1));
        assertEquals(List.of("a", "if night", "b", "else", "c", "end", "if night", "b", "else", "c", "end", "while day", "d", "end", "e"), m.lines());
        assertEquals(id(m, 6), first);
        assertNull(m.duplicate(id(m, 3)));
        m.duplicate(id(m, 0));
        assertEquals("a", m.lines().get(1));
    }

    @Test
    void deletingABlockKeepsOrDropsTheSteps() {
        RoutineModel m = sample();
        m.delete(id(m, 1), true);
        assertEquals(List.of("a", "b", "c", "while day", "d", "end", "e"), m.lines());
        m = sample();
        m.delete(id(m, 1), false);
        assertEquals(List.of("a", "while day", "d", "end", "e"), m.lines());
        m = sample();
        m.delete(id(m, 5), false); // the end deletes its block
        assertEquals(List.of("a", "while day", "d", "end", "e"), m.lines());
        m = sample();
        m.delete(id(m, 3), false); // an else with its steps
        assertEquals(List.of("a", "if night", "b", "end", "while day", "d", "end", "e"), m.lines());
        m = sample();
        m.delete(id(m, 3), true);
        assertEquals(List.of("a", "if night", "b", "c", "end", "while day", "d", "end", "e"), m.lines());
        m = sample();
        m.delete(id(m, 0), false);
        assertEquals(9, m.size());
    }

    @Test
    void addingBlocksInsertsTheirEnd() {
        RoutineModel m = new RoutineModel(List.of("a"));
        String w = m.insertAfter(null, RoutineModel.template("#", "while"));
        assertEquals(List.of("a", "while inv.free>0", "end"), m.lines());
        // after a header: inside it
        m.insertAfter(w, List.of("b"));
        assertEquals(List.of("a", "while inv.free>0", "b", "end"), m.lines());
        assertEquals(1, m.depth(id(m, 2)));
        assertEquals(List.of("set count = 0"), RoutineModel.template("#", "setvar"));
        // after a collapsed header: past its end
        m.collapse(w, true);
        m.insertAfter(w, List.of("z"));
        assertEquals("z", m.lines().get(4));
    }

    @Test
    void addingElseGoesBeforeTheEnd() {
        RoutineModel m = new RoutineModel(List.of("if night", "a", "end"));
        assertNotNull(m.addElse(null, false));
        assertEquals(List.of("if night", "a", "else", "end"), m.lines());
        assertNull(m.addElse(null, false), "one plain else only");
        assertNotNull(m.addElse(null, true));
        assertEquals(List.of("if night", "a", "else if night", "else", "end"), m.lines(), "else if goes before the plain else");
        assertNull(new RoutineModel(List.of("a")).addElse(null, false));
    }

    @Test
    void reconcileKeepsHiddenRowsAndAddsPickedWords() {
        RoutineModel m = sample();
        String h = id(m, 1);
        m.toggle(h);
        // the widget: same names, one new word at the end, and the last step dropped
        List<String> names = new ArrayList<>(m.visibleIds());
        names.remove(names.size() - 1);
        names.add("repeat");
        m.reconcile(names, "#");
        assertEquals(List.of("a", "if night", "b", "else", "c", "end", "while day", "d", "end", "repeat 3", "end"), m.lines());
    }

    @Test
    void removingAHeaderInTheWidgetKeepsItsSteps() {
        RoutineModel m = new RoutineModel(List.of("if night", "a", "else", "b", "end"));
        List<String> names = new ArrayList<>(m.visibleIds());
        names.remove(0);
        m.reconcile(names, "#");
        assertEquals(List.of("a", "b"), m.lines());
    }

    @Test
    void editingATextReparsesAndChecksSeeUnclosedBlocks() {
        RoutineModel m = new RoutineModel(List.of("a", "b"));
        m.setText(id(m, 1), "while night");
        assertTrue(m.isHeader(id(m, 1)));
        var p = m.problems(null, null);
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("never closed"));
        m.setText(id(m, 1), "if banana");
        assertNull(m.row(id(m, 1)).step());
        assertNotNull(m.row(id(m, 1)).error());
        assertEquals("if banana", m.lines().get(1));
    }

    @Test
    void everyConditionKindMakesAClauseAndReadsBack() {
        for (CondForm.Subject s : CondForm.Subject.values()) {
            CondForm.Clause c = CondForm.Clause.of(s);
            if (s == CondForm.Subject.DISTANCE) c.item = "@home";
            Cond cond = c.toCond();
            assertEquals(cond, Cond.parse(cond.text()), s.name());
            CondForm back = CondForm.of(cond);
            assertNotNull(back, s.name());
            assertEquals(1, back.clauses.size());
            assertEquals(cond.text(), back.toCond().text(), s.name());
        }
    }

    @Test
    void controlWordsAreInThePicker() {
        for (String w : List.of("if", "else", "while", "repeat", "setvar", "addvar", "break", "continue"))
            assertTrue(Forms.words().contains(w), w);
        for (String w : List.of("if", "else", "while", "repeat", "setvar", "addvar", "break", "continue")) {
            Step s = Step.parse(Forms.defaultCommand("#", w));
            assertNotEquals(Step.Kind.COMMAND, s.kind(), w);
        }
    }
}
