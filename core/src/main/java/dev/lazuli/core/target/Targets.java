package dev.lazuli.core.target;

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
import java.util.Set;

/**
 * The player's named targets (chests, villagers, stations...), kept in a text file, one per line:
 * {@code name kind dim x y z uuid|- block|- label...}. Names are lower-case; the same block or entity is only
 * ever one target (naming it again renames it).
 */
public final class Targets {
    /** Words the routine language uses after an {@code @}: never target names. */
    public static final Set<String> RESERVED = Set.copyOf(dev.lazuli.core.task.Step.MODIFIERS);

    /** Commands that take the position of the block they work at as {@code at x y z}. */
    private static final Set<String> AT_WORDS = Set.of("craft", "smelt", "brew", "enchant", "repair", "combine", "smith");

    /** {@code @name} in a command or routine step. */
    private static final java.util.regex.Pattern REF = java.util.regex.Pattern.compile("(?<![\\w@])@([A-Za-z0-9_][A-Za-z0-9_.-]*)");

    private final Path file;
    private final Map<String, Target> all = new LinkedHashMap<>();

    /** {@code file} null: kept in memory only. */
    public Targets(Path file) {
        this.file = file;
        load();
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[a-z0-9_.-]{1,32}") && !RESERVED.contains(name);
    }

