package dev.lazuli.core.target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TargetsTest {
    @Test
    void autoNamesCountPerKind() {
        Targets t = new Targets(null);
        assertEquals("chest1", t.pick(null, Target.Kind.CONTAINER, "overworld", 1, 64, 1, null, "minecraft:chest", "", null).name());
        assertEquals("chest2", t.pick(null, Target.Kind.CONTAINER, "overworld", 2, 64, 1, null, "minecraft:barrel", "", null).name());
        assertEquals("villager1", t.pick(null, Target.Kind.VILLAGER, "overworld", 5, 64, 5, "u-1", null, "librarian", null).name());
        assertEquals("stand1", t.pick(null, Target.Kind.STATION, "overworld", 3, 64, 3, null, "minecraft:brewing_stand", "", "stand").name());
        assertEquals("stand2", t.pick(null, Target.Kind.STATION, "overworld", 4, 64, 3, null, "minecraft:brewing_stand", "", "stand").name());
    }

    @Test
    void repickingMovesOrRenames() {
        Targets t = new Targets(null);
        t.pick("duper", Target.Kind.CONTAINER, "overworld", 1, 64, 1, null, "minecraft:chest", "", null);
        assertEquals("duper", t.pick(null, Target.Kind.CONTAINER, "overworld", 1, 64, 1, null, "minecraft:chest", "", null).name());
        t.pick("storage", Target.Kind.CONTAINER, "overworld", 1, 64, 1, null, "minecraft:chest", "", null);
        assertNull(t.get("duper"));
        assertEquals(1, t.size());
        // a name moves to another block
        t.pick("storage", Target.Kind.CONTAINER, "overworld", 9, 64, 9, null, "minecraft:chest", "", null);
        assertEquals(9, t.get("storage").x());
        assertEquals(1, t.size());
        // an entity keeps its name when it walked
        t.pick("bob", Target.Kind.VILLAGER, "overworld", 5, 64, 5, "u1", null, "", null);
        t.pick(null, Target.Kind.VILLAGER, "overworld", 8, 64, 8, "u1", null, "", null);
        assertEquals(8, t.get("bob").x());
    }

    @Test
    void renameRemoveNearestResolve() {
        Targets t = new Targets(null);
        t.pick("a", Target.Kind.CONTAINER, "overworld", 0, 0, 0, null, "c", "", null);
        t.pick("b", Target.Kind.CONTAINER, "overworld", 10, 0, 0, null, "c", "", null);
        t.pick("v", Target.Kind.VILLAGER, "overworld", 3, 0, 0, "uu", null, "", null);
        assertEquals("b", t.nearest("overworld", 9, 0, 0, null).name());
        assertEquals("v", t.nearest("overworld", 4, 0, 0, null).name());
        assertEquals("a", t.nearest("overworld", 4, 0, 0, Target.Kind.CONTAINER).name());
        assertNull(t.nearest("the_nether", 0, 0, 0, null));
        assertTrue(t.rename("a", "Home"));
        assertFalse(t.rename("home", "b"));
        assertFalse(t.rename("zzz", "q"));
        assertEquals("v", t.resolve("uuid:UU").name());
        assertEquals("home", t.resolve("@home").name());
        assertTrue(t.remove("@home"));
        assertNull(t.get("home"));
        assertTrue(t.close("vv").contains("v"));
    }

    @Test
    void reservedAndBadNames() {
        Targets t = new Targets(null);
        assertThrows(IllegalArgumentException.class, () -> t.pick("retry", Target.Kind.BLOCK, "o", 0, 0, 0, null, "b", "", null));
        assertThrows(IllegalArgumentException.class, () -> t.pick("a b", Target.Kind.BLOCK, "o", 0, 0, 0, null, "b", "", null));
    }

    @Test
    void persists(@TempDir Path dir) {
        Path f = dir.resolve("x").resolve("targets.txt");
        Targets t = new Targets(f);
        t.pick("duper", Target.Kind.CONTAINER, "overworld", -1, 64, 7, null, "minecraft:chest", "", null);
        t.pick("lib", Target.Kind.VILLAGER, "the_nether", 5, 70, 5, "123e4567", null, "librarian farmer", null);
        Targets back = new Targets(f);
        assertEquals(t.all(), back.all());
        assertEquals("librarian farmer", back.get("lib").label());
        assertNull(back.get("duper").uuid());
        back.clear();
        assertEquals(0, new Targets(f).size());
    }

    private static Targets sample() {
        Targets t = new Targets(null);
        t.pick("duper", Target.Kind.CONTAINER, "overworld", 10, 64, -20, null, "minecraft:chest", "", null);
        t.pick("stand1", Target.Kind.STATION, "overworld", 3, 70, 4, null, "minecraft:brewing_stand", "", "stand");
        t.pick("fisher", Target.Kind.VILLAGER, "overworld", 5, 64, 5, "abc-123", null, "fisherman", null);
        return t;
    }

    @Test
    void atNamesBecomeCoordinates() {
        Targets t = sample();
        assertEquals("#withdraw string all 10 64 -20", t.expand("#withdraw string all @duper"));
        assertEquals("#goto 10 64 -20", t.expand("#goto @DUPER"));
        assertEquals("#brew swiftness 3 at 3 70 4", t.expand("#brew swiftness 3 @stand1"));
        assertEquals("#trade sell string --with abc-123", t.expand("#trade sell string @fisher"));
        assertEquals("#goto 5 64 5", t.expand("#goto @fisher"));
        assertEquals("#drop dirt", t.expand("#drop dirt"));
    }

    @Test
    void modifierWordsAreNeverTargets() {
        Targets t = sample();
        assertEquals("#withdraw string all 10 64 -20 @retry 3 @onfail stop", t.expand("#withdraw string all @duper @retry 3 @onfail stop"));
        for (String w : dev.lazuli.core.task.Step.MODIFIERS) {
            assertFalse(Targets.validName(w), w);
            assertThrows(IllegalArgumentException.class, () -> t.pick(w, Target.Kind.BLOCK, "o", 0, 0, 0, null, "b", "", null));
        }
        assertEquals("#farm @if", t.expand("#farm @if"));
    }

    @Test
    void anUnknownTargetSaysWhichAndOffersNeighbours() {
        Targets t = sample();
        var e = assertThrows(IllegalArgumentException.class, () -> t.expand("#withdraw string @dupr"));
        assertTrue(e.getMessage().contains("No target @dupr"), e.getMessage());
        assertTrue(e.getMessage().contains("@duper"), e.getMessage());
    }

    @Test
    void resolveTakesNamesAtNamesAndUuids() {
        Targets t = sample();
        assertEquals("duper", t.resolve("duper").name());
        assertEquals("duper", t.resolve("@Duper").name());
        assertEquals("fisher", t.resolve("uuid:ABC-123").name());
        assertNull(t.resolve("uuid:nope"));
        assertNull(t.resolve(null));
    }
}
