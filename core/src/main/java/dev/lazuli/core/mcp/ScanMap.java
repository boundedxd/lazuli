package dev.lazuli.core.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a compact ASCII map of blocks: palettes are small per layer, so maps stay readable and cheap. */
public final class ScanMap {
    private ScanMap() {}

    /** Where block ids come from ({@code null} for a chunk that isn't loaded). */
    public interface Blocks {
        String at(int x, int y, int z);
    }

    /** @param rows run north to south (z), each character west to east (x) */
    public record Layer(int y, Map<Character, String> legend, List<String> rows, boolean unloaded) {}

    public static Layer layer(int y, int minX, int maxX, int minZ, int maxZ, Blocks blocks) {
        Map<Character, String> legend = new LinkedHashMap<>();
        Map<String, Character> byId = new LinkedHashMap<>();
        List<String> rows = new ArrayList<>();
        boolean unloaded = false;
        for (int z = minZ; z <= maxZ; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = minX; x <= maxX; x++) {
                String id = blocks.at(x, y, z);
                if (id == null) {
                    row.append('?');
                    unloaded = true;
                    continue;
                }
                Character letter = byId.get(id);
                if (letter == null) {
                    int n = byId.size();
                    letter = n < 26 ? (char) ('A' + n) : n < 52 ? (char) ('a' + n - 26) : '#';
                    byId.put(id, letter);
                    legend.put(letter, id);
                }
                row.append(letter);
            }
            rows.add(row.toString());
        }
        return new Layer(y, legend, rows, unloaded);
    }
}
