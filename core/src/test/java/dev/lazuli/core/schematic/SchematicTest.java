package dev.lazuli.core.schematic;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SchematicTest {
    // ---- a tiny NBT writer for the fixtures

    static byte[] gz(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(bytes))) {
            d.writeByte(10);
            d.writeUTF("");
            payload(d, root);
        }
        return bytes.toByteArray();
    }

    static int type(Object o) {
        if (o instanceof Byte) return 1;
        if (o instanceof Short) return 2;
        if (o instanceof Integer) return 3;
        if (o instanceof Long) return 4;
        if (o instanceof byte[]) return 7;
        if (o instanceof String) return 8;
        if (o instanceof List) return 9;
        if (o instanceof Map) return 10;
        if (o instanceof int[]) return 11;
        if (o instanceof long[]) return 12;
        throw new IllegalArgumentException(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    static void payload(DataOutputStream d, Object o) throws IOException {
        switch (type(o)) {
            case 1 -> d.writeByte((Byte) o);
            case 2 -> d.writeShort((Short) o);
            case 3 -> d.writeInt((Integer) o);
            case 4 -> d.writeLong((Long) o);
            case 7 -> {
                d.writeInt(((byte[]) o).length);
                d.write((byte[]) o);
            }
            case 8 -> d.writeUTF((String) o);
            case 9 -> {
                List<Object> l = (List<Object>) o;
                d.writeByte(l.isEmpty() ? 0 : type(l.get(0)));
                d.writeInt(l.size());
                for (Object e : l) payload(d, e);
            }
            case 10 -> {
                for (var e : ((Map<String, Object>) o).entrySet()) {
                    d.writeByte(type(e.getValue()));
                    d.writeUTF(e.getKey());
                    payload(d, e.getValue());
                }
                d.writeByte(0);
            }
            case 11 -> {
                d.writeInt(((int[]) o).length);
                for (int v : (int[]) o) d.writeInt(v);
            }
            case 12 -> {
                d.writeInt(((long[]) o).length);
                for (long v : (long[]) o) d.writeLong(v);
            }
            default -> throw new IllegalStateException();
        }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> entry(String name, Map<String, Object> props) {
        return props == null ? map("Name", name) : map("Name", name, "Properties", props);
    }

    /** What block a 3x2x4 test structure has at (x, y, z): a palette index 0-4 (0 air). */
    static int pattern(int x, int y, int z) {
        return (x + 2 * y + 3 * z) % 5;
    }

    static final String[] STATES = {"minecraft:air", "minecraft:stone", "minecraft:oak_stairs[facing=east,half=bottom]",
        "minecraft:glass", "minecraft:oak_log[axis=y]"};

    static List<Object> palette() {
        return List.of(entry("minecraft:air", null), entry("minecraft:stone", null),
            entry("minecraft:oak_stairs", map("half", "bottom", "facing", "east")), entry("minecraft:glass", null),
            entry("minecraft:oak_log", map("axis", "y")));
    }

    static void check(Schematic s) {
        assertEquals(3, s.sizeX());
        assertEquals(2, s.sizeY());
        assertEquals(4, s.sizeZ());
        int n = 0;
        for (int y = 0; y < 2; y++)
            for (int z = 0; z < 4; z++)
                for (int x = 0; x < 3; x++) if (pattern(x, y, z) != 0) n++;
        assertEquals(n, s.blocks().size());
        for (Schematic.Block b : s.blocks()) assertEquals(STATES[pattern(b.x(), b.y(), b.z())], b.state(), b.toString());
        assertEquals("minecraft:oak_stairs", new Schematic.Block(0, 0, 0, STATES[2]).id());
    }

    @Test
    void readsLitematicaAcrossLongBoundaries() throws IOException {
        // 5 states: 3 bits each, so some values straddle two longs
        int sx = 3, sy = 2, sz = 4, bits = 3;
        long[] data = new long[(sx * sy * sz * bits + 63) / 64];
        for (int y = 0; y < sy; y++)
            for (int z = 0; z < sz; z++)
                for (int x = 0; x < sx; x++) {
                    long index = ((long) y * sz + z) * sx + x, bit = index * bits;
                    int start = (int) (bit >> 6), off = (int) (bit & 63);
                    long v = pattern(x, y, z);
                    data[start] |= v << off;
                    if (off + bits > 64) data[start + 1] |= v >>> (64 - off);
                }
        // a negative size: the region grows the other way from its position
        Map<String, Object> region = map("Position", map("x", 12, "y", 70, "z", 3 + sz - 1),
            "Size", map("x", sx, "y", sy, "z", -sz), "BlockStatePalette", palette(), "BlockStates", data);
        Map<String, Object> root = map("Version", 6, "Regions", map("main", region));
        Schematic s = Schematic.read(new ByteArrayInputStream(gz(root)), "test.litematic");
        check(s);
    }

    @Test
    void readsSpongeSchem() throws IOException {
        int w = 3, h = 2, l = 4;
        Map<String, Object> pal = new LinkedHashMap<>();
        for (int i = 0; i < STATES.length; i++) pal.put(STATES[i], i);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++)
            for (int z = 0; z < l; z++)
                for (int x = 0; x < w; x++) data.write(pattern(x, y, z));
        Map<String, Object> v2 = map("Version", 2, "Width", (short) w, "Height", (short) h, "Length", (short) l,
            "Palette", pal, "BlockData", data.toByteArray());
        check(Schematic.read(new ByteArrayInputStream(gz(v2)), "a.schem"));
        Map<String, Object> v3 = map("Schematic", map("Version", 3, "Width", (short) w, "Height", (short) h, "Length", (short) l,
            "Blocks", map("Palette", pal, "Data", data.toByteArray())));
        check(Schematic.read(new ByteArrayInputStream(gz(v3)), "b.schem"));
    }

    @Test
    void readsVanillaStructures() throws IOException {
        List<Object> blocks = new ArrayList<>();
        for (int y = 0; y < 2; y++)
            for (int z = 0; z < 4; z++)
                for (int x = 0; x < 3; x++) blocks.add(map("pos", List.of(x, y, z), "state", pattern(x, y, z)));
        Map<String, Object> root = map("size", List.of(3, 2, 4), "palette", palette(), "blocks", blocks);
        Schematic s = Schematic.read(new ByteArrayInputStream(gz(root)), "house.nbt");
        check(s);
        assertEquals(5, s.materials().get("minecraft:stone"));
    }
}
