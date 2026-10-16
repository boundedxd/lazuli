package dev.lazuli.core.move;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiggingTest {
    static final Interaction BREAK = new Interaction(true, false, 0, p -> Interaction.handTicks(p, false));
    static final Interaction PLACE = new Interaction(false, true, 64, p -> Double.POSITIVE_INFINITY);
    static final Interaction BOTH = new Interaction(true, true, 64, p -> Interaction.handTicks(p, false));

    static List<Long> plan(GridWorld w, Interaction in, long start, int gx, int gy, int gz) {
        Settings s = new Settings();
        s.allowParkour.set(false);
        List<String> msgs = new ArrayList<>();
        PathingSession session = new PathingSession(w, () -> -64, () -> 319, s, Runnable::run, msgs::add, () -> in);
        session.start(new GoalBlock(gx, gy, gz), false, start);
        PathingSession.Snapshot snap = session.snapshot();
        return snap.state() == PathingSession.State.PATHING ? snap.path() : null;
    }

    static MoveGraph graph(GridWorld w, Interaction in) {
        return new MoveGraph(w, 3, true, 1, false, in);
    }

    @Test
    void tunnelsThroughAWallItCannotGoAround() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10).fill(3, 1, -10, 3, 3, 10, BlockKind.SOLID);
        long start = Pos.pack(0, 1, 0);
        assertNull(plan(w, Interaction.NONE, start, 6, 1, 0));
        List<Long> path = plan(w, BREAK, start, 6, 1, 0);
        assertNotNull(path);
        assertTrue(path.stream().anyMatch(n -> Pos.x(n) == 3 && Pos.y(n) == 1), "goes through the wall: " + str(path));
        MoveGraph g = graph(w, BREAK);
        int k = 0;
        while (Pos.x(path.get(k + 1)) != 3) k++;
        List<Digging.Action> acts = g.actions(path.get(k), path.get(k + 1));
        assertEquals(2, acts.size(), "breaks head then feet: " + acts);
        assertFalse(acts.get(0).place());
        assertEquals(2, Pos.y(acts.get(0).pos()));
        assertEquals(1, Pos.y(acts.get(1).pos()));
    }

    @Test
    void walksAroundAShortWallInsteadOfDigging() {
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10).fill(3, 1, -1, 3, 2, 1, BlockKind.SOLID);
        List<Long> path = plan(w, BOTH, Pos.pack(0, 1, 0), 6, 1, 0);
        assertNotNull(path);
        MoveGraph g = graph(w, BOTH);
        for (int i = 0; i + 1 < path.size(); i++) assertTrue(g.actions(path.get(i), path.get(i + 1)).isEmpty(), str(path));
    }

    @Test
    void bridgesAGap() {
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-5, 0, -2, 0, 0, 2, BlockKind.SOLID).fill(5, 0, -2, 10, 0, 2, BlockKind.SOLID);
        long start = Pos.pack(0, 1, 0);
        assertNull(plan(w, Interaction.NONE, start, 8, 1, 0));
        List<Long> path = plan(w, PLACE, start, 8, 1, 0);
        assertNotNull(path);
        assertEquals(Pos.pack(1, 1, 0), path.get(1), str(path));
        List<Digging.Action> acts = graph(w, PLACE).actions(path.get(0), path.get(1));
        assertEquals(List.of(new Digging.Action(true, Pos.pack(1, 0, 0), Pos.pack(0, 0, 0))), acts);
    }

    @Test
    void pillarsUpToAGoalInTheAir() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5);
        List<Long> path = plan(w, PLACE, Pos.pack(0, 1, 0), 0, 4, 0);
        assertNotNull(path);
        assertEquals(List.of(Pos.pack(0, 1, 0), Pos.pack(0, 2, 0), Pos.pack(0, 3, 0), Pos.pack(0, 4, 0)), path);
        assertEquals(List.of(new Digging.Action(true, Pos.pack(0, 1, 0), Pos.pack(0, 0, 0))),
            graph(w, PLACE).actions(path.get(0), path.get(1)));
    }

    @Test
    void minesStraightDown() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).fill(-5, -6, -5, 5, -1, 5, BlockKind.SOLID);
        List<Long> path = plan(w, BREAK, Pos.pack(0, 1, 0), 0, -2, 0);
        assertNotNull(path);
        assertEquals(List.of(Pos.pack(0, 1, 0), Pos.pack(0, 0, 0), Pos.pack(0, -1, 0), Pos.pack(0, -2, 0)), path);
        assertEquals(List.of(new Digging.Action(false, Pos.pack(0, 0, 0), 0)),
            graph(w, BREAK).actions(path.get(0), path.get(1)));
    }

    @Test
    void refusesUnsafeBreaks() {
        BlockPhysics gravel = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.6f, 1, 1, BlockPhysics.FALLING, 0.6f, 13);
        BlockPhysics bedrock = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.6f, 1, 1, 0, -1, 14);
        BlockPhysics lava = BlockPhysics.intern(new double[0], 0.6f, 1, 1, BlockPhysics.LAVA, -1, 15);
        GridWorld w = GridWorld.flatFloor(-10, -10, 10, 10)
            .set(0, 1, 0, BlockKind.SOLID).set(1, 1, 0, BlockKind.WATER)          // water beside
            .set(0, 1, 3, BlockKind.SOLID).setPhysics(0, 2, 3, gravel)            // gravel above
            .setPhysics(0, 1, 6, bedrock)
            .set(0, 1, 9, BlockKind.SOLID).setPhysics(0, 1, 8, lava)              // lava beside
            .set(5, 1, 0, BlockKind.SOLID);
        Digging d = new Digging(w, BREAK);
        assertEquals(Double.POSITIVE_INFINITY, d.clearCost(0, 1, 0, false));
        assertEquals(Double.POSITIVE_INFINITY, d.clearCost(0, 1, 3, false));
        assertTrue(d.clearCost(0, 1, 3, true) < Double.POSITIVE_INFINITY, "fine once the gravel is mined first");
        assertEquals(Double.POSITIVE_INFINITY, d.clearCost(0, 1, 6, false));
        assertEquals(Double.POSITIVE_INFINITY, d.clearCost(0, 1, 9, false));
        assertEquals(Digging.BREAK_OVERHEAD + 45, d.clearCost(5, 1, 0, false), 1e-9);
        assertEquals(0, d.clearCost(5, 2, 0, false));
        assertEquals(Double.POSITIVE_INFINITY, new Digging(w, Interaction.NONE).clearCost(5, 1, 0, false));
    }

    @Test
    void withoutInteractionOnlyRealNodesExist() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.SOLID);
        assertFalse(graph(w, Interaction.NONE).isNode(Pos.pack(1, 1, 0)));
        assertFalse(graph(w, Interaction.NONE).isNode(Pos.pack(0, 3, 0)));
        assertTrue(graph(w, BREAK).isNode(Pos.pack(1, 1, 0)));
        assertTrue(graph(w, PLACE).isNode(Pos.pack(0, 3, 0)));
        assertFalse(graph(w, BREAK).isNode(Pos.pack(0, 3, 0)));
    }

    static String str(List<Long> path) {
        StringBuilder sb = new StringBuilder();
        for (long n : path) sb.append(Pos.toString(n)).append(' ');
        return sb.toString();
    }

    /** A flower, leaf litter, wildflowers: no collision, breaks at once, but nothing can be placed into it as it is. */
    static final BlockPhysics PLANT = BlockPhysics.intern(new double[0], 0.6f, 1, 1, BlockPhysics.SOFT, 0f, 424242);

    @Test
    void pillarsOutOfAFlowerBreakingItFirst() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).setPhysics(0, 1, 0, PLANT);
        List<Long> path = plan(w, BOTH, Pos.pack(0, 1, 0), 0, 4, 0);
        assertNotNull(path, "should pillar up out of the flower");
        assertEquals(Pos.pack(0, 2, 0), path.get(1), str(path));
        assertEquals(List.of(new Digging.Action(false, Pos.pack(0, 1, 0), 0), new Digging.Action(true, Pos.pack(0, 1, 0), Pos.pack(0, 0, 0))),
            graph(w, BOTH).actions(path.get(0), path.get(1)));
        // can't break it: steps out of the flower to pillar beside it
        List<Long> aside = plan(w, PLACE, Pos.pack(0, 1, 0), 0, 4, 0);
        assertNotNull(aside);
        assertNotEquals(Pos.pack(0, 2, 0), aside.get(1), str(aside));
    }

    @Test
    void bridgesIntoLeafLitterBreakingItFirst() {
        // a ditch one deep with leaf litter in it, then a gap: the bridge block goes where the litter is
        GridWorld w = new GridWorld(BlockKind.AIR).fill(-5, 0, -2, 0, 0, 2, BlockKind.SOLID)
            .fill(1, -1, -2, 1, -1, 2, BlockKind.SOLID).setPhysics(1, 0, 0, PLANT);
        List<Digging.Action> acts = graph(w, BOTH).actions(Pos.pack(0, 1, 0), Pos.pack(1, 1, 0));
        assertEquals(List.of(new Digging.Action(false, Pos.pack(1, 0, 0), 0), new Digging.Action(true, Pos.pack(1, 0, 0), Pos.pack(0, 0, 0))), acts);
    }
}
