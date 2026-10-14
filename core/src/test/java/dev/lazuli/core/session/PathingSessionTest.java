package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathingSessionTest {
    private final List<String> msgs = new ArrayList<>();

    private PathingSession session(BlockView w) {
        return new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, msgs::add);
    }

    @Test
    void findsPathOnFlatGround() {
        PathingSession s = session(GridWorld.flatFloor(0, 0, 20, 20));
        s.start(new GoalBlock(15, 1, 3), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        List<Long> path = s.snapshot().path();
        assertEquals(Pos.pack(15, 1, 3), path.get(path.size() - 1));
    }

    @Test
    void goalInUnknownTerrainIsPlanned() {
        GridWorld w = new GridWorld(BlockKind.UNKNOWN)
            .fill(-2, 0, -2, 2, 0, 2, BlockKind.SOLID).fill(-2, 1, -2, 2, 3, 2, BlockKind.AIR);
        PathingSession s = session(w);
        s.start(new GoalBlock(200, 1, 0), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
    }

    @Test
    void blockOnPathTriggersRepairAroundIt() {
        GridWorld w = GridWorld.flatFloor(0, -5, 10, 5);
        PathingSession s = session(w);
        s.start(new GoalBlock(10, 1, 0), false, Pos.pack(0, 1, 0));
        w.fill(5, 1, -5, 5, 2, 4, BlockKind.SOLID);
        s.regionChanged(5, 1, -5, 5, 2, 4);
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        assertTrue(s.snapshot().path().contains(Pos.pack(5, 1, 5)),
            s.snapshot().path().stream().map(Pos::toString).toList().toString());
    }

    @Test
    void enclosedPlayerUsesFallback() {
        GridWorld w = GridWorld.flatFloor(0, 0, 4, 4)
            .fill(-1, 1, -1, 5, 3, -1, BlockKind.SOLID).fill(-1, 1, 5, 5, 3, 5, BlockKind.SOLID)
            .fill(-1, 1, -1, -1, 3, 5, BlockKind.SOLID).fill(5, 1, -1, 5, 3, 5, BlockKind.SOLID)
            .fill(-1, 4, -1, 5, 4, 5, BlockKind.SOLID);
        PathingSession s = session(w);
        s.start(new GoalBlock(30, 1, 2), true, Pos.pack(0, 1, 0));
        assertEquals(new GoalBlock(4, 1, 2), s.snapshot().goal());
        assertTrue(msgs.stream().anyMatch(m -> m.contains("nearest safe spot")));
    }

    @Test
    void unreachableWithoutFallbackFails() {
        PathingSession s = session(GridWorld.flatFloor(0, 0, 4, 4));
        s.start(new GoalBlock(30, 1, 2), false, Pos.pack(0, 1, 0));
        assertEquals(PathingSession.State.FAILED, s.snapshot().state());
        assertTrue(msgs.contains("Goal unreachable"));
    }

    @Test
    void arrivesWhenPlayerReachesGoal() {
        PathingSession s = session(GridWorld.flatFloor(0, 0, 5, 5));
        s.start(new GoalBlock(2, 1, 0), false, Pos.pack(0, 1, 0));
        s.playerMoved(Pos.pack(1, 1, 0));
        s.playerMoved(Pos.pack(2, 1, 0));
        assertEquals(PathingSession.State.ARRIVED, s.snapshot().state());
    }
}