    /** Lower-cased (a leading @ dropped), or throws for a name that can't be used. */
    public static String clean(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("@")) n = n.substring(1);
        if (!validName(n)) throw new IllegalArgumentException("Bad target name '" + name + "' (letters, digits, _ . -; not a routine word)");
        return n;
    }

    /** The next free name for a kind: chest1, chest2... (or from {@code stem}, e.g. stand1, furnace1). */
    public synchronized String autoName(Target.Kind kind, String stem) {
        String s = stem == null || stem.isEmpty() ? kind.prefix() : stem;
        for (int i = 1; ; i++) if (!all.containsKey(s + i)) return s + i;
    }

    /**
     * Registers or moves a target. Where the same block (or entity uuid) is already a target, that one is replaced:
     * the new name wins when {@code name} is given, otherwise the old name stays. Returns what was stored.
     */
    public synchronized Target pick(String name, Target.Kind kind, String dim, int x, int y, int z, String uuid, String block,
                                    String label, String stem) {
        Target existing = at(dim, x, y, z, uuid);
        String n;
        if (name != null) n = clean(name);
        else if (existing != null) n = existing.name();
        else n = autoName(kind, stem);
        if (existing != null) all.remove(existing.name());
        all.remove(n); // naming over another target moves that name here
        Target t = new Target(n, kind, dim, x, y, z, uuid, block, label);
        all.put(n, t);
        save();
        return t;
    }

    public synchronized void put(Target t) {
        all.put(t.name(), t);
        save();
    }

    /** The target for the same entity (uuid) or the same block, if any. */
    public synchronized Target at(String dim, int x, int y, int z, String uuid) {
        for (Target t : all.values()) {
            if (uuid != null ? uuid.equals(t.uuid()) : t.uuid() == null && t.dim().equals(dim) && t.x() == x && t.y() == y && t.z() == z) return t;
        }
        return null;
    }

    public synchronized Target get(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        return all.get(n.startsWith("@") ? n.substring(1) : n);
    }

    /** {@code @name}, a bare name or {@code uuid:<uuid>}: the target, or null. */
    public synchronized Target resolve(String token) {
        if (token == null) return null;
        if (token.regionMatches(true, 0, "uuid:", 0, 5)) {
            String u = token.substring(5);
            for (Target t : all.values()) if (u.equalsIgnoreCase(t.uuid())) return t;
            return null;
        }
        return get(token);
    }

    /**
     * Writes the positions of the targets named with {@code @name} into a command: {@code x y z}; {@code at x y z} for
     * the commands that work at a block (craft, smelt, brew...); {@code --with <uuid>} for a villager in #trade.
     * Routine modifier words ({@code @retry}...) are left alone. Throws IllegalArgumentException naming the targets
     * that don't exist.
     */
    public synchronized String expand(String text) {
        if (text.indexOf('@') < 0) return text;
        String word = text.trim().replaceFirst("^[^a-zA-Z]+", "").split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        java.util.regex.Matcher m = REF.matcher(text);
        StringBuilder out = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (RESERVED.contains(name)) {
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group()));
                continue;
            }
            Target t = all.get(name);
            if (t == null) {
                missing.add("@" + name);
                continue;
            }
            String where;
            if (t.isEntity() && (word.equals("trade") || word.equals("trades"))) where = "--with " + t.uuid();
            else if (AT_WORDS.contains(word)) where = "at " + t.coords();
            else where = t.coords();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(where));
        }
        if (!missing.isEmpty()) {
            String first = missing.get(0);
            List<String> close = close(first);
            throw new IllegalArgumentException("No target " + String.join(", ", missing)
                + (close.isEmpty() ? " (the target command lists them)" : " (did you mean " + String.join(", ", close.stream().map(x -> "@" + x).toList()) + "?)"));
        }
        m.appendTail(out);
        return out.toString();
    }

    public synchronized boolean remove(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        boolean had = all.remove(n.startsWith("@") ? n.substring(1) : n) != null;
        if (had) save();
        return had;
    }

    /** False when {@code old} doesn't exist or {@code to} is taken. */
    public synchronized boolean rename(String old, String to) {
        String o = old.toLowerCase(Locale.ROOT);
        if (o.startsWith("@")) o = o.substring(1);
        String n = clean(to);
        Target t = all.get(o);
        if (t == null || all.containsKey(n) && !n.equals(o)) return false;
        Map<String, Target> copy = new LinkedHashMap<>();
        for (var e : all.entrySet()) {
            if (e.getKey().equals(o)) copy.put(n, t.named(n));
            else copy.put(e.getKey(), e.getValue());
        }
        all.clear();
        all.putAll(copy);
        save();
        return true;
    }

    public synchronized void clear() {
        all.clear();
        save();
    }

    public synchronized List<Target> all() {
        return new ArrayList<>(all.values());
    }

    public synchronized List<Target> byKind(Target.Kind kind) {
        return all.values().stream().filter(t -> t.kind() == kind).toList();
    }

    public synchronized List<Target> inDim(String dim) {
        return all.values().stream().filter(t -> t.dim().equals(dim)).toList();
    }

    /** The nearest target in {@code dim} (of {@code kind}, or any when null), or null. */
    public synchronized Target nearest(String dim, int px, int py, int pz, Target.Kind kind) {
        return all.values().stream().filter(t -> t.dim().equals(dim) && (kind == null || t.kind() == kind))
            .min(Comparator.comparingDouble(t -> t.distSq(px, py, pz))).orElse(null);
    }

    /** Names like {@code typo} (shared start, contained, or a typo away), for "no such target" errors. */
    public synchronized List<String> close(String typo) {
        String t = typo.toLowerCase(Locale.ROOT);
        if (t.startsWith("@")) t = t.substring(1);
        List<String> out = new ArrayList<>();
        for (String n : all.keySet()) {
            if (t.isEmpty() || n.contains(t) || t.contains(n) || n.charAt(0) == t.charAt(0) || editDistance(n, t) <= 2) out.add(n);
            if (out.size() >= 8) break;
        }
        return out;
    }

    private static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            prev = cur;
        }
        return prev[b.length()];
    }

    public synchronized int size() {
        return all.size();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.trim().split("\\s+", 9);
                if (p.length < 8) continue;
                try {
                    Target t = new Target(p[0], Target.Kind.valueOf(p[1]), p[2], Integer.parseInt(p[3]), Integer.parseInt(p[4]),
                        Integer.parseInt(p[5]), dash(p[6]), dash(p[7]), p.length > 8 ? p[8] : "");
                    all.put(t.name(), t);
                } catch (IllegalArgumentException ignored) {
                    // a hand-edited line gone wrong: skip it
                }
            }
        } catch (IOException ignored) {
            // unreadable: start empty
        }
    }

    private static String dash(String s) {
        return s.equals("-") ? null : s;
    }

    private void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (Target t : all.values()) {
            sb.append(t.name()).append(' ').append(t.kind().name()).append(' ').append(t.dim()).append(' ')
                .append(t.x()).append(' ').append(t.y()).append(' ').append(t.z()).append(' ')
                .append(t.uuid() == null ? "-" : t.uuid()).append(' ').append(t.block() == null ? "-" : t.block()).append(' ')
                .append(t.label() == null ? "" : t.label().replace('\n', ' ')).append(System.lineSeparator());
        }
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // can't write: still works in memory
        }
    }
}
