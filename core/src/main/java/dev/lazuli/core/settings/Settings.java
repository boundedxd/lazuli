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
    /** Across open water use swim mode (sprint-swimming, fast) just under the surface, coming up for air in time. */
    public final Setting<Boolean> sprintSwim = bool("sprintSwim", true);
    /** Cross long stretches of water by boat when there's one in the inventory. */
    public final Setting<Boolean> useBoats = bool("useBoats", true);
    /** Water blocks in a row on the path it takes to bother with the boat. */
    public final Setting<Integer> boatMinWater = integer("boatMinWater", 12, 3, 128);
    /** Break the boat after getting out, to take it along. */
    public final Setting<Boolean> boatPickup = bool("boatPickup", true);
    public final Setting<Integer> maxFallHeight = integer("maxFallHeight", 3, 0, 20);
    /** Place water under itself before landing from a fall that would hurt (water bucket in the inventory). */
    public final Setting<Boolean> waterClutch = bool("waterClutch", true);
    /** With a water bucket, the path may drop this far onto solid ground and clutch (0: never on purpose). */
    public final Setting<Integer> clutchFallHeight = integer("clutchFallHeight", 30, 0, 200);
    /** Scoop the clutch water back up after landing. */
    public final Setting<Boolean> clutchPickup = bool("clutchPickup", true);
    public final Setting<Boolean> allowParkour = bool("allowParkour", true);
    public final Setting<Boolean> allowBreak = bool("allowBreak", true);
    public final Setting<Boolean> allowPlace = bool("allowPlace", true);
    /** Bridge eagle-style: crouch only at the edge (faster); off, ninja-style, sneaking the whole way. */
    public final Setting<Boolean> eagleBridge = bool("eagleBridge", false);
    /** Crouch while standing still to break a block in the path (off: stand up, as most players do). */
    public final Setting<Boolean> mineSneak = bool("mineSneak", false);
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
    /** #mine with a full inventory throws away junk blocks (the throwaway list) to make room for what it is after. */
    public final Setting<Boolean> mineDropJunk = bool("mineDropJunk", true);
    /** #follow stops this many blocks from whoever it follows. */
    public final Setting<Integer> followRadius = integer("followRadius", 3, 1, 32);
    /** #farm works this far (blocks, horizontally) around where it started. */
    public final Setting<Integer> farmRange = integer("farmRange", 32, 4, 128);
    /** #farm sprints between crops (it still never hops onto farmland). */
    public final Setting<Boolean> farmSprint = bool("farmSprint", true);
    /** Eat from the hotbar while Lazuli is busy and hunger drops to {@link #autoEatHunger}. */
    public final Setting<Boolean> autoEat = bool("autoEat", true);
    public final Setting<Integer> autoEatHunger = integer("autoEatHunger", 14, 1, 19);
    /** Put on better armor from the inventory. */
    public final Setting<Boolean> autoArmor = bool("autoArmor", true);
    /** Place torches from the hotbar where it's dark underground, while pathing. */
    public final Setting<Boolean> autoTorch = bool("autoTorch", true);
    /** Block light below which autoTorch places one (mobs spawn at 0; 14 is right next to a torch). */
    public final Setting<Integer> torchLight = integer("torchLight", 4, 1, 14);
    /** #sleep places a bed from the hotbar when none is near. */
    public final Setting<Boolean> sleepPlaceBed = bool("sleepPlaceBed", true);
    /** #sleep breaks the bed it placed in the morning, to take it along. */
    public final Setting<Boolean> sleepPickupBed = bool("sleepPickupBed", true);
    /** While busy, run from a creeper that starts hissing close by (the job waits). */
    public final Setting<Boolean> dodgeCreepers = bool("dodgeCreepers", true);
    /** While busy, fight monsters that come within reach (best sword or axe, full swings); the job waits. */
    public final Setting<Boolean> defend = bool("defend", true);
    /** While busy, at {@link #retreatHealth} or less: get away from monsters, eat and wait to heal (the job waits). */
    public final Setting<Boolean> lowHpRetreat = bool("lowHpRetreat", true);
    public final Setting<Integer> retreatHealth = integer("retreatHealth", 8, 1, 19);
    /** In a fight, shoot skeletons and creepers that are still a way off with a bow (needs a bow and arrows). */
    public final Setting<Boolean> useBow = bool("useBow", true);
    /** In a fight, hold the shield up (offhand) between swings and against arrows. */
    public final Setting<Boolean> useShield = bool("useShield", true);
    /** Run instead of fighting when there are too many monsters for its health, armor and weapon. */
    public final Setting<Boolean> fightOrFlee = bool("fightOrFlee", true);
    /** Keep a totem of undying in the offhand when there's one in the inventory. */
    public final Setting<Boolean> autoTotem = bool("autoTotem", true);
    /** Keep a shield in the offhand (when there's no totem to put there). */
    public final Setting<Boolean> autoShield = bool("autoShield", true);
    /** Paths keep away from hostile mobs (the closer, the costlier), going around them when that's not much longer. */
    public final Setting<Boolean> avoidMobs = bool("avoidMobs", true);
    /** How far from a mob paths start to bend away, in blocks (creepers: half again). */
    public final Setting<Integer> mobAvoidRadius = integer("mobAvoidRadius", 6, 2, 16);
    /** Extra ticks for a cell right at a mob (fading to 0 at the radius). */
    public final Setting<Integer> mobAvoidCost = integer("mobAvoidCost", 40, 1, 400);
    /** Paths keep a block away from lava (and fire, cactus...) when there's room. */
    public final Setting<Boolean> avoidLava = bool("avoidLava", true);
    /** A trip with nothing happening for ~6 seconds is planned again from scratch (what #kill and a rerun would do). */
    public final Setting<Boolean> unstick = bool("unstick", true);
    /** Stuck: also swap in a fresh planner thread and forget all trip state (what #kill did), keeping the job going. */
    public final Setting<Boolean> unstickRestart = bool("unstickRestart", true);
    /** Save a "death" waypoint where the player dies ({@code #back} goes there). */
    public final Setting<Boolean> deathWaypoint = bool("deathWaypoint", true);
    /** After a respawn, go back to where the player died and pick the dropped items up before they despawn. */
    public final Setting<Boolean> deathRecovery = bool("deathRecovery", true);
    /** Draw this dimension's waypoints in the world. */
    public final Setting<Boolean> renderWaypoints = bool("renderWaypoints", true);
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
    /** #craft / #smelt break a crafting table or furnace they placed themselves once done, and pick it up. */
    public final Setting<Boolean> pickupStations = bool("pickupStations", true);
    /** How far (blocks) #craft and #smelt look for a crafting table or furnace. */
    public final Setting<Integer> stationRange = integer("stationRange", 32, 4, 64);
    /** Fuel #smelt may burn, best first (item ids, namespace optional). */
    public final Setting<String> smeltFuels = register(new Setting<>("smeltFuels",
        "coal,charcoal,coal_block,dried_kelp_block,blaze_rod,oak_planks,spruce_planks,birch_planks,jungle_planks,acacia_planks,"
            + "dark_oak_planks,mangrove_planks,cherry_planks,pale_oak_planks,bamboo_planks,crimson_planks,warped_planks,stick",
        s -> s.replace(" ", "").toLowerCase(java.util.Locale.ROOT)));
    /** #getitem may mine for what it needs. */
    public final Setting<Boolean> getItemMine = bool("getItemMine", true);
    /** Remember what's in each chest, barrel and shulker box opened ({@code #find}). */
    public final Setting<Boolean> chestMemory = bool("chestMemory", true);
    /** #deposit without a container puts each item into a remembered chest that already holds it (else the nearest). */
    public final Setting<Boolean> depositSorted = bool("depositSorted", true);
    /** #getitem / #brew take the finished thing asked for from a remembered chest within {@link #chestRange} if one has it. */
    public final Setting<Boolean> chestFinal = bool("chestFinal", true);
    /** #getitem / #brew take what they make it from (ingredients, materials) from remembered chests instead of getting them. */
    public final Setting<Boolean> chestIngredients = bool("chestIngredients", true);
    /** Taking items also looks in shulker boxes: one carried (placed, opened, picked up) or one in a remembered chest. */
    public final Setting<Boolean> shulkers = bool("shulkers", true);
    /** #sort may move items between the chests and barrels around. */
    public final Setting<Boolean> sortEnabled = bool("sortEnabled", true);
    /** After #sort, put an item frame (from the inventory) on each box front showing what it holds most of. */
    public final Setting<Boolean> sortLabels = bool("sortLabels", true);
    /** #sel fill / clear may build and break. */
    public final Setting<Boolean> selEnabled = bool("selEnabled", true);
    /** While a job runs, tops up {@link #restockItems} from known chests (or shulker boxes) when under half. */
    public final Setting<Boolean> restock = bool("restock", true);
    /** Each thing restocked, on or off (tools: keep one), with how many to keep for the counted ones. */
    public final Setting<Boolean> restockFood = bool("restockFood", true);
    public final Setting<Integer> restockFoodCount = integer("restockFoodCount", 16, 1, 64);
    public final Setting<Boolean> restockPickaxe = bool("restockPickaxe", true);
    public final Setting<Boolean> restockAxe = bool("restockAxe", false);
    public final Setting<Boolean> restockShovel = bool("restockShovel", false);
    public final Setting<Boolean> restockSword = bool("restockSword", false);
    public final Setting<Boolean> restockTorch = bool("restockTorch", true);
    public final Setting<Integer> restockTorchCount = integer("restockTorchCount", 16, 1, 64);
    public final Setting<Boolean> restockBlocks = bool("restockBlocks", true);
    public final Setting<Integer> restockBlocksCount = integer("restockBlocksCount", 64, 1, 256);
    /** Paths that need more blocks than you carry (and won't restock blocks) look for a way round without placing. */
    public final Setting<Boolean> blockBudget = bool("blockBudget", true);
    /** Restocks while following someone (#follow) too. */
    public final Setting<Boolean> restockFollow = bool("restockFollow", false);
    /** Other items to keep, item:amount (the things with their own toggle are left out of this). */
    public final Setting<String> restockItems = register(new Setting<>("restockItems", "",
        s -> s.replace(" ", "").toLowerCase(java.util.Locale.ROOT)));

    private static final java.util.Set<String> RESTOCK_OWN = java.util.Set.of("food", "pickaxe", "axe", "shovel", "sword", "torch", "blocks");

    /** The whole restock list: the toggled things, then the other items. */
    public String restockSpec() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (restockFood.get()) out.add("food:" + restockFoodCount.get());
        if (restockPickaxe.get()) out.add("pickaxe:1");
        if (restockAxe.get()) out.add("axe:1");
        if (restockShovel.get()) out.add("shovel:1");
        if (restockSword.get()) out.add("sword:1");
        if (restockTorch.get()) out.add("torch:" + restockTorchCount.get());
        if (restockBlocks.get()) out.add("blocks:" + restockBlocksCount.get());
        for (String part : restockItems.get().split(",")) {
            int c = part.lastIndexOf(':');
            String name = c < 0 ? part : part.substring(0, c);
            if (part.isBlank() || RESTOCK_OWN.contains(name) || name.equals("minecraft:torch")) continue;
            out.add(part);
        }
        return String.join(",", out);
    }
    public final Setting<Integer> chestRange = integer("chestRange", 128, 8, 2048);
    /** #getitem crafts what it needs several times over (planks, sticks...) all at once: fewer steps. */
    public final Setting<Boolean> getItemBatch = bool("getItemBatch", true);
    /** #getitem prefers blocks that are in the loaded world around (birch logs when there are birches...). */
    public final Setting<Boolean> getItemNearby = bool("getItemNearby", true);
    /** The server hides ores (anti-xray): only ores next to air or water are real, the rest are looked for by digging. */
    public final Setting<Boolean> antiXray = bool("antiXray", false);
    /** Remember every ore seen as chunks load (also after they unload): #mine finds ores at once. Costs a little on every chunk load. */
    public final Setting<Boolean> oreMemory = bool("oreMemory", false);
    /** #mine / #getitem with none found: strip-mine at the ore's best level, or explore for anything else. */
    public final Setting<Boolean> mineSearch = bool("mineSearch", true);
    /** Long tunnels of the strip mine, in blocks. */
    public final Setting<Integer> stripLength = integer("stripLength", 32, 8, 256);
    /** Searching gives up after this many minutes. */
    public final Setting<Integer> searchMinutes = integer("searchMinutes", 15, 1, 240);
    /** Remember every villager's trades when its trading screen opens (by hand too): #trade plans from them. */
    public final Setting<Boolean> tradeMemory = bool("tradeMemory", true);
    /** #trade short of emeralds: sells things for them first (string to a fisherman, sticks to a fletcher...). */
    public final Setting<Boolean> tradeForEmeralds = bool("tradeForEmeralds", true);
    /** #trade needing things to sell: looks in the chests around the villagers that buy them (village chests). */
    public final Setting<Boolean> tradeLootChests = bool("tradeLootChests", false);
    /** #getitem trades for an item when that's the only way, or cheaper than making it. */
    public final Setting<Boolean> getItemTrade = bool("getItemTrade", true);
    /** #getitem kills mobs for what only they drop (leather, bones, string...), or when that's cheaper. */
    public final Setting<Boolean> getItemHunt = bool("getItemHunt", true);
    /** A hunt gives up when it hasn't got enough after this many minutes. */
    public final Setting<Integer> huntMinutes = integer("huntMinutes", 5, 1, 60);
    /** #build may place schematics (break wrong blocks and place the right ones). */
    public final Setting<Boolean> buildEnabled = bool("buildEnabled", true);
    /** #build out of a block (none in the chests either): leave those spots and build the rest (off: stop). */
    public final Setting<Boolean> buildSkipMissing = bool("buildSkipMissing", false);
    /** #build places stairs, slabs, logs, doors... facing the way the schematic has them (and fixes ones that don't). */
    public final Setting<Boolean> buildExactStates = bool("buildExactStates", true);
    /** #iceboat's search: runs kept each round (more: better lines, more time). */
    public final Setting<Integer> iceboatBeam = integer("iceboatBeam", 64, 8, 512);
    /** #iceboat's search: milliseconds per tick it may think (a tick is 50). */
    public final Setting<Integer> iceboatBudgetMs = integer("iceboatBudgetMs", 20, 2, 45);
    /** How far around where it started #breed looks for animals, in blocks. */
    public final Setting<Integer> breedRange = integer("breedRange", 24, 4, 96);
    /** How far #trade looks for villagers (and walks to them), in blocks. */
    public final Setting<Integer> tradeRange = integer("tradeRange", 96, 16, 512);
    /** #trade with what it wants sold out: buys what's left, then waits up to this many minutes for a restock (0: no wait). */
    public final Setting<Integer> tradeRestockMinutes = integer("tradeRestockMinutes", 10, 0, 60);
    /** #trade never sells these for emeralds (comma list; #trade sell <item> still does when asked by name). */
    public final Setting<String> tradeNeverSell = register(new Setting<>("tradeNeverSell",
        "minecraft:diamond,minecraft:netherite_ingot,minecraft:ancient_debris,minecraft:emerald_block,minecraft:enchanted_book,minecraft:totem_of_undying",
        s -> s.replace(" ", "").toLowerCase(java.util.Locale.ROOT)));
    /** When set: the only things #trade sells for emeralds (comma list). */
    public final Setting<String> tradeSellOnly = register(new Setting<>("tradeSellOnly", "",
        s -> s.replace(" ", "").toLowerCase(java.util.Locale.ROOT)));
    /** #trade skips buying trades that cost more emeralds than this. */
    public final Setting<Integer> tradeMaxEmeralds = integer("tradeMaxEmeralds", 64, 1, 64);
    /** #trade looks at villagers it has not seen yet when the known ones cannot do it. */
    public final Setting<Boolean> tradeScanUnknown = bool("tradeScanUnknown", true);
    /** #trade keeps a villager screen open for the next trade with the same villager (off: closes after each). */
    public final Setting<Boolean> tradeStayOpen = bool("tradeStayOpen", true);
    /** Lets the players in chatControlPlayers run commands by saying them in chat (or whispering). */
    public final Setting<Boolean> chatControl = bool("chatControl", false);
    /** Who may (names, comma separated). */
    public final Setting<String> chatControlPlayers = register(new Setting<>("chatControlPlayers", "",
        s -> s.replace(" ", "")));
    /** #getitem may smelt for what it needs. */
    public final Setting<Boolean> getItemSmelt = bool("getItemSmelt", true);
    /** #getitem may upgrade at a smithing table (netherite gear: template + diamond item + netherite ingot). */
    public final Setting<Boolean> getItemSmith = bool("getItemSmith", true);
    public final Setting<Boolean> getItemBuckets = bool("getItemBuckets", true);
    public final Setting<Integer> bucketRange = integer("bucketRange", 48, 8, 128);
    /** #smelt waits at the furnace until it's done; off: loads it, leaves, and #smelt collect goes back. */
    public final Setting<Boolean> smeltWait = bool("smeltWait", true);
    /** #smelt uses a blast furnace (ores) or smoker (food), twice as fast, when there's one near or in the inventory. */
    public final Setting<Boolean> smeltFastFurnaces = bool("smeltFastFurnaces", true);
    /** Paths never break blocks that need a tool it doesn't have (stone without a pickaxe): too slow, drops nothing. */
    public final Setting<Boolean> breakNeedsTool = bool("breakNeedsTool", true);
    /** #goto farther than {@link #elytraMinDistance} flies there with an elytra and rockets when it has them. */
    public final Setting<Boolean> elytraGoto = bool("elytraGoto", true);
    public final Setting<Integer> elytraMinDistance = integer("elytraMinDistance", 300, 50, 100000);
    /** Blocks over the highest ground ahead to fly at. */
    public final Setting<Integer> elytraHeight = integer("elytraHeight", 24, 6, 120);
    /** Put the chestplate back on after landing. */
    public final Setting<Boolean> elytraSwapBack = bool("elytraSwapBack", true);
    /** A far #goto in the overworld goes through the nether when that's quicker (known portals, or obsidian and flint and steel to build them). */
    public final Setting<Boolean> netherGoto = bool("netherGoto", true);
    /** How far a #goto has to be before the nether is considered, in blocks. */
    public final Setting<Integer> netherMinDistance = integer("netherMinDistance", 1000, 200, 1000000);
    /** #portal and nether trips fetch missing obsidian and flint and steel with #getitem (off: only with them on hand). */
    public final Setting<Boolean> portalGather = bool("portalGather", false);
    /** Remember every nether portal seen, for #portal and nether trips. */
    public final Setting<Boolean> portalMemory = bool("portalMemory", true);
    /** #stronghold: how far to walk square to the first eye throw before the second (longer: more exact). */
    public final Setting<Integer> strongholdBaseline = integer("strongholdBaseline", 120, 30, 1000);
    /** #stronghold picks thrown eyes of ender back up when they drop. */
    public final Setting<Boolean> strongholdPickupEyes = bool("strongholdPickupEyes", true);
    /** #enchant / #repair / #combine place an enchanting table or anvil they carry when none is near. */
    public final Setting<Boolean> enchantPlace = bool("enchantPlace", true);
    /** #brew gets missing ingredients, glass bottles or a brewing stand with #getitem first. */
    public final Setting<Boolean> brewGather = bool("brewGather", true);
    /** #brew places a brewing stand (crafting one if need be) when none is near. */
    public final Setting<Boolean> brewPlaceStand = bool("brewPlaceStand", true);
    /** #smelt places a furnace (crafting one if need be) when none is near. */
    public final Setting<Boolean> smeltPlaceFurnace = bool("smeltPlaceFurnace", true);
    /** Never use a tool's last few points of durability. */
    public final Setting<Boolean> saveTools = bool("saveTools", true);
    /** Mining swaps to a fresh tool of the same tier over a nearly broken one, and #getitem plans a spare pickaxe. */
    public final Setting<Boolean> mineSpareTool = bool("mineSpareTool", true);
    public final Setting<Integer> evalHorizon = integer("evalHorizon", 32, 1, 4096);
    public final Setting<Double> heuristicWeight = decimal("heuristicWeight", 1.2, 1.0, 10.0);
    public final Setting<Boolean> renderPath = bool("renderPath", true);
    /** Ghost view: draw where the player really went on the last trip (red where off the plan) and the plans it dropped. */
    public final Setting<Boolean> renderGhost = bool("renderGhost", false);
    /** How many ticks of the ghost trail to keep. */
    public final Setting<Integer> ghostTicks = integer("ghostTicks", 1200, 20, 12000);
    /** The HUD status as a Material card (off: the plain text panel). */
    public final Setting<Boolean> hudCard = bool("hudCard", true);
    /** Corner radius of the HUD card and minimap, in pixels. */
    public final Setting<Integer> hudCorner = integer("hudCorner", 6, 0, 16);
    /** Show the minimap (top right). */
    public final Setting<Boolean> minimap = bool("minimap", true);
    /** Minimap width and height, in pixels. */
    public final Setting<Integer> minimapSize = integer("minimapSize", 96, 48, 192);
    /** Minimap zoom: 1 shows about 32 blocks each way, 2 half of that, 0.5 twice as much. */
    public final Setting<Double> minimapZoom = decimal("minimapZoom", 1.0, 0.5, 4.0);
    /** The minimap turns with you (off: north is up). */
    public final Setting<Boolean> minimapRotate = bool("minimapRotate", false);
    /** Draw the path, goal and waypoints on the minimap. */
    public final Setting<Boolean> minimapPath = bool("minimapPath", true);
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
