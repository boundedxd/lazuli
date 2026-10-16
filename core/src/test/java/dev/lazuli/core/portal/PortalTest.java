package dev.lazuli.core.portal;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PortalTest {
    @Test
    void frameHasTenObsidianFourCornersAndASixBlockOpening() {
        PortalFrame f = new PortalFrame(0, 1, 0, true);
        assertEquals(10, f.cells().stream().filter(c -> c.part() == PortalFrame.Part.OBSIDIAN).count());
        assertEquals(4, f.cells().stream().filter(c -> c.part() == PortalFrame.Part.CORNER).count());
        assertEquals(6, f.cells().stream().filter(c -> c.part() == PortalFrame.Part.INSIDE).count());
        assertEquals(PortalFrame.Part.OBSIDIAN, f.ignite().part());
        assertEquals(new PortalFrame.Cell(1, 2, 0, PortalFrame.Part.INSIDE), f.entrance());
        // bottom row first: every cell placed has the one below it (or the ground) done already
        assertEquals(1, f.cells().get(0).y());
        assertEquals(5, f.cells().get(f.cells().size() - 1).y());
        PortalFrame z = new PortalFrame(0, 1, 0, false);
        assertEquals(new PortalFrame.Cell(0, 1, 3, PortalFrame.Part.CORNER), z.cell(3, 0));
    }

    @Test
    void findsASpotOnOpenGround() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10);
        PortalFrame.Site s = PortalFrame.find(w, 0, 1, 0);
        assertNotNull(s);
        assertEquals(1, s.frame().y());
        assertEquals(0, PortalFrame.breaks(w, s.frame()));
        // the stand spot is outside the frame, in front of the opening
        for (PortalFrame.Cell c : s.frame().cells()) {
            assertFalse(c.x() == s.standX() && c.z() == s.standZ(), "stands in the frame");
        }
    }

    @Test
    void avoidsWaterAndLava() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10)
            .fill(-10, 1, -10, 10, 1, 10, BlockKind.WATER).fill(3, 1, 3, 10, 1, 10, BlockKind.AIR);
        PortalFrame.Site s = PortalFrame.find(w, 4, 1, 4);
        assertNotNull(s);
        for (PortalFrame.Cell c : s.frame().cells()) assertNotEquals(BlockKind.WATER, w.get(c.x(), c.y(), c.z()));
        assertNull(PortalFrame.find(GridWorld.flatFloor(-10, -10, 10, 10).fill(-10, 1, -10, 10, 6, 10, BlockKind.DANGER), 0, 1, 0));
    }

    @Test
    void prefersOpenSpaceOverDigging() {
        // stone to the east, open to the west
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10).fill(1, 1, -10, 10, 8, 10, BlockKind.SOLID);
        PortalFrame.Site s = PortalFrame.find(w, 0, 1, 0);
        assertNotNull(s);
        assertEquals(0, PortalFrame.breaks(w, s.frame()));
    }

    @Test
    void routeOnlyWhenFarAndWorthIt() {
        assertFalse(NetherRoute.worthIt(300, 1000, -1, false));
        assertTrue(NetherRoute.worthIt(5000, 1000, -1, false));
        assertFalse(NetherRoute.worthIt(1000, 100, 900, false)); // the known portal is the wrong way
        assertEquals(2, NetherRoute.builds(-1, false));
        assertEquals(0, NetherRoute.builds(20, true));
        assertEquals(-2, NetherRoute.toNether(-9));
        assertEquals(100, NetherRoute.toNether(807));
    }

    @Test
    void memoryDedupesAndPersists() throws Exception {
        Path f = Files.createTempDirectory("lazuli").resolve("portals.txt");
        PortalMemory m = new PortalMemory(f);
        assertTrue(m.add("overworld", 10, 64, 10));
        assertFalse(m.add("overworld", 11, 65, 10)); // same portal
        assertTrue(m.add("the_nether", 1, 70, 1));
        assertTrue(m.add("overworld", 500, 64, 500));
        PortalMemory again = new PortalMemory(f);
        assertEquals(new PortalMemory.Portal("overworld", 10, 64, 10), again.nearest("overworld", 0, 0));
        assertEquals(new PortalMemory.Portal("the_nether", 1, 70, 1), again.nearest("the_nether", 400, 400));
        assertTrue(again.remove("overworld", 10, 64, 11));
        assertEquals(new PortalMemory.Portal("overworld", 500, 64, 500), again.nearest("overworld", 0, 0));
        assertNull(again.nearest("the_end", 0, 0));
    }
}
