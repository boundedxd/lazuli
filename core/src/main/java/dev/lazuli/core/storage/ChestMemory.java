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
import java.util.function.LongSupplier;
import java.util.function.Predicate;

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
    private final LongSupplier clock;
    /** Failed trips per container (3 strikes and it's forgotten), and until when each is left alone after one. */
    private final Map<Key, Integer> strikes = new HashMap<>();
    private final Map<Key, Long> coolUntil = new HashMap<>();
    /** Strikes before a container that can't be reached or opened is forgotten. */
    public static final int STRIKES = 3;

    /** {@code file} null: kept in memory only. */
    public ChestMemory(Path file) {
        this(file, System::currentTimeMillis);
    }

    /** {@code clock}: milliseconds, for the cooldown after a failed trip. */
    public ChestMemory(Path file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    /**
     * A trip to the container at x y z failed (couldn't get there or open it): it's left alone for {@code cooldownMs},
     * and forgotten at the third strike. True if forgotten.
     */
    public synchronized boolean fail(String dim, int x, int y, int z, long cooldownMs) {
        Key k = new Key(dim, x, y, z);
        int n = strikes.merge(k, 1, Integer::sum);
        if (n >= STRIKES) {
            forget(dim, x, y, z);
            return true;
        }
        coolUntil.put(k, clock.getAsLong() + cooldownMs);
        return false;
    }

    private boolean cooling(Key k) {
        Long until = coolUntil.get(k);
        return until != null && clock.getAsLong() < until;
    }

    /** Forgets every remembered container for which {@code gone} (given dim x y z) says it isn't a container now. */
    public synchronized int prune(Predicate<Spot> gone) {
        int before = chests.size();
        chests.keySet().removeIf(k -> gone.test(new Spot(k.dim(), k.x(), k.y(), k.z(), 0)));
        strikes.keySet().retainAll(chests.keySet());
        coolUntil.keySet().retainAll(chests.keySet());
        int n = before - chests.size();
        if (n > 0) save();
        return n;
    }

    /** The container at x y z now holds these (an empty map: nothing; it's still remembered as a container). */
    public synchronized void record(String dim, int x, int y, int z, Map<String, Integer> items) {
        Map<String, Integer> copy = new LinkedHashMap<>();
        items.forEach((k, v) -> {
            if (v > 0) copy.merge(k, v, Integer::sum);
        });
        Key key = new Key(dim, x, y, z);
        strikes.remove(key);
        coolUntil.remove(key);
        Map<String, Integer> old = chests.put(key, copy);
        if (!copy.equals(old)) save();
    }

    /** Forgets the container at x y z (broken, or not there any more). */
    public synchronized void forget(String dim, int x, int y, int z) {
        Key key = new Key(dim, x, y, z);
        strikes.remove(key);
        coolUntil.remove(key);
        if (chests.remove(key) != null) save();
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
            if (n != null && n > 0 && k.dim().equals(dim) && !cooling(k)) out.add(new Spot(k.dim(), k.x(), k.y(), k.z(), n));
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

    /** Like {@link #totals(String)}, for the containers within {@code range} blocks of px py pz only. */
    public synchronized Map<String, Integer> totals(String dim, int px, int py, int pz, int range) {
        Map<String, Integer> out = new HashMap<>();
        double r2 = (double) range * range;
        chests.forEach((k, items) -> {
            if (!k.dim().equals(dim) || cooling(k)) return;
            double dx = k.x() - px, dy = k.y() - py, dz = k.z() - pz;
            if (dx * dx + dy * dy + dz * dz <= r2) items.forEach((i, n) -> out.merge(i, n, Integer::sum));
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
