package dev.lazuli.core.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Saved routines (#routine add): a name and its commands, one {@code name<TAB>cmd ; cmd ; ...} line each in a file. */
public final class Routines {
    private final Path file;
    private final Map<String, List<String>> all = new LinkedHashMap<>();

    /** {@code file} null: kept in memory only. */
    public Routines(Path file) {
        this.file = file;
        load();
    }

    /** Splits "a ; b ; c" into its commands. */
    public static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        for (String s : line.split(";")) if (!s.isBlank()) out.add(s.trim());
        return out;
    }

    public synchronized void set(String name, List<String> steps) {
        all.put(name.toLowerCase(Locale.ROOT), List.copyOf(steps));
        save();
    }

    public synchronized boolean remove(String name) {
        boolean had = all.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (had) save();
        return had;
    }

    public synchronized List<String> get(String name) {
        return all.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized Map<String, List<String>> list() {
        return new LinkedHashMap<>(all);
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                all.put(line.substring(0, tab), split(line.substring(tab + 1)));
            }
        } catch (IOException ignored) {
            // unreadable: start empty
        }
    }

    private void save() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (var e : all.entrySet()) lines.add(e.getKey() + "\t" + String.join(" ; ", e.getValue()));
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // kept in memory for this session at least
        }
    }
}
