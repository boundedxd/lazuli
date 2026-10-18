package dev.lazuli.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Which world Lazuli's memory belongs to: a server (its normalised address) or a singleplayer world (its folder
 * name), as a filename-safe key. What is remembered lives under {@code config/lazuli/servers/<key>/}: one file per
 * kind ({@code chests.txt}, {@code trades.txt}...), or a folder for kinds with many files ({@code iceboat/}).
 */
public final class WorldKey {
    private WorldKey() {}

    public static final String SERVERS = "servers";

    /** {@code server-host[:port]}: lower-case, default port 25565 and a trailing dot dropped, IPv6 kept, filename-safe. */
    public static String server(String address) {
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        String host = a;
        String port = "";
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close > 0) {
                host = a.substring(0, close + 1);
                String rest = a.substring(close + 1);
                if (rest.startsWith(":")) port = rest.substring(1);
            }
        } else {
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) {
                host = a.substring(0, colon);
                port = a.substring(colon + 1);
            } else if (colon >= 0) { // bare IPv6, no port
                host = "[" + a + "]";
            }
        }
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty()) return "unknown";
        if (port.matches("[0-9]{1,5}")) port = String.valueOf(Integer.parseInt(port));
        else port = "";
        if (port.equals("25565")) port = "";
        return sanitize("server-" + host + (port.isEmpty() ? "" : ":" + port));
    }

    /** A singleplayer world by its folder name. */
    public static String world(String folder) {
        return sanitize("world-" + (folder == null || folder.isBlank() ? "unknown" : folder.trim()));
    }

    /** Lower-case, only a-z 0-9 . _ - (anything else becomes _), no leading dot, never empty or very long. */
    public static String sanitize(String s) {
        String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_").replaceAll("^\\.+", "_");
        return out.isEmpty() ? "unknown" : out.length() > 100 ? out.substring(0, 100) : out;
    }

    /** The folder of a world's memory: {@code root/servers/<key>}. */
    public static Path worldDir(Path root, String key) {
        return root.resolve(SERVERS).resolve(key);
    }

    /** The file for a kind of memory in a world: {@code root/servers/<key>/<kind>.txt}. */
    public static Path file(Path root, String kind, String key) {
        return worldDir(root, key).resolve(kind + ".txt");
    }

    /** The folder for a kind of memory in a world (for kinds with several files, like iceboat lines). */
    public static Path dir(Path root, String kind, String key) {
        return worldDir(root, key).resolve(kind);
    }

    /** What the old layout called a world's file: {@code root/<kind>/<this>} ({@code server-<ip>.txt}, {@code world-<name>.txt}). */
    public static String legacyName(String oldId) {
        return oldId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_") + ".txt";
    }

    /**
     * Brings the old layout over, copying and never deleting. A world's own old file ({@code root/<kind>/<legacyName>})
     * becomes its {@code <kind>.txt}. Loose files that belong to no world ({@code root/<kind>/*.txt} not named like a
     * world, e.g. iceboat lines) go into the first world joined afterwards: once, marked by {@code servers/.loose-migrated}.
     * A world is done once (marker {@code servers/<key>/.migrated}), so memory forgotten later doesn't come back.
     *
     * @param fileKinds kinds stored as one file per world
     * @param dirKinds  kinds stored as a folder per world (loose files only)
     * @return whether anything was brought over for this world
     */
    public static boolean migrate(Path root, List<String> fileKinds, List<String> dirKinds, String key, String legacyName) {
        Path world = worldDir(root, key);
        Path marker = world.resolve(".migrated");
        if (Files.exists(marker)) return false;
        boolean did = false;
        try {
            Files.createDirectories(world);
            for (String kind : fileKinds) {
                Path old = root.resolve(kind).resolve(legacyName);
                Path to = file(root, kind, key);
                if (Files.isRegularFile(old) && !Files.exists(to)) {
                    Files.copy(old, to, StandardCopyOption.COPY_ATTRIBUTES);
                    did = true;
                }
            }
            Path loose = root.resolve(SERVERS).resolve(".loose-migrated");
            if (!Files.exists(loose)) {
                for (String kind : dirKinds) {
                    Path kd = root.resolve(kind);
                    if (!Files.isDirectory(kd)) continue;
                    List<Path> files;
                    try (Stream<Path> s = Files.list(kd)) {
                        files = s.filter(Files::isRegularFile).filter(p -> !perWorldName(p.getFileName().toString())).toList();
                    }
                    for (Path p : files) {
                        Path dest = dir(root, kind, key);
                        Files.createDirectories(dest);
                        Path to = dest.resolve(p.getFileName().toString());
                        if (!Files.exists(to)) {
                            Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES);
                            did = true;
                        }
                    }
                }
                Files.writeString(loose, "loose files went into " + key + System.lineSeparator());
            }
            Files.writeString(marker, "migrated" + System.lineSeparator());
        } catch (IOException e) {
            return did;
        }
        return did;
    }

    private static boolean perWorldName(String name) {
        return name.matches("(server-|world-).*\\.txt") || name.equals("unknown.txt");
    }
}
