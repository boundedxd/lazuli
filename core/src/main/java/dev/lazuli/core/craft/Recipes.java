package dev.lazuli.core.craft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Every crafting and furnace recipe, read from the game's data files, by what it makes. */
public final class Recipes {
    private final Map<String, List<Recipe>> byResult = new HashMap<>();
    private final Map<String, List<SmeltRecipe>> smeltByResult = new HashMap<>(), smeltByInput = new HashMap<>();

    private Recipes() {}

    /**
     * @param recipes recipe id -> its JSON file
     * @param tags    item tag id -> its JSON file (tags in ingredients are resolved through these)
     */
    public static Recipes load(Map<String, String> recipes, Map<String, String> tags) {
        Recipes r = new Recipes();
        Map<String, Set<String>> resolved = new HashMap<>();
        recipes.keySet().stream().sorted().forEach(id -> {
            java.util.function.Function<String, Set<String>> tag = t -> tag(t, tags, resolved, new HashSet<>());
            try {
                Object json = Json.parse(recipes.get(id));
                Recipe recipe = read(id, json, tag);
                if (recipe != null) r.byResult.computeIfAbsent(recipe.result(), k -> new ArrayList<>()).add(recipe);
                SmeltRecipe smelt = readSmelt(id, json, tag);
                if (smelt != null) {
                    r.smeltByResult.computeIfAbsent(smelt.result(), k -> new ArrayList<>()).add(smelt);
                    for (String in : smelt.input()) r.smeltByInput.computeIfAbsent(in, k -> new ArrayList<>()).add(smelt);
                }
            } catch (RuntimeException e) {
                // a format it doesn't know: skip that one recipe
            }
        });
        return r;
    }

    /** The recipes making {@code item} (a full id like minecraft:stick). */
    public List<Recipe> producing(String item) {
        return byResult.getOrDefault(item, List.of());
    }

    /** The furnace recipes making {@code item} (all furnace kinds). */
    public List<SmeltRecipe> smelting(String item) {
        return smeltByResult.getOrDefault(item, List.of());
    }

    /** The furnace recipes cooking {@code item}. */
    public List<SmeltRecipe> smeltingFrom(String item) {
        return smeltByInput.getOrDefault(item, List.of());
    }

    public int size() {
        return byResult.values().stream().mapToInt(List::size).sum();
    }

    @SuppressWarnings("unchecked")
    private static Recipe read(String id, Object json, java.util.function.Function<String, Set<String>> tag) {
        Map<String, Object> o = (Map<String, Object>) json;
        String type = (String) o.get("type");
        Map<String, Object> result = (Map<String, Object>) o.get("result");
        if (result == null || type == null) return null;
        String out = (String) result.get("id");
        int count = result.get("count") instanceof Double d ? d.intValue() : 1;
        switch (type) {
            case "minecraft:crafting_shaped" -> {
                List<Object> pattern = (List<Object>) o.get("pattern");
                Map<String, Object> key = (Map<String, Object>) o.get("key");
                int h = pattern.size(), w = 0;
                for (Object row : pattern) w = Math.max(w, ((String) row).length());
                List<Set<String>> cells = new ArrayList<>();
                for (Object row : pattern) {
                    String line = (String) row;
                    for (int x = 0; x < w; x++) {
                        char c = x < line.length() ? line.charAt(x) : ' ';
                        cells.add(c == ' ' ? Set.of() : ingredient(key.get(String.valueOf(c)), tag));
                    }
                }
                return new Recipe(id, out, count, w, h, List.copyOf(cells), true);
            }
            case "minecraft:crafting_shapeless" -> {
                List<Set<String>> cells = new ArrayList<>();
                for (Object i : (List<Object>) o.get("ingredients")) cells.add(ingredient(i, tag));
                return new Recipe(id, out, count, cells.size(), 1, List.copyOf(cells), false);
            }
            default -> {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static SmeltRecipe readSmelt(String id, Object json, java.util.function.Function<String, Set<String>> tag) {
        Map<String, Object> o = (Map<String, Object>) json;
        SmeltRecipe.Furnace f = switch (String.valueOf(o.get("type"))) {
            case "minecraft:smelting" -> SmeltRecipe.Furnace.FURNACE;
            case "minecraft:blasting" -> SmeltRecipe.Furnace.BLAST_FURNACE;
            case "minecraft:smoking" -> SmeltRecipe.Furnace.SMOKER;
            default -> null;
        };
        if (f == null) return null;
        Map<String, Object> result = (Map<String, Object>) o.get("result");
        int ticks = o.get("cookingtime") instanceof Double d ? d.intValue() : f == SmeltRecipe.Furnace.FURNACE ? 200 : 100;
        return new SmeltRecipe(id, (String) result.get("id"), ingredient(o.get("ingredient"), tag), f, ticks);
    }

    /** "minecraft:x", "#minecraft:tag" or a list of those. */
    @SuppressWarnings("unchecked")
    private static Set<String> ingredient(Object v, java.util.function.Function<String, Set<String>> tag) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof List<?> list) for (Object e : list) out.addAll(ingredient(e, tag));
        else if (v instanceof String s) {
            if (s.startsWith("#")) out.addAll(tag.apply(s.substring(1)));
            else out.add(s);
        } else if (v instanceof Map<?, ?> m && m.get("item") instanceof String s) out.add(s); // the old format
        if (out.isEmpty()) throw new IllegalArgumentException("empty ingredient " + v);
        return Collections.unmodifiableSet(out);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> tag(String id, Map<String, String> files, Map<String, Set<String>> done, Set<String> visiting) {
        Set<String> known = done.get(id);
        if (known != null) return known;
        String file = files.get(id);
        if (file == null || !visiting.add(id)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object e : (List<Object>) ((Map<String, Object>) Json.parse(file)).get("values")) {
            String s = e instanceof Map<?, ?> m ? (String) m.get("id") : (String) e;
            if (s.startsWith("#")) out.addAll(tag(s.substring(1), files, done, visiting));
            else out.add(s);
        }
        done.put(id, out);
        return out;
    }
}
