package dev.lazuli.core.control;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HopTest {
    private static List<Long> zigzag(int n) {
        List<Long> l = new ArrayList<>();
        int z = 0;
        for (int x = 0; x <= n; x++) {
            l.add(Pos.pack(x, 1, z));
            if (x % 2 == 0 && z < 3) z++;
        }
        return l;
    }

    private static final PlayerState START = new PlayerState(0.5, 1, 0.5, -90f, 0, true, false);

    @Test
    void hopsAlongLongClearFlatStraight() {
        PathFollower f = new PathFollower(GridWorld.flatFloor(-2, -5, 30, 10));
        Controls c = f.tick(new PlayerState(0.5, 1, 0.5, -90f, 0, true, false), zigzag(12), true);
        assertTrue(c.forward());
        assertTrue(c.sprint());
        assertTrue(c.jump());
    }

    @Test
    void aimsPastTheZigzagInsteadOfAtTheNextNode() {
        PathFollower f = new PathFollower(GridWorld.flatFloor(-2, -5, 30, 10));
        Controls c = f.tick(START, zigzag(12), true);
        float towardNextNode = PathFollower.yawTo(0.5, 0.5, 1.5, 0.5);
        assertNotEquals(towardNextNode, c.targetYaw(), 0.5f);
    }

    @Test
    void doesNotHopWhenALedgeBreaksTheLine() {
        GridWorld w = GridWorld.flatFloor(-2, -5, 30, 10).set(3, 0, 0, BlockKind.AIR).set(3, 0, 1, BlockKind.AIR)
            .set(3, 0, 2, BlockKind.AIR).set(3, 0, 3, BlockKind.AIR);
        List<Long> straight = new ArrayList<>();
        for (int x = 0; x <= 12; x++) straight.add(Pos.pack(x, 1, 0));
        Controls c = new PathFollower(w).tick(START, straight, true);
        assertFalse(c.jump());
    }

    @Test
    void doesNotHopNearTheEndOfThePath() {
        PathFollower f = new PathFollower(GridWorld.flatFloor(-2, -5, 30, 10));
        List<Long> shortPath = new ArrayList<>();
        for (int x = 0; x <= 3; x++) shortPath.add(Pos.pack(x, 1, 0));
        assertFalse(f.tick(START, shortPath, true).jump());
    }

    @Test
    void doesNotHopWhenSprintIsDisallowed() {
        PathFollower f = new PathFollower(GridWorld.flatFloor(-2, -5, 30, 10));
        List<Long> straight = new ArrayList<>();
        for (int x = 0; x <= 12; x++) straight.add(Pos.pack(x, 1, 0));
        assertFalse(f.tick(START, straight, false).jump());
    }
}
