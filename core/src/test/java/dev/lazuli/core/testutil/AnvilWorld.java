package dev.lazuli.core.testutil;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.WorldCache;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Loads a saved Minecraft world (Anvil region files) into a {@link WorldCache} for benchmarks on real terrain.
 * Blocks are classified by name, roughly as the mod does from their shapes; good enough to time the planner.
 */
public final class AnvilWorld {
    private AnvilWorld() {}

    public static WorldCache load(Path worldDir) throws IOException {
        return load(worldDir, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** Only the chunks with cx in [cx0, cx1] and cz in [cz0, cz1]. */
    public static WorldCache load(Path worldDir, int cx0, int cx1, int cz0, int cz1) throws IOException {
        WorldCache cache = new WorldCache(-64, 384);
        Map<String, short[]> classified = new HashMap<>(); // name+props -> {kind, physics id}
        try (var files = Files.list(worldDir.resolve("region"))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String[] p = f.getFileName().toString().split("\\.");
                if (p.length != 4 || !p[3].equals("mca")) continue;
                int rx = Integer.parseInt(p[1]), rz = Integer.parseInt(p[2]);
                if (rx * 32 + 31 < cx0 || rx * 32 > cx1 || rz * 32 + 31 < cz0 || rz * 32 > cz1) continue;
                loadRegion(f, rx, rz, cache, classified, cx0, cx1, cz0, cz1);
            }
        }
        return cache;
    }

    /** The single player's saved position, from level.dat. */
    public static double[] playerPos(Path worldDir) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(worldDir.resolve("level.dat")))) {
            Map<String, Object> root = readRoot(new DataInputStream(in));
            Map<?, ?> data = (Map<?, ?>) root.get("Data");
            Map<?, ?> player = (Map<?, ?>) data.get("Player");
            List<?> pos = (List<?>) player.get("Pos");
            return new double[]{(Double) pos.get(0), (Double) pos.get(1), (Double) pos.get(2)};
        }
    }

    private static void loadRegion(Path f, int rx, int rz, WorldCache cache, Map<String, short[]> classified,
                                   int cx0, int cx1, int cz0, int cz1) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f.toFile(), "r")) {
            if (raf.length() < 8192) return;
            int[] offsets = new int[1024];
            for (int i = 0; i < 1024; i++) offsets[i] = raf.readInt();
            for (int i = 0; i < 1024; i++) {
                int sector = offsets[i] >>> 8;
                if (sector == 0) continue;
                int ccx = rx * 32 + (i & 31), ccz = rz * 32 + (i >> 5);
                if (ccx < cx0 || ccx > cx1 || ccz < cz0 || ccz > cz1) continue;
                raf.seek(sector * 4096L);
                int len = raf.readInt();
                int type = raf.readByte();
                byte[] buf = new byte[len - 1];
                raf.readFully(buf);
                InputStream in = type == 2 ? new InflaterInputStream(new ByteArrayInputStream(buf))
                    : type == 1 ? new GZIPInputStream(new ByteArrayInputStream(buf)) : null;
                if (in == null) continue;
                Map<String, Object> chunk = readRoot(new DataInputStream(in));
                if (!"minecraft:full".equals(chunk.get("Status"))) continue;
                int cx = rx * 32 + (i & 31), cz = rz * 32 + (i >> 5);
                putChunk(cache, cx, cz, chunk, classified);
            }
        }
    }

    private static void putChunk(WorldCache cache, int cx, int cz, Map<String, Object> chunk, Map<String, short[]> classified) {
        byte[][] kinds = new byte[cache.sectionCount()][];
        short[][] physics = new short[cache.sectionCount()][];
        for (Object o : (List<?>) chunk.get("sections")) {
            Map<?, ?> sec = (Map<?, ?>) o;
            int s = ((Number) sec.get("Y")).intValue() + 4;
            if (s < 0 || s >= kinds.length) continue;
            Map<?, ?> states = (Map<?, ?>) sec.get("block_states");
            if (states == null) continue;
            List<?> palette = (List<?>) states.get("palette");
            short[][] pal = new short[palette.size()][];
            boolean allAir = true;
            for (int i = 0; i < pal.length; i++) {
                Map<?, ?> e = (Map<?, ?>) palette.get(i);
                String name = (String) e.get("Name");
                Map<?, ?> props = (Map<?, ?>) e.get("Properties");
                String key = name + (props == null ? "" : props.toString());
                pal[i] = classified.computeIfAbsent(key, k -> classify(name, props));
                if (pal[i][0] != BlockKind.AIR.ordinal() || pal[i][1] != 0) allAir = false;
            }
            if (allAir) continue;
            byte[] k = new byte[4096];
            short[] p = new short[4096];
            long[] data = (long[]) states.get("data");
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(pal.length - 1));
            int perLong = 64 / bits;
            for (int i = 0; i < 4096; i++) {
                int idx = data == null ? 0 : (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & ((1L << bits) - 1));
                short[] c = pal[Math.min(idx, pal.length - 1)];
                k[i] = (byte) c[0];
                p[i] = c[1];
            }
            kinds[s] = k;
            physics[s] = p;
        }
        cache.putChunk(cx, cz, kinds, physics);
    }

    private static final double[] FULL = {0, 0, 0, 1, 1, 1};

    private static short[] classify(String name, Map<?, ?> props) {
        String n = name.substring(name.indexOf(':') + 1);
        boolean waterlogged = props != null && "true".equals(props.get("waterlogged"));
        if (n.equals("air") || n.equals("cave_air") || n.equals("void_air")) return new short[]{(short) BlockKind.AIR.ordinal(), 0};
        if (n.equals("water") || n.equals("bubble_column") || n.contains("kelp") || n.contains("seagrass") || waterlogged) {
            return new short[]{(short) BlockKind.WATER.ordinal(), 0};
        }
        if (n.equals("lava") || n.contains("fire") || n.equals("magma_block") || n.equals("cactus") || n.equals("cobweb")
            || n.equals("sweet_berry_bush") || n.equals("powder_snow") || n.contains("campfire")) {
            BlockPhysics ph = BlockPhysics.intern(new double[0], 0.6f, 1, 1, n.equals("lava") ? BlockPhysics.LAVA : 0, -1, n.hashCode());
            return new short[]{(short) BlockKind.DANGER.ordinal(), ph.id};
        }
        boolean noCollision = n.endsWith("grass") && !n.equals("grass_block") || n.contains("flower") || n.endsWith("tulip")
            || n.equals("dandelion") || n.equals("poppy") || n.equals("fern") || n.equals("large_fern") || n.equals("dead_bush")
            || n.contains("torch") || n.contains("sapling") || n.equals("vine") || n.equals("sugar_cane") || n.contains("mushroom") && !n.contains("block")
            || n.equals("snow") || n.contains("button") || n.contains("pressure_plate") || n.contains("rail") || n.equals("lily_pad")
            || n.contains("carpet") || n.equals("azure_bluet") || n.equals("oxeye_daisy") || n.equals("cornflower") || n.equals("allium")
            || n.contains("orchid") || n.equals("lily_of_the_valley") || n.equals("bush") || n.contains("leaf_litter") || n.contains("petals")
            || n.equals("firefly_bush") || n.contains("wildflowers") || n.equals("short_dry_grass") || n.equals("tall_dry_grass");
        if (noCollision) {
            boolean replaceable = n.endsWith("grass") || n.equals("fern") || n.equals("large_fern") || n.equals("dead_bush") || n.equals("vine")
                || n.equals("snow") || n.equals("bush") || n.contains("leaf_litter");
            BlockPhysics ph = BlockPhysics.intern(new double[0], 0.6f, 1, 1, replaceable ? BlockPhysics.REPLACEABLE : 0, 0, n.hashCode());
            return new short[]{(short) BlockKind.AIR.ordinal(), ph.id};
        }
        float hardness = n.contains("leaves") ? 0.2f : n.contains("dirt") || n.equals("grass_block") || n.equals("sand")
            || n.equals("gravel") || n.equals("podzol") || n.equals("mycelium") || n.equals("clay") ? 0.6f
            : n.contains("log") || n.contains("wood") || n.contains("planks") ? 2.0f
            : n.equals("bedrock") ? -1f : n.contains("ore") ? 3.0f : 1.5f;
        int flags = n.equals("sand") || n.equals("red_sand") || n.equals("gravel") || n.contains("concrete_powder") ? BlockPhysics.FALLING : 0;
        double[] boxes = FULL;
        BlockKind kind = BlockKind.SOLID;
        if (n.endsWith("_slab") && props != null && !"double".equals(props.get("type"))) {
            boxes = "top".equals(props.get("type")) ? new double[]{0, 0.5, 0, 1, 1, 1} : new double[]{0, 0, 0, 1, 0.5, 1};
            kind = BlockKind.OBSTACLE;
        } else if (n.endsWith("_fence") || n.endsWith("_wall") || n.endsWith("fence_gate")) {
            boxes = new double[]{0.375, 0, 0.375, 0.625, 1.5, 0.625};
            kind = BlockKind.OBSTACLE;
        } else if (n.equals("dirt_path") || n.equals("farmland")) {
            boxes = new double[]{0, 0, 0, 1, 0.9375, 1};
            kind = BlockKind.OBSTACLE;
        }
        float friction = n.contains("ice") ? 0.98f : 0.6f;
        BlockPhysics ph = BlockPhysics.intern(boxes, friction, 1, 1, flags, hardness, n.hashCode());
        return new short[]{(short) kind.ordinal(), ph.id};
    }

    // --- minimal NBT ---

    private static Map<String, Object> readRoot(DataInputStream in) throws IOException {
        int type = in.readByte();
        if (type != 10) throw new IOException("not a compound");
        in.readUTF();
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) read(in, 10);
        return m;
    }

    private static Object read(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { byte[] b = new byte[in.readInt()]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readByte(), n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(read(in, t));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = in.readByte();
                    if (t == 0) return m;
                    String name = in.readUTF();
                    m.put(name, read(in, t));
                }
            }
            case 11: { int[] a = new int[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readInt(); return a; }
            case 12: { long[] a = new long[in.readInt()]; for (int i = 0; i < a.length; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("bad tag " + type);
        }
    }
}
