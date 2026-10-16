package dev.lazuli.core.inventory;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * Item keys as the mod writes them: {@code minecraft:dirt}, {@code minecraft:dirt#<components hash>} for a changed
 * stack, and a potion's kind after an {@code @}: {@code minecraft:potion#<hash>@minecraft:swiftness}. A name to look
 * for is an id (namespace optional), or an id with a potion ({@code minecraft:potion@minecraft:swiftness}).
 */
public final class ItemKeys {
    private ItemKeys() {}

    /** The name for bottles of {@code potion} as the item {@code item} (potion, splash_potion, lingering_potion). */
    public static String potion(String item, String potion) {
        return id(item) + "@" + id(potion);
    }

    /** A full id: "Raw_Iron" -> "minecraft:raw_iron" (a potion part kept). */
    public static String id(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains(":") ? n : "minecraft:" + n;
    }

    /** The item id without a potion part. */
    public static String base(String name) {
        int at = name.indexOf('@');
        return at < 0 ? name : name.substring(0, at);
    }

    /** Matches keys of {@code name} (whatever its enchantments...); "all" matches anything. */
    public static Predicate<String> matcher(String name) {
        if (name.equals("all")) return k -> true;
        String full = id(name);
        int at = full.indexOf('@');
        if (at >= 0) {
            String item = full.substring(0, at), kind = full.substring(at);
            return k -> k.startsWith(item + "#") && k.endsWith(kind);
        }
        return k -> k.equals(full) || k.startsWith(full + "#");
    }
}
