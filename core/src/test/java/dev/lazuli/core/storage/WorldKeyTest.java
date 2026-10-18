package dev.lazuli.core.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorldKeyTest {
    @Test
    void serverAddresses() {
        assertEquals("server-play.example.com", WorldKey.server("Play.Example.com"));
        assertEquals("server-play.example.com", WorldKey.server("play.example.com:25565"));
        assertEquals("server-play.example.com", WorldKey.server("play.example.com."));
        assertEquals("server-play.example.com_25566", WorldKey.server("PLAY.example.com:25566"));
        assertEquals("server-play.example.com_25566", WorldKey.server("play.example.com.:25566"));
        assertEquals("server-___1_", WorldKey.server("[::1]"));
        assertEquals("server-___1_", WorldKey.server("[::1]:25565"));
        assertEquals("server-___1__25570", WorldKey.server("[::1]:25570"));
        assertEquals("server-___1_", WorldKey.server("::1"));
        assertEquals("unknown", WorldKey.server(""));
        assertEquals("unknown", WorldKey.server(null));
    }

    @Test
    void sanitising() {
        assertEquals("world-my_world_", WorldKey.world("My World!"));
        assertEquals("world-unknown", WorldKey.world(" "));
        assertEquals("_hidden", WorldKey.sanitize("..hidden"));
        assertFalse(WorldKey.sanitize("a/b\\c:d").contains("/"));
        assertTrue(WorldKey.sanitize("x".repeat(500)).length() <= 100);
        assertEquals("unknown", WorldKey.sanitize(""));
    }

    @Test
    void layout() {
        Path root = Path.of("cfg");
        assertEquals(root.resolve("servers").resolve("k").resolve("chests.txt"), WorldKey.file(root, "chests", "k"));
        assertEquals(root.resolve("servers").resolve("k").resolve("iceboat"), WorldKey.dir(root, "iceboat", "k"));
    }

    @Test
    void legacyNamesMatchTheOldFiles() {
        assertEquals("server-play.example.com_25566.txt", WorldKey.legacyName("server-Play.Example.com:25566"));
        assertEquals("world-my_world.txt", WorldKey.legacyName("world-My World"));
    }

    @Test
    void worldsTakeTheirOwnOldFilesOnce(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("waypoints"));
        Files.writeString(root.resolve("waypoints").resolve("server-a.example.txt"), "A");
        Files.writeString(root.resolve("waypoints").resolve("server-b.example.txt"), "B");
        Files.createDirectories(root.resolve("iceboat"));
        Files.writeString(root.resolve("iceboat").resolve("track1.txt"), "T");
        List<String> files = List.of("waypoints", "chests"), dirs = List.of("iceboat");
        assertTrue(WorldKey.migrate(root, files, dirs, "server-a.example", "server-a.example.txt"));
        assertEquals("A", Files.readString(WorldKey.file(root, "waypoints", "server-a.example")));
        assertEquals("T", Files.readString(WorldKey.dir(root, "iceboat", "server-a.example").resolve("track1.txt")));
        assertTrue(Files.exists(root.resolve("waypoints").resolve("server-b.example.txt")), "old files are kept");
        // asking again does nothing, and what was forgotten stays forgotten
        Files.delete(WorldKey.file(root, "waypoints", "server-a.example"));
        assertFalse(WorldKey.migrate(root, files, dirs, "server-a.example", "server-a.example.txt"));
        assertFalse(Files.exists(WorldKey.file(root, "waypoints", "server-a.example")));
        // the other world gets its own file, but not the loose iceboat lines (they went to the first)
        assertTrue(WorldKey.migrate(root, files, dirs, "server-b.example", "server-b.example.txt"));
        assertEquals("B", Files.readString(WorldKey.file(root, "waypoints", "server-b.example")));
        assertFalse(Files.exists(WorldKey.dir(root, "iceboat", "server-b.example").resolve("track1.txt")));
        // a world with nothing old gets nothing
        assertFalse(WorldKey.migrate(root, files, dirs, "server-c.example", "server-c.example.txt"));
        assertFalse(Files.exists(WorldKey.file(root, "waypoints", "server-c.example")));
    }
}
