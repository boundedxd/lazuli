package dev.lazuli.core.trade;

import dev.lazuli.core.inventory.ItemCount;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every villager's trades as last seen in its trading screen (opened by hand or by Lazuli), kept in a small text
 * file: a {@code T id dim x y z profession} line per villager, then an
 * {@code O index left costA n costB|- n result n enchant|- level} line per offer.
 */
public final class TradeMemory {
    private final Path file;
    private final Map<String, Trader> traders = new LinkedHashMap<>();

    /** {@code file} null: kept in memory only. */
    public TradeMemory(Path file) {
        this.file = file;
        load();
    }

    public synchronized void record(Trader t) {
        Trader old = traders.put(t.id(), t);
        if (!t.equals(old)) save();
    }

    /** Only where it is now (it walks about). */
    public synchronized void moved(String id, int x, int y, int z) {
        Trader t = traders.get(id);
        if (t != null && (t.x() != x || t.y() != y || t.z() != z)) {
            traders.put(id, new Trader(id, t.profession(), t.dim(), x, y, z, t.offers()));
        }
    }

    public synchronized Trader get(String id) {
        return traders.get(id);
    }

    public synchronized void forget(String id) {
        if (traders.remove(id) != null) save();
    }

    public synchronized void clear() {
        traders.clear();
        save();
    }

    /** The traders in {@code dim}. */
    public synchronized List<Trader> all(String dim) {
        return traders.values().stream().filter(t -> t.dim().equals(dim)).toList();
    }

    /** The traders in {@code dim}, nearest first. */
    public List<Trader> near(String dim, int px, int py, int pz) {
        List<Trader> out = new ArrayList<>(all(dim));
        out.sort(Comparator.comparingDouble(t -> t.distSq(px, py, pz)));
        return out;
    }

    public synchronized int size() {
        return traders.size();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            Trader cur = null;
            List<Offer> offers = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+");
                try {
                    if (p[0].equals("T") && p.length >= 7) {
                        if (cur != null) traders.put(cur.id(), new Trader(cur.id(), cur.profession(), cur.dim(), cur.x(), cur.y(), cur.z(), offers));
                        cur = new Trader(p[1], p[6], p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]), List.of());
                        offers = new ArrayList<>();
                    } else if (p[0].equals("O") && p.length >= 11 && cur != null) {
                        ItemCount b = p[5].equals("-") ? null : new ItemCount(p[5], Integer.parseInt(p[6]));
                        offers.add(new Offer(Integer.parseInt(p[1]), new ItemCount(p[3], Integer.parseInt(p[4])), b,
                            new ItemCount(p[7], Integer.parseInt(p[8])), p[9].equals("-") ? null : p[9], Integer.parseInt(p[10]),
                            Integer.parseInt(p[2])));
                    }
                } catch (NumberFormatException ignored) {
                    // a line gone wrong: skip it
                }
            }
            if (cur != null) traders.put(cur.id(), new Trader(cur.id(), cur.profession(), cur.dim(), cur.x(), cur.y(), cur.z(), offers));
        } catch (IOException ignored) {
            // unreadable: start empty
        }
    }

    private void save() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (Trader t : traders.values()) {
            lines.add("T " + t.id() + " " + t.dim() + " " + t.x() + " " + t.y() + " " + t.z() + " " + t.profession());
            for (Offer o : t.offers()) {
                lines.add("O " + o.index() + " " + o.left() + " " + o.costA().item() + " " + o.costA().count() + " "
                    + (o.costB() == null ? "- 0" : o.costB().item() + " " + o.costB().count()) + " "
                    + o.result().item() + " " + o.result().count() + " " + (o.enchant() == null ? "-" : o.enchant()) + " " + o.level());
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // kept in memory for this session at least
        }
    }
}
