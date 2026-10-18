package dev.lazuli.core.session;

import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.testutil.AnvilWorld;
import dev.lazuli.core.world.Pos;
import dev.lazuli.core.world.WorldCache;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Times the first plan on the dev client's saved world, the way the game runs it (PathingSession, segments, budgets).
 * Read-only. Run with ./gradlew :core:benchmark --tests '*RealWorldBench*'.
 */
@Tag("benchmark")
class RealWorldBenchTest {
    static final Path WORLD = Path.of("../mod/run/saves/New World");
    static final int VIEW_CHUNKS = 12;

    @Test
    void firstPlanOnRealTerrain() throws Exception {
        Assumptions.assumeTrue(Files.isDirectory(WORLD.resolve("region")));
        double[] pp = AnvilWorld.playerPos(WORLD);
        int pcx = (int) Math.floor(pp[0]) >> 4, pcz = (int) Math.floor(pp[2]) >> 4;
        long t0 = System.nanoTime();
        WorldCache w = AnvilWorld.load(WORLD, pcx - VIEW_CHUNKS, pcx + VIEW_CHUNKS, pcz - VIEW_CHUNKS, pcz + VIEW_CHUNKS);
        System.out.printf("BENCH loaded around %.1f %.1f %.1f in %d ms%n", pp[0], pp[1], pp[2], (System.nanoTime() - t0) / 1_000_000);
        long start = Standing.nodeUnder(w, pp[0], pp[1], pp[2]);
        Interaction tools = new Interaction(true, true, 64, p -> p.hardness < 0 ? Double.POSITIVE_INFINITY : Math.max(1, Math.ceil(p.hardness * 4)));
        int[][] goals = {{259, 76, 355}, {207, 80, 334}, {Pos.x(start) + 40, -1, Pos.z(start) + 30}, {Pos.x(start) - 60, -1, Pos.z(start) - 50}};
        for (int[] g : goals) {
            int gy = g[1];
            if (gy < 0) { // the surface there
                gy = 319;
                while (gy > -64 && !Standing.standable(w, g[0], gy, g[2])) gy--;
            }
            for (boolean build : new boolean[]{false, true}) {
                List<String> msgs = new ArrayList<>();
                Settings settings = new Settings();
                PathingSession s = new PathingSession(w, () -> -64, () -> 319, settings, Runnable::run, msgs::add,
                    () -> build ? tools : Interaction.NONE);
                long t1 = System.nanoTime();
                s.start(new GoalBlock(g[0], gy, g[2]), false, start);
                System.out.printf("BENCH %s -> %d %d %d build=%s: %s in %d ms, path %d %s%n", Pos.toString(start), g[0], gy, g[2], build,
                    s.snapshot().state(), (System.nanoTime() - t1) / 1_000_000, s.snapshot().path().size(), msgs);
            }
        }
    }
}
