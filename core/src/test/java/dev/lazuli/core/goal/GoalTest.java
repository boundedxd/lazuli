package dev.lazuli.core.goal;

import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalTest {
    private static List<Long> candidates(Goal g, int px, int py, int pz) {
        List<Long> out = new ArrayList<>();
        g.forEachCandidate(px, py, pz, -64, 319, out::add);
        return out;
    }

    @Test
    void goalBlockHasExactlyOneCandidate() {
        Goal g = new GoalBlock(10, 64, -5);
        assertTrue(g.isGoal(10, 64, -5));
        assertFalse(g.isGoal(10, 65, -5));
        assertEquals(List.of(Pos.pack(10, 64, -5)), candidates(g, 0, 0, 0));
        assertEquals(5.0, g.distance(13, 64, -1), 1e-9);
    }

    @Test
    void goalXZCoversTheWholeColumn() {
        Goal g = new GoalXZ(3, 4);
        assertTrue(g.isGoal(3, -64, 4));
        assertEquals(384, candidates(g, 0, 0, 0).size());
        assertEquals(0.0, g.distance(3, 100, 4), 1e-9);
    }

    @Test
    void goalYLevelCandidatesAreBoundedAroundThePlayer() {
        int side = 2 * GoalYLevel.CANDIDATE_RADIUS + 1;
        assertEquals(side * side, candidates(new GoalYLevel(12), 100, 70, 100).size());
        assertEquals(0, candidates(new GoalYLevel(-100), 0, 0, 0).size());
        assertEquals(58.0, new GoalYLevel(12).distance(0, 70, 0), 1e-9);
    }

    @Test
    void goalNearIsASphere() {
        Goal g = new GoalNear(0, 64, 0, 2);
        assertTrue(g.isGoal(2, 64, 0));
        assertFalse(g.isGoal(2, 65, 1));
        for (long c : candidates(g, 0, 0, 0)) assertTrue(g.isGoal(Pos.x(c), Pos.y(c), Pos.z(c)));
        assertEquals(3.0, g.distance(5, 64, 0), 1e-9);
    }
}
