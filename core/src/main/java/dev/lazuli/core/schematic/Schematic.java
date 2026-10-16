package dev.lazuli.core.schematic;

import dev.lazuli.core.world.Pos;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A structure to build: block states (like {@code minecraft:oak_stairs[facing=east,half=bottom]}) at positions
 * relative to its lowest corner, air left out. Reads Litematica {@code .litematic} (every region), Sponge/WorldEdit
 * {@code .schem} (versions 2 and 3) and vanilla structure {@code .nbt} files.
 */
public final class Schematic {
    /** One block: where (relative), and its state. */
    public record Block(int x, int y, int z, String state) {
        /** The block id without its properties. */
        public String id() {
            int i = state.indexOf('[');
            return i < 0 ? state : state.substring(0, i);
        }
    }

    private final int sizeX, sizeY, sizeZ;
    private final List<Block> blocks;

    public Schematic(int sizeX, int sizeY, int sizeZ, List<Block> blocks) {
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.blocks = List.copyOf(blocks);
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public List<Block> blocks() {
        return blocks;
    }

    /** How many of each block id it takes, most first. */
    public Map<String, Integer> materials() {
        Map<String, Integer> m = new TreeMap<>();
        for (Block b : blocks) m.merge(b.id(), 1, Integer::sum);
        Map<String, Integer> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public static Schematic load(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, file.getFileName().toString());
        }
    }

    /** Reads a schematic; the file name's extension picks the format (any other: guessed from the contents). */
    public static Schematic read(InputStream in, String name) throws IOException {
        Map<String, Object> root = Nbt.read(in);
        try {
            if (root.containsKey("Regions")) return litematic(root);
            if (root.containsKey("Schematic")) return sponge(Nbt.compound(root, "Schematic"));
            if (root.containsKey("BlockData") || root.containsKey("Blocks") && root.containsKey("Width")) return sponge(root);
            if (root.containsKey("palette") || root.containsKey("palettes")) return structure(root);
        } catch (RuntimeException e) {
            throw new IOException("broken schematic " + name + ": " + e.getMessage(), e);
        }
        throw new IOException("not a schematic I know: " + name);
    }

    // ---- Litematica

