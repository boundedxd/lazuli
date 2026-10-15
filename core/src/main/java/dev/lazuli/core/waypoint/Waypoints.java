package dev.lazuli.core.waypoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Named places per dimension ({@code #wp add home}, {@code #goto home}), kept in a small text file, one
 * {@code dimension name x y z} per line. Names are case-insensitive; {@code death} is set when the player dies.
 */
public final class Waypoints {
    public record Waypoint(String name, String dim, int x, int y, int z) {}

    public static final String DEATH = "death";

    private final Path file;
    /** Keyed by "dim name". */
    private final Map<String, Waypoint> all = new LinkedHashMap<>();

    /** {@code file} null: kept in memory only. */
    public Waypoints(Path file) {
        this.file = file;
        load();
    }

    public synchronized void set(String name, String dim, int x, int y, int z) {
        name = name.toLowerCase(Locale.ROOT);
        all.put(key(dim, name), new Waypoint(name, dim, x, y, z));
        save();
    }

    public synchronized boolean remove(String name, String dim) {
        boolean had = all.remove(key(dim, name.toLowerCase(Locale.ROOT))) != null;
        if (had) save();
        return had;
    }

    public synchronized Waypoint get(String name, String dim) {
        return all.get(key(dim, name.toLowerCase(Locale.ROOT)));
    }

    /** This dimension's waypoints, by name. */
    public synchronized List<Waypoint> list(String dim) {
        List<Waypoint> out = new ArrayList<>();
        for (Waypoint w : all.values()) if (w.dim().equals(dim)) out.add(w);
        out.sort(Comparator.comparing(Waypoint::name));
        return out;
    }

    private static String key(String dim, String name) {
        return dim + " " + name;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length != 5) continue;
                try {
                    Waypoint w = new Waypoint(p[1], p[0], Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]));
                    all.put(key(w.dim(), w.name()), w);
                } catch (NumberFormatException ignored) {
                    // a hand-edited line gone wrong: skip it
                }
            }
        } catch (IOException ignored) {
            // unreadable: start empty
        }
    }

    private void save() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (Waypoint w : all.values()) lines.add(w.dim() + " " + w.name() + " " + w.x() + " " + w.y() + " " + w.z());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // kept in memory for this session at least
        }
    }
}
