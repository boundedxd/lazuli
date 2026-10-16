package dev.lazuli.core.schematic;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Just enough of Minecraft's NBT to read schematic files: compounds become {@code Map<String, Object>}, lists
 * {@code List<Object>}, arrays {@code byte[] / int[] / long[]}, numbers their boxed types, strings strings.
 */
public final class Nbt {
    private Nbt() {}

    /** The root compound of a (gzipped or plain) NBT stream. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b0 = in.read(), b1 = in.read();
        in.reset();
        InputStream src = b0 == 0x1f && b1 == 0x8b ? new GZIPInputStream(in) : in;
        DataInputStream d = new DataInputStream(src);
        int type = d.readUnsignedByte();
        if (type != 10) throw new IOException("not an NBT compound");
        d.readUTF(); // the root's name
        return (Map<String, Object>) payload(d, 10, 0);
    }

    private static Object payload(DataInputStream d, int type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT nested too deep");
        return switch (type) {
            case 1 -> d.readByte();
            case 2 -> d.readShort();
            case 3 -> d.readInt();
            case 4 -> d.readLong();
            case 5 -> d.readFloat();
            case 6 -> d.readDouble();
            case 7 -> {
                byte[] a = new byte[length(d)];
                d.readFully(a);
                yield a;
            }
            case 8 -> d.readUTF();
            case 9 -> {
                int t = d.readUnsignedByte(), n = d.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, Math.min(n, 1 << 16)));
                for (int i = 0; i < n; i++) l.add(payload(d, t, depth + 1));
                yield l;
            }
            case 10 -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int t; (t = d.readUnsignedByte()) != 0; ) m.put(d.readUTF(), payload(d, t, depth + 1));
                yield m;
            }
            case 11 -> {
                int[] a = new int[length(d)];
                for (int i = 0; i < a.length; i++) a[i] = d.readInt();
                yield a;
            }
            case 12 -> {
                long[] a = new long[length(d)];
                for (int i = 0; i < a.length; i++) a[i] = d.readLong();
                yield a;
            }
            default -> throw new IOException("bad NBT tag " + type);
        };
    }

    private static int length(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > 64 << 20) throw new IOException("bad NBT array length " + n);
        return n;
    }

    // ---- reading helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> compound(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof List ? (List<Object>) o : List.of();
    }

    static int integer(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException("missing " + key);
    }
}
