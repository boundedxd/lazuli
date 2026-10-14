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

    private static double eval(GridWorld w, long u, long v) {
        return graph(w, true).evaluate(u, v);
    }

    @Test
    void physicsDecidesWhichGapsAreJumpable() {
        // standing 2-gap from a single block: fine
        assertTrue(eval(strip(0, 0, 3, 6), Pos.pack(0, 1, 0), Pos.pack(3, 1, 0)) < INF);
        // 3-gap with a 2-block run-up: fine
        assertTrue(eval(strip(-5, 0, 4, 8), Pos.pack(-2, 1, 0), Pos.pack(4, 1, 0)) < INF);
        // 5-gap: not in vanilla
        assertEquals(INF, eval(strip(-6, 0, 6, 9), Pos.pack(-1, 1, 0), Pos.pack(6, 1, 0)));
        // geometry alone rules out a 7-gap
        assertEquals(INF, graph(strip(-6, 0, 8, 9), true).optimisticCost(Pos.pack(0, 1, 0), Pos.pack(8, 1, 0)));
    }

    @Test
    void diagonalAndOddAngleJumps() {
        GridWorld w = new GridWorld(BlockKind.AIR).set(0, 0, 0, BlockKind.SOLID).set(2, 0, 2, BlockKind.SOLID)
            .set(5, 0, 3, BlockKind.SOLID);
        assertTrue(eval(w, Pos.pack(0, 1, 0), Pos.pack(2, 1, 2)) < INF, "diagonal 1-gap");
        assertTrue(eval(w, Pos.pack(2, 1, 2), Pos.pack(5, 1, 3)) < INF, "3-over-1-across");
    }

    @Test
    void optimisticNeverExceedsEvaluated() {
        GridWorld w = strip(-5, 0, 2, 2, 5, 5, 9, 12).fill(14, 1, 0, 15, 1, 0, BlockKind.SOLID);
        MoveGraph g = graph(w, true);
        for (int x = -5; x <= 15; x++) {
            for (int x2 = x - 7; x2 <= x + 7; x2++) {
                for (int dy = -3; dy <= 1; dy++) {
                    long u = Pos.pack(x, 1, 0), v = Pos.pack(x2, 1 + dy, 0);
                    assertTrue(g.evaluate(u, v) >= g.optimisticCost(u, v));
                }
            }
        }
    }

    @Test
    void lowCeilingOverTheGapBlocksTheJump() {
        GridWorld w = strip(-3, 0, 3, 6).set(1, 3, 0, BlockKind.SOLID);
        assertEquals(INF, graph(w, true).evaluate(Pos.pack(-1, 1, 0), Pos.pack(3, 1, 0)));
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
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int x = -12; x <= 12; x += 2) w.fill(x, 63, -12, x, 63, 12, BlockKind.SOLID); // striped floor
        MoveGraph g = graph(w, true);
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
        GridWorld w = strip(-3, 0, 3, 6); // a 2-gap: 1-gaps can be sprinted across without jumping
        PathFollower f = new PathFollower(w);
        List<Long> path = List.of(Pos.pack(0, 1, 0), Pos.pack(3, 1, 0));
        // sprinting, but the hitbox is still over the block when next tick's vertical move happens: not yet
        Controls early = f.tick(new PlayerState(1.2, 1, 0.5, -90f, 0, true, false, 0.153, -0.0784, 0, true), path, true);
        assertTrue(early.forward());
        assertFalse(early.jump());
        // hitbox already past the edge but still flagged on ground: last chance, jump now
        Controls late = f.tick(new PlayerState(1.45, 1, 0.5, -90f, 0, true, false, 0.153, -0.0784, 0, true), path, true);
        assertTrue(late.jump());
    }
}
