package dev.lazuli.core.control;

import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuilderTest {
    static final class Hands implements Builder.Hands {
        final List<Long> mined = new ArrayList<>();
        public boolean selectThrowaway() { return false; }
        public void place(long pos, long against) {}
        public void mine(long pos) { if (!mined.contains(pos)) mined.add(pos); }
        public boolean breakable(long pos) { return true; }
        public void outOfBlocks() {}
        public double eyeHeight() { return Builder.EYE; }
    }

    @Test
    void breaksWhatHidesTheBlockToBreakButNeverTheFloor() {
        // standing at x 0.5 in a 1x2 pocket in stone; the block to break is out of sight behind the wall
        GridWorld w = GridWorld.flatFloor(-4, -4, 4, 4).fill(-4, 1, -4, 4, 4, 4, BlockKind.SOLID).fill(0, 1, 0, 0, 2, 0, BlockKind.AIR);
        Hands hands = new Hands();
        Builder b = new Builder(w, hands);
        long hidden = Pos.pack(2, 1, 0);
        float yaw = 0, pitch = 0;
        for (int t = 0; t < 40; t++) {
            PlayerState p = new PlayerState(0.5, 1, 0.5, yaw, pitch, true, false, 0, 0, 0, false, 0, false, 0);
            Controls c = b.mineOrClear(p, hidden);
            assertNotNull(c);
            yaw = c.targetYaw();
            pitch = c.targetPitch();
        }
        assertFalse(hands.mined.isEmpty(), "mines what's in the way");
        long first = hands.mined.get(0);
        assertEquals(1, Pos.x(first), Pos.toString(first));
        assertTrue(Pos.y(first) >= 1, "not the floor: " + Pos.toString(first));
    }
}
