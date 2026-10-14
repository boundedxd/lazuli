package dev.lazuli.core.physics;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A golden trace: the real game's player state tick by tick, the inputs that produced it, and the blocks around it.
 * Recorded in game with {@code #record}, replayed through {@link PlayerSim} by the tests to prove the sim matches
 * vanilla. Plain text, one record per line, so traces diff and survive format changes.
 */
public final class Trace implements BlockView {
    /** One tick: the inputs held during it and the player's state after it. Tick 0 is the start state. */
    public record Tick(int forward, int strafe, boolean jump, boolean sprint, float yaw,
                       double x, double y, double z, double vx, double vy, double vz, boolean onGround) {}

    private final Map<Long, BlockKind> kinds = new HashMap<>();
    private final Map<Long, BlockPhysics> physics = new HashMap<>();
    public final List<Tick> ticks = new ArrayList<>();

    public void putBlock(int x, int y, int z, BlockKind kind, BlockPhysics p) {
        kinds.put(Pos.pack(x, y, z), kind);
        physics.put(Pos.pack(x, y, z), p);
    }

    @Override
    public BlockKind get(int x, int y, int z) {
        return kinds.getOrDefault(Pos.pack(x, y, z), BlockKind.AIR);
    }

    @Override
    public BlockPhysics physics(int x, int y, int z) {
        BlockPhysics p = physics.get(Pos.pack(x, y, z));
        return p != null ? p : BlockView.super.physics(x, y, z);
    }

    public void write(Writer out) throws IOException {
        out.write("lazuli-trace 1\n");
        for (Map.Entry<Long, BlockKind> e : kinds.entrySet()) {
            long k = e.getKey();
            BlockPhysics p = physics(Pos.x(k), Pos.y(k), Pos.z(k));
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "block %d %d %d %s %s %s %s %d %d",
                Pos.x(k), Pos.y(k), Pos.z(k), e.getValue().name(), p.friction, p.speedFactor, p.jumpFactor, p.flags,
                p.boxes.length / 6));
            for (double b : p.boxes) sb.append(' ').append(b);
            out.write(sb.append('\n').toString());
        }
        for (Tick t : ticks) {
            out.write(String.format(Locale.ROOT, "tick %d %d %b %b %s %s %s %s %s %s %s %b%n", t.forward, t.strafe, t.jump,
                t.sprint, t.yaw, t.x, t.y, t.z, t.vx, t.vy, t.vz, t.onGround));
        }
    }

    public static Trace read(Reader in) throws IOException {
        BufferedReader r = new BufferedReader(in);
        String header = r.readLine();
        if (header == null || !header.startsWith("lazuli-trace")) throw new IOException("not a Lazuli trace");
        Trace t = new Trace();
        for (String line; (line = r.readLine()) != null; ) {
            String[] f = line.trim().split("\\s+");
            if (f[0].equals("block")) {
                int n = Integer.parseInt(f[9]);
                double[] boxes = new double[n * 6];
                for (int i = 0; i < boxes.length; i++) boxes[i] = Double.parseDouble(f[10 + i]);
                t.putBlock(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), BlockKind.valueOf(f[4]),
                    BlockPhysics.intern(boxes, Float.parseFloat(f[5]), Float.parseFloat(f[6]), Float.parseFloat(f[7]),
                        Integer.parseInt(f[8])));
            } else if (f[0].equals("tick")) {
                t.ticks.add(new Tick(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Boolean.parseBoolean(f[3]),
                    Boolean.parseBoolean(f[4]), Float.parseFloat(f[5]), Double.parseDouble(f[6]), Double.parseDouble(f[7]),
                    Double.parseDouble(f[8]), Double.parseDouble(f[9]), Double.parseDouble(f[10]),
                    Double.parseDouble(f[11]), Boolean.parseBoolean(f[12])));
            }
        }
        return t;
    }

    /** How far the sim strays from the recording. */
    public record Divergence(double maxStep, int worstStep, double maxDrift) {}

    /**
     * Replays the inputs. {@code maxStep}: worst position error of one tick simulated from the recorded state before
     * it (the sim's per-tick fidelity). {@code maxDrift}: worst error of the whole run simulated from the start.
     */
    public Divergence replay() {
        double maxStep = 0, maxDrift = 0;
        int worst = -1;
        Tick s0 = ticks.get(0);
        PlayerSim free = new PlayerSim(this, s0.x, s0.y, s0.z, s0.vx, s0.vy, s0.vz, s0.onGround, s0.sprint);
        for (int i = 1; i < ticks.size(); i++) {
            Tick prev = ticks.get(i - 1), t = ticks.get(i);
            PlayerSim one = new PlayerSim(this, prev.x, prev.y, prev.z, prev.vx, prev.vy, prev.vz, prev.onGround, prev.sprint);
            one.step(t.forward, t.strafe, t.jump, t.sprint, t.yaw);
            double e = Math.sqrt(sq(one.x - t.x) + sq(one.y - t.y) + sq(one.z - t.z));
            if (e > maxStep) {
                maxStep = e;
                worst = i;
            }
            free.step(t.forward, t.strafe, t.jump, t.sprint, t.yaw);
            maxDrift = Math.max(maxDrift, Math.sqrt(sq(free.x - t.x) + sq(free.y - t.y) + sq(free.z - t.z)));
        }
        return new Divergence(maxStep, worst, maxDrift);
    }

    private static double sq(double d) {
        return d * d;
    }
}
