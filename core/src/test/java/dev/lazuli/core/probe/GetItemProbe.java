package dev.lazuli.core.probe;

import dev.lazuli.core.craft.Drops;
import dev.lazuli.core.craft.ItemPlanner;
import dev.lazuli.core.craft.Recipes;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class GetItemProbe {
    static Set<String> tag(Map<String, String> tags, String id) {
        Set<String> out = new HashSet<>();
        String f = tags.get(id);
        if (f == null) return out;
        for (String s : f.replaceAll("[\\[\\]{}\"\\s]", "").replace("values:", "").split(",")) {
            if (s.startsWith("#")) out.addAll(tag(tags, s.substring(1)));
            else if (!s.isEmpty()) out.add(s.startsWith("id:") ? s.substring(3) : s);
        }
        return out;
    }

    @Test
    void probe() throws Exception {
        var data = VanillaRecipesProbe.DATA;
        if (!Files.isDirectory(data.resolve("loot_table"))) return;
        Recipes r = Recipes.load(VanillaRecipesProbe.read(data.resolve("recipe")), VanillaRecipesProbe.read(data.resolve("tags/item")));
        Drops d = Drops.load(VanillaRecipesProbe.read(data.resolve("loot_table/blocks")));
        var btags = VanillaRecipesProbe.read(data.resolve("tags/block"));
        Set<String> pick = tag(btags, "minecraft:mineable/pickaxe"), stone = tag(btags, "minecraft:needs_stone_tool"),
            iron = tag(btags, "minecraft:needs_iron_tool"), diamond = tag(btags, "minecraft:needs_diamond_tool");
        Map<String, Integer> burn = Map.of("minecraft:coal", 1600, "minecraft:charcoal", 1600, "minecraft:oak_planks", 300,
            "minecraft:spruce_planks", 300, "minecraft:stick", 100);
        ItemPlanner p = new ItemPlanner(r, d, b -> diamond.contains(b) ? 4 : iron.contains(b) ? 3 : stone.contains(b) ? 2
            : pick.contains(b) ? 1 : 0, f -> burn.getOrDefault(f, 0),
            List.of("minecraft:coal", "minecraft:charcoal", "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:stick"),
            ItemPlanner.Rules.ALL);
        for (String item : new String[]{"wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe", "torch", "bucket",
            "furnace", "glass", "bread", "shield", "iron_ingot", "redstone", "chest"}) {
            long t = System.nanoTime();
            var plan = p.plan("minecraft:" + item, 1, Map.of(), 0, false, false);
            System.out.println(item + " (" + (System.nanoTime() - t) / 1000 + " us): " + (plan.ok() ? plan.actions() : plan.problem()));
        }
        for (var have : List.of(Map.of("minecraft:raw_iron", 2, "minecraft:stone_pickaxe", 1, "minecraft:cobblestone", 20, "minecraft:oak_log", 3),
            Map.of("minecraft:raw_iron", 1, "minecraft:iron_ingot", 1, "minecraft:coal", 2), Map.of("minecraft:iron_nugget", 3),
            Map.of("minecraft:gold_ingot", 1), Map.of("minecraft:diamond", 1, "minecraft:iron_pickaxe", 1))) {
            var plan = p.plan("minecraft:iron_pickaxe", 1, have, have.containsKey("minecraft:iron_pickaxe") ? 3 : 2, true, true);
            System.out.println("from " + have + ": " + (plan.ok() ? plan.actions() : plan.problem()));
            plan = p.plan("minecraft:diamond_pickaxe", 1, have, 2, true, true);
            System.out.println("  diamond pick: " + (plan.ok() ? plan.actions() : plan.problem()));
        }
    }
}
