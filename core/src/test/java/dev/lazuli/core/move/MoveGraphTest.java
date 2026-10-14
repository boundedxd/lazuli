package dev.lazuli.core.move;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MoveGraphTest {
    private static final long O = Pos.pack(0, 1, 0);
    private static final double INF = Double.POSITIVE_INFINITY;

    @Test
    void flatSprintCost() {
        MoveGraph g = new MoveGraph(GridWorld.flatFloor(-5, -5, 5, 5), 3, true);
        assertEquals(Costs.SPRINT_ONE, g.optimisticCost(O, Pos.pack(1, 1, 0)), 1e-9);
        assertEquals(Costs.SPRINT_ONE * Costs.SQRT2, g.optimisticCost(O, Pos.pack(1, 1, 1)), 1e-9);
    }

    @Test
    void diagonalBlockedByCorner() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.SOLID);
        assertEquals(INF, new MoveGraph(w, 3, true).optimisticCost(O, Pos.pack(1, 1, 1)));
    }

    @Test
    void ascendNeedsHeadroomAboveStart() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.SOLID);
        MoveGraph g = new MoveGraph(w, 3, true);
        assertEquals(Costs.SPRINT_ONE + Costs.JUMP_PENALTY, g.optimisticCost(O, Pos.pack(1, 2, 0)), 1e-9);
        w.set(0, 3, 0, BlockKind.SOLID);
        assertEquals(INF, g.optimisticCost(O, Pos.pack(1, 2, 0)));
    }

    @Test
    void fallRespectsMaxFall() {
        GridWorld w = new GridWorld(BlockKind.AIR).set(0, 10, 0, BlockKind.SOLID).set(1, 6, 0, BlockKind.SOLID);
        long top = Pos.pack(0, 11, 0);
        assertEquals(Costs.SPRINT_ONE + Costs.fallTicks(4),
            new MoveGraph(w, 4, true).optimisticCost(top, Pos.pack(1, 7, 0)), 1e-9);
        assertEquals(INF, new MoveGraph(w, 3, true).optimisticCost(top, Pos.pack(1, 7, 0)));
    }

    @Test
    void dangerAndObstacleAreNotWalkable() {
        GridWorld w = GridWorld.flatFloor(-5, -5, 5, 5).set(1, 1, 0, BlockKind.DANGER).set(-1, 0, 0, BlockKind.DANGER);
        MoveGraph g = new MoveGraph(w, 3, true);
        assertFalse(g.isNode(Pos.pack(1, 1, 0)));
        assertFalse(g.isNode(Pos.pack(-1, 1, 0)));
    }

    @Test
    void unknownIsFreespace() {
        MoveGraph g = new MoveGraph(new GridWorld(BlockKind.UNKNOWN), 3, true);
        assertTrue(g.isNode(Pos.pack(100, 64, 100)));
        assertEquals(Costs.SPRINT_ONE, g.optimisticCost(Pos.pack(100, 64, 100), Pos.pack(101, 64, 100)), 1e-9);
    }

    @Test
    void fallTicksIncrease() {
        assertEquals(5.0, Costs.fallTicks(1), 0);
        assertTrue(Costs.fallTicks(3) > Costs.fallTicks(2));
    }

    @Test
    void heuristicIsAdmissibleOnEveryEdge() {
        MoveGraph g = new MoveGraph(new GridWorld(BlockKind.UNKNOWN), 3, true);
        g.successors(O, v -> {
            double c = g.optimisticCost(O, v);
            if (c != INF) assertTrue(g.heuristic(O, v) <= c + 1e-9);
        });
    }

    @Test
    void predecessorsMirrorSuccessors() {
        MoveGraph g = new MoveGraph(new GridWorld(BlockKind.UNKNOWN), 3, true);
        g.successors(O, v -> {
            Set<Long> pv = new HashSet<>();
            g.predecessors(v, pv::add);
            assertTrue(pv.contains(O));
        });
    }

    @Test
    void findsNearestSafeSpotInsideAClosedRoom() {
        GridWorld w = GridWorld.flatFloor(0, 0, 4, 4)
            .fill(-1, 1, -1, 5, 2, -1, BlockKind.SOLID).fill(-1, 1, 5, 5, 2, 5, BlockKind.SOLID)
            .fill(-1, 1, -1, -1, 2, 5, BlockKind.SOLID).fill(5, 1, -1, 5, 2, 5, BlockKind.SOLID);
        MoveGraph g = new MoveGraph(w, 3, true);
        OptionalLong spot = SafeSpotFinder.find(g, w, Pos.pack(0, 1, 0), new GoalBlock(20, 1, 2), 10_000);
        assertEquals(Pos.pack(4, 1, 2), spot.orElseThrow());
    }

    @Test
    void lavaNeighbourIsUnsafe() {
        GridWorld w = GridWorld.flatFloor(0, 0, 4, 4).set(3, 1, 0, BlockKind.DANGER);
        assertFalse(SafeSpotFinder.isSafe(w, 2, 1, 0));
        assertTrue(SafeSpotFinder.isSafe(w, 0, 1, 4));
    }
}
