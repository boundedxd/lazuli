package dev.lazuli.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What was in each container the last time it was opened, per dimension, kept in a small text file
 * ({@code dim x y z item=count item=count...} per line). Item ids are full ids like {@code minecraft:diamond}.
 */
public final class ChestMemory {
    /** A container holding {@code count} of an item. */
    public record Spot(String dim, int x, int y, int z, int count) {
        public double distSq(int px, int py, int pz) {
            double dx = x - px, dy = y - py, dz = z - pz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private record Key(String dim, int x, int y, int z) {}

    private final Path file;
    private final Map<Key, Map<String, Integer>> chests = new LinkedHashMap<>();

    /** {@code file} null: kept in memory only. */
    public ChestMemory(Path file) {
        this.file = file;
        load();
    }

    /** The container at x y z now holds these (an empty map: nothing; it's still remembered as a container). */
    public synchronized void record(String dim, int x, int y, int z, Map<String, Integer> items) {
        Map<String, Integer> copy = new LinkedHashMap<>();
        items.forEach((k, v) -> {
            if (v > 0) copy.merge(k, v, Integer::sum);
        });
        Map<String, Integer> old = chests.put(new Key(dim, x, y, z), copy);
        if (!copy.equals(old)) save();
    }

    /** Forgets the container at x y z (broken, or not there any more). */
    public synchronized void forget(String dim, int x, int y, int z) {
        if (chests.remove(new Key(dim, x, y, z)) != null) save();
    }

    public synchronized void clear() {
        chests.clear();
        save();
    }

    /** Containers in {@code dim} with some of {@code item}, nearest to px py pz first. */
    public synchronized List<Spot> find(String item, String dim, int px, int py, int pz) {
        List<Spot> out = new ArrayList<>();
        chests.forEach((k, items) -> {
            Integer n = items.get(item);
            if (n != null && n > 0 && k.dim().equals(dim)) out.add(new Spot(k.dim(), k.x(), k.y(), k.z(), n));
        });
        out.sort(Comparator.comparingDouble(s -> s.distSq(px, py, pz)));
        return out;
    }

    /** The nearest container within {@code range} blocks holding some of {@code item}, or null. */
    public Spot nearest(String item, String dim, int px, int py, int pz, int range) {
        for (Spot s : find(item, dim, px, py, pz)) return s.distSq(px, py, pz) <= (double) range * range ? s : null;
        return null;
    }

    /** How many of each item the containers in {@code dim} hold together. */
    public synchronized Map<String, Integer> totals(String dim) {
        Map<String, Integer> out = new HashMap<>();
        chests.forEach((k, items) -> {
            if (k.dim().equals(dim)) items.forEach((i, n) -> out.merge(i, n, Integer::sum));
        });
        return out;
    }

    /** A container at x y z has been opened before. */
    public synchronized boolean known(String dim, int x, int y, int z) {
        return chests.containsKey(new Key(dim, x, y, z));
    }

    public synchronized int size() {
        return chests.size();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length < 4) continue;
                try {
                    Key k = new Key(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    Map<String, Integer> items = new LinkedHashMap<>();
                    for (int i = 4; i < p.length; i++) {
                        int eq = p[i].lastIndexOf('=');
                        if (eq > 0) items.put(p[i].substring(0, eq), Integer.parseInt(p[i].substring(eq + 1)));
                    }
                    chests.put(k, items);
                } catch (NumberFormatException ignored) {
                    // a line gone wrong: skip it
                }
            }
        } catch (IOException ignored) {
            // unreadable: start empty
        }
    }

    private void save() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        chests.forEach((k, items) -> {
            StringBuilder b = new StringBuilder(k.dim() + " " + k.x() + " " + k.y() + " " + k.z());
            items.forEach((i, n) -> b.append(' ').append(i).append('=').append(n));
            lines.add(b.toString());
        });
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // kept in memory for this session at least
        }
    }
}
