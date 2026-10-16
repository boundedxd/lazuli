package dev.lazuli.core.portal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Nether portals seen (or built, or come out of) per dimension, one {@code dimension x y z} line each in a small text
 * file. A portal is kept as one of its portal blocks; blocks of the same portal (within a few blocks) count once.
 */
public final class PortalMemory {
    public record Portal(String dim, int x, int y, int z) {
        public double distXZ(double px, double pz) {
            return Math.hypot(x + 0.5 - px, z + 0.5 - pz);
        }
    }

    /** Portal blocks this close (each axis) are taken for the same portal. */
    private static final int SAME = 4;

    private final Path file;
    private final List<Portal> all = new ArrayList<>();

    /** {@code file} null: kept in memory only. */
    public PortalMemory(Path file) {
        this.file = file;
        load();
    }

    /** Remembers a portal block; false if that portal was known already. */
    public synchronized boolean add(String dim, int x, int y, int z) {
        if (find(dim, x, y, z) != null) return false;
        all.add(new Portal(dim, x, y, z));
        save();
        return true;
    }

    /** Forgets the portal at (x, y, z) (it's gone); false if none was known there. */
    public synchronized boolean remove(String dim, int x, int y, int z) {
        Portal p = find(dim, x, y, z);
        if (p == null) return false;
        all.remove(p);
        save();
        return true;
    }

    /** The known portal nearest (x, z) in {@code dim} (horizontally), or null. */
    public synchronized Portal nearest(String dim, double x, double z) {
        Portal best = null;
        double bd = Double.MAX_VALUE;
        for (Portal p : all) {
            if (!p.dim().equals(dim)) continue;
            double d = p.distXZ(x, z);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    public synchronized List<Portal> list(String dim) {
        return all.stream().filter(p -> p.dim().equals(dim)).toList();
    }

    private Portal find(String dim, int x, int y, int z) {
        for (Portal p : all) {
            if (p.dim().equals(dim) && Math.abs(p.x() - x) <= SAME && Math.abs(p.y() - y) <= SAME && Math.abs(p.z() - z) <= SAME) return p;
        }
        return null;
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                if (p.length != 4) continue;
                try {
                    all.add(new Portal(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
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
        for (Portal p : all) lines.add(p.dim() + " " + p.x() + " " + p.y() + " " + p.z());
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // kept in memory for this session at least
        }
    }
}
