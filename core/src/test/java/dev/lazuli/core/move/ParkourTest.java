package dev.lazuli.core.move;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.PathFollower;
import dev.lazuli.core.control.PlayerState;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ParkourTest {
    private static final double INF = Double.POSITIVE_INFINITY;

    /** Floor at y=0 on the given x ranges (inclusive pairs), z=0 only; air elsewhere. */
    private static GridWorld strip(int... ranges) {
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int i = 0; i < ranges.length; i += 2) w.fill(ranges[i], 0, 0, ranges[i + 1], 0, 0, BlockKind.SOLID);
        return w;
    }

    private static MoveGraph graph(GridWorld w, boolean sprint) {
        return new MoveGraph(w, 3, sprint, 1.0, true);
    }

    @Test
    void oneBlockGapJumpExistsFromTheEdgeBlock() {
        MoveGraph g = graph(strip(-3, 0, 2, 5), false);
        assertTrue(g.optimisticCost(Pos.pack(0, 1, 0), Pos.pack(2, 1, 0)) < INF);
    }

    @Test
    void twoBlockGapNeedsRunupAndSprint() {
        GridWorld w = strip(-3, 0, 3, 6);
        assertTrue(graph(w, true).optimisticCost(Pos.pack(-1, 1, 0), Pos.pack(3, 1, 0)) < INF);
        assertEquals(INF, graph(w, false).optimisticCost(Pos.pack(-1, 1, 0), Pos.pack(3, 1, 0)));
        assertEquals(INF, graph(strip(0, 0, 3, 6), true).optimisticCost(Pos.pack(-1, 1, 0), Pos.pack(3, 1, 0)));
    }

    @Test
    void threeBlockGapWithTwoBlockRunup() {
        GridWorld w = strip(-5, 0, 4, 8);
        assertTrue(graph(w, true).optimisticCost(Pos.pack(-2, 1, 0), Pos.pack(4, 1, 0)) < INF);
    }

    @Test
    void lowCeilingOverTheGapBlocksTheJump() {
        GridWorld w = strip(-3, 0, 3, 6).set(1, 3, 0, BlockKind.SOLID);
        assertEquals(INF, graph(w, true).optimisticCost(Pos.pack(-1, 1, 0), Pos.pack(3, 1, 0)));
    }

    @Test
    void jumpUpOneOverAGap() {
        GridWorld w = strip(-3, 0).fill(2, 1, 0, 5, 1, 0, BlockKind.SOLID);
        assertTrue(graph(w, true).optimisticCost(Pos.pack(-1, 1, 0), Pos.pack(2, 2, 0)) < INF);
    }

    @Test
    void lavaInTheGapIsJumpedOver() {
        GridWorld w = strip(-3, 0, 3, 6).set(1, 0, 0, BlockKind.DANGER).set(2, 0, 0, BlockKind.DANGER);
        assertTrue(graph(w, true).optimisticCost(Pos.pack(-1, 1, 0), Pos.pack(3, 1, 0)) < INF);
    }

    @Test
    void parkourOffMeansNoJumps() {
        MoveGraph g = new MoveGraph(strip(-3, 0, 2, 5), 3, true, 1.0, false);
        assertEquals(INF, g.optimisticCost(Pos.pack(0, 1, 0), Pos.pack(2, 1, 0)));
    }

    @Test
    void jumpPredecessorsMirrorSuccessors() {
        MoveGraph g = graph(new GridWorld(BlockKind.UNKNOWN), true);
        long o = Pos.pack(0, 64, 0);
        g.successors(o, v -> {
            Set<Long> pv = new HashSet<>();
            g.predecessors(v, pv::add);
            assertTrue(pv.contains(o), "missing predecessor for " + Pos.toString(v));
        });
    }

    @Test
    void plansAcrossAParkourCourse() {
        // island A, 2-gap, island B, 1-gap up one block, island C
        GridWorld w = strip(0, 4, 7, 10).fill(12, 1, 0, 16, 1, 0, BlockKind.SOLID);
        List<String> msgs = new ArrayList<>();
        PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add);
        s.start(new GoalBlock(16, 2, 0), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state(), msgs.toString());
        assertEquals(Pos.pack(16, 2, 0), s.snapshot().path().get(s.snapshot().path().size() - 1));
    }

    @Test
    void followerTakesOffOnTheLastSupportedTick() {
        GridWorld w = strip(-3, 0, 2, 5);
        PathFollower f = new PathFollower(w);
        List<Long> path = List.of(Pos.pack(0, 1, 0), Pos.pack(2, 1, 0));
        // sprinting, but the hitbox is still over the block when next tick's vertical move happens: not yet
        Controls early = f.tick(new PlayerState(1.2, 1, 0.5, -90f, 0, true, false, 0.153, -0.0784, 0, true), path, true);
        assertTrue(early.forward());
        assertFalse(early.jump());
        // hitbox already past the edge but still flagged on ground: last chance, jump now
        Controls late = f.tick(new PlayerState(1.45, 1, 0.5, -90f, 0, true, false, 0.153, -0.0784, 0, true), path, true);
        assertTrue(late.jump());
    }
}
