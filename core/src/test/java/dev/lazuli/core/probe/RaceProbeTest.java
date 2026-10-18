package dev.lazuli.core.probe;

import dev.lazuli.core.physics.BoatSim;
import dev.lazuli.core.race.IceRacer;
import dev.lazuli.core.race.RaceLine;
import dev.lazuli.core.race.TrackField;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import org.junit.jupiter.api.Test;

/** Scratch: prints a racer run tick by tick. */
class RaceProbeTest {
    static final BlockPhysics BLUE_ICE = BlockPhysics.intern(BlockPhysics.FULL.boxes, 0.989f, 1, 1, 0, 2.8f, 9001);

    @Test
    void probe() {
        String[] rows = {
            "S...........                    ",
            ".............                   ",
            "..............                  ",
            "...............                 ",
            " ...............                ",
            "   ..............               ",
            "     ...........................",
            "       ........................G",
            "         .......................",
            "            ...................."};
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (int z = 0; z < rows.length; z++)
            for (int x = 0; x < rows[z].length(); x++)
                if (rows[z].charAt(x) != ' ') w.setPhysics(x, 0, z, BLUE_ICE);
        TrackField f = TrackField.toGoal(w, 0, 1, 0, 31, 1, 7, 1.5, 100000);
        System.out.println("cells " + f.cellCount());
        IceRacer racer = new IceRacer(w).beam(24).budget(Long.MAX_VALUE);
        BoatSim sim = new BoatSim(racer.view(), 0.5, 1, 0.5, 0, 0, 0, -90, 0);
        RaceLine.Cursor cur = f.start(sim.x, sim.y, sim.z);
        IceRacer.Keys pending = IceRacer.Keys.NONE;
        for (int t = 0; t < 60; t++) {
            long t0 = System.nanoTime();
            IceRacer.Keys k = racer.next(sim, pending, f, cur);
            long dt = System.nanoTime() - t0;
            sim.step(pending.left(), pending.right(), pending.forward(), pending.back());
            pending = k;
            double g = f.toGo(cur, sim.x, sim.y, sim.z);
            System.out.printf("t%d %.2f %.2f %.2f yaw %.1f spin %.2f v %.2f %s toGo %.2f %s %dus%n", t, sim.x, sim.y, sim.z, sim.yaw,
                sim.spin, sim.speed(), sim.status, g, k, dt / 1000);
            if (Double.isNaN(g)) break;
        }
    }
}
