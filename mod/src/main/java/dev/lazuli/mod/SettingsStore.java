package dev.lazuli.mod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lazuli.core.settings.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class SettingsStore {
    private SettingsStore() {}

    public static void load(Path file, Settings settings) {
        if (!Files.exists(file)) return;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            Map<String, String> values = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                values.put(e.getKey(), e.getValue().getAsString());
            }
            settings.load(values);
        } catch (IOException | RuntimeException e) {
            System.err.println("[Lazuli] could not read " + file + ": " + e.getMessage());
        }
    }

    public static void save(Path file, Settings settings) {
        JsonObject obj = new JsonObject();
        settings.toMap().forEach(obj::addProperty);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Lazuli] could not write " + file + ": " + e.getMessage());
        }
    }
}
