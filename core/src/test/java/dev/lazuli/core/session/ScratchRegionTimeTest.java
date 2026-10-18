package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.AnvilWorld;
import dev.lazuli.core.world.Pos;
import dev.lazuli.core.world.WorldCache;
import org.junit.jupiter.api.Test;

class ScratchRegionTimeTest {
    @Test
    void time() throws Exception {
        var WORLD = RealWorldBenchTest.WORLD;
        double[] pp = AnvilWorld.playerPos(WORLD);
        int pcx = (int) Math.floor(pp[0]) >> 4, pcz = (int) Math.floor(pp[2]) >> 4;
        WorldCache w = AnvilWorld.load(WORLD, pcx - 12, pcx + 12, pcz - 12, pcz + 12);
        long start = Standing.nodeUnder(w, pp[0], pp[1], pp[2]);
        Interaction tools = new Interaction(true, true, 64, p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4)));
        for (boolean build : new boolean[]{false, true}) {
            PathingSession s = new PathingSession(w, () -> -64, () -> 319, new Settings(), Runnable::run, m -> {}, () -> build ? tools : Interaction.NONE);
            int gx = Pos.x(start) + 40, gz = Pos.z(start) + 30, gy = 319;
            while (!Standing.standable(w, gx, gy, gz)) gy--;
            long t0 = System.nanoTime();
            s.start(new GoalBlock(gx, gy, gz), false, start);
            System.out.println("PLAN build=" + build + " ms " + (System.nanoTime() - t0) / 1_000_000 + " " + s.snapshot().state() + " len " + s.snapshot().path().size());
            int bx = Pos.x(start) >> 4 << 4, bz = Pos.z(start) >> 4 << 4;
            for (int[] b : new int[][]{{bx, 40, bz, bx + 15, 120, bz + 15}, {bx + 16, 40, bz, bx + 31, 120, bz + 15}, {bx - 32, 0, bz - 32, bx + 47, 200, bz + 47}}) {
                t0 = System.nanoTime();
                s.regionChanged(b[0], b[1], b[2], b[3], b[4], b[5]);
                System.out.println("REGION " + java.util.Arrays.toString(b) + " ms " + (System.nanoTime() - t0) / 1_000_000);
            }
            t0 = System.nanoTime();
            s.setHazards(dev.lazuli.core.move.Hazards.of(java.util.List.of(new dev.lazuli.core.move.Hazards.Hazard(Pos.x(start) + 8, Pos.y(start), Pos.z(start) + 8, 8, 20))));
            System.out.println("HAZARD ms " + (System.nanoTime() - t0) / 1_000_000);
        }
    }
}
