package dev.lazuli.mod.craft;

import dev.lazuli.core.craft.Drops;
import dev.lazuli.core.craft.MobDrops;
import dev.lazuli.core.craft.Recipes;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.repository.ServerPacksSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The vanilla crafting recipes, read from the game's own data files: a server only tells the client the recipes it
 * has unlocked, so the recipe book can't be used.
 */
public final class RecipeData {
    private static volatile CompletableFuture<Recipes> loading;
    private static volatile CompletableFuture<Drops> drops;

    private RecipeData() {}

    /** Starts loading in the background (once); join it when needed. */
    public static CompletableFuture<Recipes> get() {
        if (loading == null) {
            synchronized (RecipeData.class) {
                if (loading == null) loading = CompletableFuture.supplyAsync(RecipeData::load);
            }
        }
        return loading;
    }

    /** Which blocks drop what (vanilla block loot tables), loaded in the background (once). */
    public static CompletableFuture<Drops> drops() {
        if (drops == null) {
            synchronized (RecipeData.class) {
                if (drops == null) drops = CompletableFuture.supplyAsync(() -> {
                    try (VanillaPackResources pack = ServerPacksSource.createVanillaPackSource()) {
                        return Drops.load(read(pack, "loot_table/blocks"));
                    }
                });
            }
        }
        return drops;
    }

    private static volatile CompletableFuture<MobDrops> mobDrops;

    /** What mobs drop (vanilla entity loot tables), loaded in the background (once). */
    public static CompletableFuture<MobDrops> mobDrops() {
        if (mobDrops == null) {
            synchronized (RecipeData.class) {
                if (mobDrops == null) mobDrops = CompletableFuture.supplyAsync(() -> {
                    try (VanillaPackResources pack = ServerPacksSource.createVanillaPackSource()) {
                        return MobDrops.load(read(pack, "loot_table/entities"));
                    }
                });
            }
        }
        return mobDrops;
    }

    private static Recipes load() {
        try (VanillaPackResources pack = ServerPacksSource.createVanillaPackSource()) {
            return Recipes.load(read(pack, "recipe"), read(pack, "tags/item"));
        }
    }

    /** id ("minecraft:stick") -> file contents, for every JSON file under data/minecraft/{dir}. */
    private static Map<String, String> read(VanillaPackResources pack, String dir) {
        Map<String, String> out = new HashMap<>();
        pack.listResources(PackType.SERVER_DATA, "minecraft", dir, (id, io) -> {
            String path = id.getPath();
            if (!path.endsWith(".json")) return;
            try (InputStream in = io.get()) {
                out.put(id.getNamespace() + ":" + path.substring(dir.length() + 1, path.length() - 5),
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // an unreadable file: skip it
            }
        });
        return out;
    }
}
