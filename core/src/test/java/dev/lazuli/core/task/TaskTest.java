package dev.lazuli.core.task;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {
    private static PathingSession session() {
        return new PathingSession(GridWorld.flatFloor(0, 0, 5, 5), () -> -64, () -> 319, new Settings(), Runnable::run, m -> {});
    }

    @Test
    void aTripIsDoneWhenItArrives() {
        PathingSession s = session();
        Tasks tasks = new Tasks();
        Task t = tasks.run(() -> Task.trip("go", s, s.start(new GoalBlock(2, 1, 0), false, Pos.pack(0, 1, 0))));
        tasks.tick();
        assertFalse(t.finished());
        s.playerMoved(Pos.pack(1, 1, 0));
        s.playerMoved(Pos.pack(2, 1, 0));
        tasks.tick();
        assertEquals(Task.Result.DONE, t.done().getNow(null));
        assertNull(tasks.current());
    }

    @Test
    void anUnreachableTripFails() {
        PathingSession s = session();
        Task t = Task.trip("go", s, s.start(new GoalBlock(30, 1, 2), false, Pos.pack(0, 1, 0)));
        t.tick();
        assertEquals(Task.Result.FAILED, t.done().getNow(null));
    }

    @Test
    void aTripIsCancelledByAStopOrANewTrip() {
        PathingSession s = session();
        Task a = Task.trip("a", s, s.start(new GoalBlock(2, 1, 0), false, Pos.pack(0, 1, 0)));
        Task b = Task.trip("b", s, s.start(new GoalBlock(3, 1, 0), false, Pos.pack(0, 1, 0)));
        a.tick();
        b.tick();
        assertEquals(Task.Result.CANCELLED, a.done().getNow(null));
        assertFalse(b.finished());
        s.stop(true);
        b.tick();
        assertEquals(Task.Result.CANCELLED, b.done().getNow(null));
    }

    @Test
    void runningANewTaskCancelsTheOldOneFirst() {
        PathingSession s = session();
        Tasks tasks = new Tasks();
        Task a = tasks.run(() -> Task.trip("a", s, s.start(new GoalBlock(2, 1, 0), false, Pos.pack(0, 1, 0))));
        Task b = tasks.run(() -> Task.trip("b", s, s.start(new GoalBlock(3, 1, 0), false, Pos.pack(0, 1, 0))));
        assertEquals(Task.Result.CANCELLED, a.done().getNow(null));
        // cancelling a stopped the session before b started, so b's trip is still on
        assertEquals(PathingSession.State.PATHING, s.snapshot().state());
        tasks.tick();
        assertFalse(b.finished());
    }
}