    private static Schematic litematic(Map<String, Object> root) {
        List<Block> all = new ArrayList<>();
        Map<String, Object> regions = Nbt.compound(root, "Regions");
        for (Object o : regions.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
            Map<String, Object> pos = Nbt.compound(r, "Position"), size = Nbt.compound(r, "Size");
            int sx = Nbt.integer(size, "x"), sy = Nbt.integer(size, "y"), sz = Nbt.integer(size, "z");
            // a negative size grows the other way from the position
            int ox = Nbt.integer(pos, "x") + (sx < 0 ? sx + 1 : 0), oy = Nbt.integer(pos, "y") + (sy < 0 ? sy + 1 : 0);
            int oz = Nbt.integer(pos, "z") + (sz < 0 ? sz + 1 : 0);
            sx = Math.abs(sx);
            sy = Math.abs(sy);
            sz = Math.abs(sz);
            List<String> palette = new ArrayList<>();
            for (Object p : Nbt.list(r, "BlockStatePalette")) palette.add(state(asMap(p)));
            long[] data = (long[]) r.get("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            long mask = (1L << bits) - 1;
            for (int y = 0; y < sy; y++)
                for (int z = 0; z < sz; z++)
                    for (int x = 0; x < sx; x++) {
                        long index = ((long) y * sz + z) * sx + x;
                        long bit = index * bits;
                        int start = (int) (bit >> 6), end = (int) ((bit + bits - 1) >> 6), off = (int) (bit & 63);
                        long v = start == end ? data[start] >>> off & mask
                            : (data[start] >>> off | data[end] << (64 - off)) & mask;
                        String st = palette.get((int) v);
                        if (!air(st)) all.add(new Block(ox + x, oy + y, oz + z, st));
                    }
        }
        return normalised(all);
    }

    // ---- Sponge (.schem)

    private static Schematic sponge(Map<String, Object> root) {
        int w = Nbt.integer(root, "Width"), h = Nbt.integer(root, "Height"), l = Nbt.integer(root, "Length");
        Map<String, Object> palette;
        byte[] data;
        Map<String, Object> blocks = Nbt.compound(root, "Blocks");
        if (blocks != null) { // version 3
            palette = Nbt.compound(blocks, "Palette");
            data = (byte[]) blocks.get("Data");
        } else {
            palette = Nbt.compound(root, "Palette");
            data = (byte[]) root.get("BlockData");
        }
        String[] byId = new String[palette.size() + 1];
        for (var e : palette.entrySet()) {
            int id = ((Number) e.getValue()).intValue();
            if (id >= byId.length) byId = java.util.Arrays.copyOf(byId, id + 1);
            byId[id] = e.getKey();
        }
        List<Block> out = new ArrayList<>();
        int i = 0, index = 0;
        while (i < data.length) {
            // varints
            int v = 0, shift = 0, b;
            do {
                b = data[i++];
                v |= (b & 0x7f) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            int x = index % w, z = index / w % l, y = index / (w * l);
            String st = v < byId.length ? byId[v] : null;
            if (st != null && !air(st)) out.add(new Block(x, y, z, st));
            index++;
        }
        return new Schematic(w, h, l, out);
    }

    // ---- vanilla structure (.nbt)

    private static Schematic structure(Map<String, Object> root) {
        List<Object> pal = root.containsKey("palette") ? Nbt.list(root, "palette") : asList(Nbt.list(root, "palettes").get(0));
        List<String> palette = new ArrayList<>();
        for (Object p : pal) palette.add(state(asMap(p)));
        List<Block> out = new ArrayList<>();
        for (Object o : Nbt.list(root, "blocks")) {
            Map<String, Object> b = asMap(o);
            List<Object> pos = Nbt.list(b, "pos");
            String st = palette.get(Nbt.integer(b, "state"));
            if (!air(st)) out.add(new Block(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue(), st));
        }
        List<Object> size = Nbt.list(root, "size");
        return new Schematic(((Number) size.get(0)).intValue(), ((Number) size.get(1)).intValue(), ((Number) size.get(2)).intValue(), out);
    }

    // ---- shared

    /** "name[k=v,...]" from a palette entry {Name, Properties}. */
    static String state(Map<String, Object> entry) {
        String name = (String) entry.get("Name");
        Map<String, Object> props = Nbt.compound(entry, "Properties");
        if (props == null || props.isEmpty()) return name;
        StringBuilder b = new StringBuilder(name).append('[');
        new TreeMap<>(props).forEach((k, v) -> b.append(k).append('=').append(v).append(','));
        b.setCharAt(b.length() - 1, ']');
        return b.toString();
    }

    private static boolean air(String state) {
        return state.startsWith("minecraft:air") || state.startsWith("minecraft:cave_air") || state.startsWith("minecraft:void_air")
            || state.startsWith("minecraft:structure_void");
    }

    /** Shifted so the lowest corner is (0, 0, 0). */
    private static Schematic normalised(List<Block> all) {
        if (all.isEmpty()) return new Schematic(0, 0, 0, List.of());
        int mx = Integer.MAX_VALUE, my = Integer.MAX_VALUE, mz = Integer.MAX_VALUE, Mx = Integer.MIN_VALUE, My = Integer.MIN_VALUE, Mz = Integer.MIN_VALUE;
        for (Block b : all) {
            mx = Math.min(mx, b.x());
            my = Math.min(my, b.y());
            mz = Math.min(mz, b.z());
            Mx = Math.max(Mx, b.x());
            My = Math.max(My, b.y());
            Mz = Math.max(Mz, b.z());
        }
        List<Block> out = new ArrayList<>(all.size());
        for (Block b : all) out.add(new Block(b.x() - mx, b.y() - my, b.z() - mz, b.state()));
        out.sort(Comparator.comparingInt(Block::y).thenComparingInt(Block::z).thenComparingInt(Block::x));
        return new Schematic(Mx - mx + 1, My - my + 1, Mz - mz + 1, out);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }

    /** Packed position of a block placed with its corner at (ox, oy, oz). */
    public static long at(Block b, int ox, int oy, int oz) {
        return Pos.pack(ox + b.x(), oy + b.y(), oz + b.z());
    }
}
