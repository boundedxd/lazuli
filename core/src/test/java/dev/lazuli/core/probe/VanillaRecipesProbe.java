package dev.lazuli.core.probe;

import dev.lazuli.core.craft.CraftTree;
import dev.lazuli.core.craft.Recipes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

class VanillaRecipesProbe {
    static final Path DATA = Path.of("C:/Users/colin/AppData/Local/Temp/claude/C--Users-colin-Documents-lambdawouldntmakeshitsoimamakeitwithclaude/20cbe64f-d6eb-46ff-8eda-5312d848c259/scratchpad/mcdata/data/minecraft");

    static Map<String, String> read(Path dir) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.filter(f -> f.toString().endsWith(".json")).toList()) {
                String rel = dir.relativize(p).toString().replace('\\', '/');
                out.put("minecraft:" + rel.substring(0, rel.length() - 5), Files.readString(p));
            }
        }
        return out;
    }

    @Test
    void probe() throws Exception {
        if (!Files.isDirectory(DATA)) return;
        long t0 = System.nanoTime();
        var rs = read(DATA.resolve("recipe"));
        var ts = read(DATA.resolve("tags/item"));
        System.out.println("loaded read " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        t0 = System.nanoTime();
        Recipes r = Recipes.load(rs, ts);
        System.out.println("loaded " + r.size() + " in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        for (var c : new Object[][]{{"minecraft:wooden_pickaxe", Map.of("minecraft:oak_log", 3)},
            {"minecraft:stone_pickaxe", Map.of("minecraft:cobblestone", 3, "minecraft:spruce_log", 1)},
            {"minecraft:iron_pickaxe", Map.of("minecraft:iron_ingot", 3)},
            {"minecraft:iron_pickaxe", Map.of("minecraft:iron_block", 1, "minecraft:stick", 2)},
            {"minecraft:chest", Map.of("minecraft:birch_log", 2)},
            {"minecraft:torch", Map.of("minecraft:coal", 1, "minecraft:oak_log", 1)},
            {"minecraft:furnace", Map.of("minecraft:cobblestone", 8)},
            {"minecraft:diamond_pickaxe", Map.of()}}) {
            long t = System.nanoTime();
            @SuppressWarnings("unchecked")
            CraftTree.Result res = CraftTree.resolve(r, (String) c[0], 1, (Map<String, Integer>) c[1]);
            System.out.println(c[0] + " from " + c[1] + " (" + (System.nanoTime() - t) / 1000 + " us): "
                + (res.ok() ? res.steps() : "missing " + res.missing()));
        }
    }
}
