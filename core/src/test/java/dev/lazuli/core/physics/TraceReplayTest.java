package dev.lazuli.core.physics;

import dev.lazuli.core.testutil.Shapes;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TraceReplayTest {
    /** Per-tick position error allowed against the real game (float vs double rounding only). */
    private static final double TOLERANCE = 1e-3;

    @Test
    void roundTripsAndReplaysItsOwnRecording() throws IOException {
        Trace t = new Trace();
        for (int x = -2; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) t.putBlock(x, 0, z, BlockKind.SOLID, BlockPhysics.FULL);
        }
        t.putBlock(5, 1, 0, BlockKind.OBSTACLE, Shapes.BOTTOM_SLAB);
        t.putBlock(8, 0, 0, BlockKind.SOLID, Shapes.ICE);
        PlayerSim s = new PlayerSim(t, 0.5, 1, 0.5, 0, 0, 0, true, false);
        t.ticks.add(new Trace.Tick(0, 0, false, false, -90, s.x, s.y, s.z, s.vx, s.vy, s.vz, s.onGround));
        for (int i = 0; i < 40; i++) {
            int strafe = i % 7 == 3 ? 1 : 0;
            boolean jump = i == 20;
            s.step(1, strafe, jump, true, -90 + i);
            t.ticks.add(new Trace.Tick(1, strafe, jump, true, -90 + i, s.x, s.y, s.z, s.vx, s.vy, s.vz, s.onGround));
        }
        StringWriter w = new StringWriter();
        t.write(w);
        Trace back = Trace.read(new StringReader(w.toString()));
        assertEquals(t.ticks.size(), back.ticks.size());
        assertSame(Shapes.BOTTOM_SLAB, back.physics(5, 1, 0), "physics re-interned to the same palette entry");
        Trace.Divergence d = back.replay();
        assertTrue(d.maxStep() < 1e-9 && d.maxDrift() < 1e-9, d.toString());
    }

    @TestFactory
    Stream<DynamicTest> recordedTracesMatchTheGame() throws IOException {
        Path dir = Path.of("src/test/resources/traces");
        if (!Files.isDirectory(dir)) return Stream.empty();
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.toString().endsWith(".trace")).sorted().toList();
        }
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> {
            Trace t;
            try (var r = Files.newBufferedReader(f)) {
                t = Trace.read(r);
            }
            Trace.Divergence d = t.replay();
            assertTrue(d.maxStep() < TOLERANCE, f.getFileName() + ": " + d);
        }));
    }
}
