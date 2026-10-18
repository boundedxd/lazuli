package dev.lazuli.core.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FormsTest {
    @Test
    void itemAndCountReadAndWriteBack() {
        var a = Forms.parseItem("string 64");
        assertEquals("string", a.item());
        assertEquals(64, a.count());
        assertEquals("string 64", Forms.compose(a));
        assertEquals("dirt", Forms.compose(Forms.parseItem("dirt")));
        assertEquals("", Forms.compose(Forms.parseItem("")));
        // several items or coordinates aren't one item with a count
        assertNull(Forms.parseItem("string 64 iron_ingot 3"));
        assertNull(Forms.parseItem("string 64 10"));
        assertEquals("duper", Forms.parseItem("all 5 @duper").target());
        assertNull(Forms.parseItem("64"));
    }

    @Test
    void aTargetComesLastAndIsKept() {
        var a = Forms.parseItem("string all @duper");
        assertNull(a, "two words before the target: not one item");
        var b = Forms.parseItem("string 64 @duper");
        assertEquals("duper", b.target());
        assertEquals(64, b.count());
        assertEquals("string 64 @duper", Forms.compose(b));
        var c = Forms.parseItem("all @duper");
        assertEquals("all", c.item());
        assertEquals("all @duper", Forms.compose(c));
        assertEquals("@duper", Forms.compose(Forms.parseItem("@duper")));
        assertNull(Forms.parseItem("@duper string"));
        assertTrue(Forms.takesTarget("withdraw"));
        assertTrue(Forms.takesTarget("brew"));
        assertFalse(Forms.takesTarget("getitem"));
    }

    @Test
    void mineTakesTheCountFirst() {
        var m = Forms.parseBlock("8 iron_ore");
        assertEquals("iron_ore", m.item());
        assertEquals(8, m.count());
        assertEquals("8 iron_ore", Forms.composeBlock(m));
        assertEquals("iron_ore", Forms.composeBlock(Forms.parseBlock("iron_ore")));
        assertNull(Forms.parseBlock("iron_ore deepslate_iron_ore"));
    }

    @Test
    void coordinatesAndTargets() {
        var w = Forms.parseWhere("10 64 -20");
        assertArrayEquals(new int[]{10, 64, -20}, w.xyz());
        assertEquals("10 64 -20", Forms.compose(w));
        var t = Forms.parseWhere("@duper");
        assertNull(t.xyz());
        assertEquals("duper", t.target());
        assertEquals("@duper", Forms.compose(t));
        assertNull(Forms.parseWhere("athens"));
        assertNull(Forms.parseWhere("1 2"));
        assertNull(Forms.parseWhere("~ ~ ~"));
        assertArrayEquals(new int[]{0, 0, 0}, Forms.parseWhere("").xyz());
    }

    @Test
    void numbers() {
        assertEquals(0, Forms.parseNumber(""));
        assertEquals(12, Forms.parseNumber("12"));
        assertEquals(-1, Forms.parseNumber("lots"));
    }

    @Test
    void commandsGetThePrefixButStepWordsDont() {
        assertEquals("#getitem string 64", Forms.command("#", "getitem", "string 64"));
        assertEquals("#farm", Forms.command("#", "farm", ""));
        assertEquals("wait 30s", Forms.command("#", "wait", "30s"));
        assertEquals("#farm", Forms.defaultCommand("#", "farm"));
        assertEquals("label here", Forms.defaultCommand("#", "label"));
        // what the editor writes is what the parser reads
        for (String w : Forms.words()) {
            Step s = Step.parse(Forms.defaultCommand("#", w));
            assertEquals(w, s.word(), w);
        }
    }

    @Test
    void everyListedCommandIsARealOne() {
        for (String w : Forms.words()) {
            if (Forms.stepWord(w)) continue;
            assertTrue(dev.lazuli.core.command.CommandHandler.isCommand(w), w + " isn't a command");
        }
    }

    // ---- condition clauses ----------------------------------------------------------------------------------

    @Test
    void aConditionFormRoundTrips() {
        for (String text : new String[]{"inv.free>4", "inv.iron_ingot>=12 and night", "not inv.full", "has.golden_apple or health<10",
            "chest.string>256 and hunger<=14 and dim==nether", "busy", "time==night", "inv.full or not busy"}) {
            Cond c = Cond.parse(text);
            CondForm f = CondForm.of(c);
            assertNotNull(f, text);
            Cond back = f.toCond();
            // the same meaning, if not always the same words (time==night is the flag night)
            Fake w = new Fake();
            for (int variant = 0; variant < 8; variant++) {
                w.free = (variant & 1) == 0 ? 0 : 10;
                w.night = (variant & 2) != 0;
                w.busy = (variant & 4) != 0;
                assertEquals(c.eval(w), back.eval(w), text + " vs " + back.text());
            }
            assertEquals(back, CondForm.of(back).toCond(), text);
        }
    }

    @Test
    void aClauseBecomesTheCondition() {
        CondForm f = new CondForm();
        var c = new CondForm.Clause();
        c.subject = CondForm.Subject.INV_ITEM;
        c.item = "minecraft:Wheat";
        c.op = ">=";
        c.number = 64;
        f.clauses.add(c);
        assertEquals("inv.wheat>=64", f.toCond().text());
        var d = new CondForm.Clause();
        d.subject = CondForm.Subject.TIME;
        d.word = "night";
        d.negate = true;
        f.clauses.add(d);
        assertEquals("inv.wheat>=64 and not night", f.toCond().text());
        f.all = false;
        assertEquals("inv.wheat>=64 or not night", f.toCond().text());
        assertNull(new CondForm().toCond());
    }

    @Test
    void anIncompleteClauseSaysWhat() {
        var c = new CondForm.Clause();
        c.subject = CondForm.Subject.HAS_ITEM;
        var e = assertThrows(IllegalArgumentException.class, c::toCond);
        assertTrue(e.getMessage().contains("item"));
    }

    @Test
    void nestedConditionsAreLeftToTheTextField() {
        assertNull(CondForm.of(Cond.parse("(night or busy) and inv.full")));
        assertNull(CondForm.of(Cond.parse("not (night or busy)")));
        assertTrue(CondForm.of(null).clauses.isEmpty());
    }

    private static final class Fake implements Cond.World {
        int free = 10;
        boolean night, busy;

        public int items(String item) { return 0; }
        public int chestItems(String item) { return 0; }
        public int freeSlots() { return free; }
        public double health() { return 20; }
        public int hunger() { return 20; }
        public boolean night() { return night; }
        public String dimension() { return "overworld"; }
        public boolean busy() { return busy; }
    }
}
