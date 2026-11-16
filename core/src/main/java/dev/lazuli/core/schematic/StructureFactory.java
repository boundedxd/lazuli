package dev.lazuli.core.schematic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic, block-state-aware structure generators used by integrations. */
public final class StructureFactory {
    private StructureFactory() {}

    /** A compact two-sided trading hall with a central aisle, individual stalls, windows, lighting, and a roof. */
    public static Schematic tradingHall(int villagers, Map<String, String> requestedPalette) {
        int count = Math.max(1, Math.min(96, villagers));
        Map<String, String> p = new LinkedHashMap<>();
        p.put("floor", "minecraft:stone_bricks");
        p.put("wall", "minecraft:stone_bricks");
        p.put("roof", "minecraft:oak_planks");
        p.put("trim", "minecraft:oak_log[axis=y]");
        p.put("glass", "minecraft:glass_pane");
        p.put("partition", "minecraft:oak_fence");
        p.put("door", "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]");
        p.put("light", "minecraft:lantern[hanging=false,waterlogged=false]");
        p.put("workstation", "minecraft:lectern[facing=south,has_book=false,powered=false]");
        if (requestedPalette != null) {
            for (var e : requestedPalette.entrySet()) if (e.getKey() != null && e.getValue() != null && p.containsKey(e.getKey()))
                p.put(e.getKey(), e.getValue());
        }

        int stallsPerSide = (count + 1) / 2;
        int length = Math.max(9, 5 + stallsPerSide * 4);
        int width = 9;
        int height = 5;
        List<Schematic.Block> blocks = new ArrayList<>();
        for (int x = 0; x < width; x++) for (int z = 0; z < length; z++) add(blocks, x, 0, z, p.get("floor"));
        for (int y = 1; y < height; y++) {
            for (int z = 0; z < length; z++) {
                add(blocks, 0, y, z, p.get("wall"));
                add(blocks, width - 1, y, z, p.get("wall"));
            }
            for (int x = 0; x < width; x++) {
                add(blocks, x, y, 0, p.get("wall"));
                add(blocks, x, y, length - 1, p.get("wall"));
            }
        }
        for (int x = 0; x < width; x++) for (int z = 0; z < length; z++) add(blocks, x, height, z, p.get("roof"));
        for (int z = 1; z < length - 1; z++) {
            add(blocks, 0, 2, z, p.get("glass"));
            add(blocks, width - 1, 2, z, p.get("glass"));
        }
        add(blocks, 4, 1, 0, p.get("door"));
        add(blocks, 4, 2, 0, p.get("door").replace("half=lower", "half=upper"));
        for (int z = 2; z < length - 1; z += 4) {
            add(blocks, 2, 1, z, p.get("partition"));
            add(blocks, 2, 2, z, p.get("partition"));
            add(blocks, 6, 1, z, p.get("partition"));
            add(blocks, 6, 2, z, p.get("partition"));
            add(blocks, 1, 1, z + 1, p.get("workstation"));
            add(blocks, 7, 1, z + 1, p.get("workstation").replace("facing=south", "facing=north"));
            add(blocks, 1, 3, z + 1, p.get("light"));
            add(blocks, 7, 3, z + 1, p.get("light"));
        }
        // A central line of lights keeps the aisle readable and makes the generated hall usable at night.
        for (int z = 3; z < length - 1; z += 4) add(blocks, 4, 3, z, p.get("light"));
        for (int z = 0; z < length; z++) {
            add(blocks, 0, 3, z, p.get("trim"));
            add(blocks, width - 1, 3, z, p.get("trim"));
        }
        return new Schematic(width, height + 1, length, blocks);
    }

    private static void add(List<Schematic.Block> blocks, int x, int y, int z, String state) {
        if (state == null || state.isBlank()) return;
        blocks.add(new Schematic.Block(x, y, z, state));
    }
}
