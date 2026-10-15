package dev.lazuli.core.settings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class Settings {
    private final Map<String, Setting<?>> byName = new LinkedHashMap<>();

    public final Setting<Boolean> allowSprint = bool("allowSprint", true);
    /** Swim at the surface with the head out (never sprint-swim under water, where the air runs out). */
    public final Setting<Boolean> surfaceSwim = bool("surfaceSwim", true);
    public final Setting<Integer> maxFallHeight = integer("maxFallHeight", 3, 0, 20);
    public final Setting<Boolean> allowParkour = bool("allowParkour", true);
    public final Setting<Boolean> allowBreak = bool("allowBreak", true);
    public final Setting<Boolean> allowPlace = bool("allowPlace", true);
    /** Bridge eagle-style: crouch only at the edge (faster); off, ninja-style, sneaking the whole way. */
    public final Setting<Boolean> eagleBridge = bool("eagleBridge", false);
    /** Block ids (namespace optional) the path may place for bridging and pillaring. */
    public final Setting<String> throwawayBlocks = register(new Setting<>("throwawayBlocks",
        "dirt,cobblestone,cobbled_deepslate,netherrack,stone,andesite,diorite,granite,tuff,deepslate,blackstone,end_stone",
        s -> s.replace(" ", "")));
    /** Don't place blocks it's collecting (#mine's blocks, what #getitem needs) for bridging and pillaring. */
    public final Setting<Boolean> keepCollected = bool("keepCollected", true);
    public final Setting<Boolean> cancelOnUserInput = bool("cancelOnUserInput", true);
    public final Setting<Double> rotationSpeed = decimal("rotationSpeed", 45.0, 1.0, 180.0);
    /** Sneak to stop dead before a long or curved jump (off: eases to a stop walking). */
    public final Setting<Boolean> allowSneakStop = bool("allowSneakStop", false);
    /** Snap the view sideways or round to steer or brake mid-air (off: look where it goes, steer with A, D and S). */
    public final Setting<Boolean> allowFlicks = bool("allowFlicks", false);
    /** Turn only server-side, leaving the camera to the mouse (Baritone's freeLook). */
    public final Setting<Boolean> freeLook = bool("freeLook", false);
    /** #mine walks over to the drops of each block it breaks. */
    public final Setting<Boolean> minePickup = bool("minePickup", true);
    /** #follow stops this many blocks from whoever it follows. */
    public final Setting<Integer> followRadius = integer("followRadius", 3, 1, 32);
    /** #farm works this far (blocks, horizontally) around where it started. */
    public final Setting<Integer> farmRange = integer("farmRange", 32, 4, 128);
    /** Eat from the hotbar while Lazuli is busy and hunger drops to {@link #autoEatHunger}. */
    public final Setting<Boolean> autoEat = bool("autoEat", true);
    public final Setting<Integer> autoEatHunger = integer("autoEatHunger", 14, 1, 19);
    /** Put on better armor from the inventory. */
    public final Setting<Boolean> autoArmor = bool("autoArmor", true);
    /** Move the best tool from the inventory into the hotbar when mining. */
    public final Setting<Boolean> allowInventory = bool("allowInventory", true);
    /**
     * Open the inventory screen (standing still) for clicks in the player's own inventory, as a player would:
     * anticheats flag inventory clicks with no screen open or while moving. Off: click straight away (faster).
     */
    public final Setting<Boolean> openInventory = bool("openInventory", true);
    /** Inventory clicks (moving, dropping items) sent per tick: more is faster, fewer looks less like a bot. */
    public final Setting<Integer> clicksPerTick = integer("clicksPerTick", 2, 1, 20);
    /** #craft also crafts the ingredients it lacks (logs into planks, planks into sticks...). */
    public final Setting<Boolean> craftIngredients = bool("craftIngredients", true);
    /** #craft places a crafting table (crafting one if need be) when a recipe needs one and none is near. */
    public final Setting<Boolean> craftPlaceTable = bool("craftPlaceTable", true);
    /** How far (blocks) #craft and #smelt look for a crafting table or furnace. */
    public final Setting<Integer> stationRange = integer("stationRange", 32, 4, 64);
    /** Fuel #smelt may burn, best first (item ids, namespace optional). */
    public final Setting<String> smeltFuels = register(new Setting<>("smeltFuels",
        "coal,charcoal,coal_block,dried_kelp_block,blaze_rod,oak_planks,spruce_planks,birch_planks,jungle_planks,acacia_planks,"
            + "dark_oak_planks,mangrove_planks,cherry_planks,pale_oak_planks,bamboo_planks,crimson_planks,warped_planks,stick",
        s -> s.replace(" ", "").toLowerCase(java.util.Locale.ROOT)));
    /** #getitem may mine for what it needs. */
    public final Setting<Boolean> getItemMine = bool("getItemMine", true);
    /** #getitem may smelt for what it needs. */
    public final Setting<Boolean> getItemSmelt = bool("getItemSmelt", true);
    /** #smelt waits at the furnace until it's done; off: loads it, leaves, and #smelt collect goes back. */
    public final Setting<Boolean> smeltWait = bool("smeltWait", true);
    /** #smelt uses a blast furnace (ores) or smoker (food), twice as fast, when there's one near or in the inventory. */
    public final Setting<Boolean> smeltFastFurnaces = bool("smeltFastFurnaces", true);
    /** #smelt places a furnace (crafting one if need be) when none is near. */
    public final Setting<Boolean> smeltPlaceFurnace = bool("smeltPlaceFurnace", true);
    /** Never use a tool's last few points of durability. */
    public final Setting<Boolean> saveTools = bool("saveTools", true);
    public final Setting<Integer> evalHorizon = integer("evalHorizon", 32, 1, 4096);
    public final Setting<Double> heuristicWeight = decimal("heuristicWeight", 1.2, 1.0, 10.0);
    public final Setting<Boolean> renderPath = bool("renderPath", true);
    public final Setting<String> prefix = register(new Setting<>("prefix", "#", s -> {
        if (s.isEmpty() || s.contains(" ")) throw new IllegalArgumentException("prefix must be non-empty and contain no spaces");
        return s;
    }));

    public Collection<Setting<?>> all() {
        return byName.values();
    }

    public Optional<Setting<?>> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Setting<?> s : byName.values()) out.put(s.name(), s.format());
        return out;
    }

    /** Applies stored values, ignoring unknown names and invalid values. */
    public void load(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            byName(e.getKey()).ifPresent(s -> {
                try {
                    s.parse(e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // keep the default
                }
            });
        }
    }

    private <T> Setting<T> register(Setting<T> s) {
        byName.put(s.name().toLowerCase(Locale.ROOT), s);
        return s;
    }

    private Setting<Boolean> bool(String name, boolean def) {
        return register(new Setting<>(name, def, s -> {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
            throw new IllegalArgumentException(name + " must be true or false");
        }));
    }

    private Setting<Integer> integer(String name, int def, int min, int max) {
        return register(new Setting<>(name, def, s -> {
            int v;
            try {
                v = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a whole number");
            }
            if (v < min || v > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            return v;
        }));
    }

    private Setting<Double> decimal(String name, double def, double min, double max) {
        return register(new Setting<>(name, def, s -> {
            double v;
            try {
                v = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
            if (!(v >= min && v <= max)) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            return v;
        }));
    }
}
