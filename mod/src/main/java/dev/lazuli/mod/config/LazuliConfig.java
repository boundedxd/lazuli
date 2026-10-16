package dev.lazuli.mod.config;

import dev.lazuli.core.settings.Setting;
import dev.lazuli.core.settings.Settings;
import main.walksy.lib.api.WalksyLibApi;
import main.walksy.lib.api.config.Config;
import main.walksy.lib.api.config.PathUtils;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.option.Option;
import main.walksy.lib.api.config.option.OptionDescription;
import main.walksy.lib.api.config.option.OptionGroup;
import main.walksy.lib.api.config.option.Options;
import main.walksy.lib.api.config.value.ListEntries;
import main.walksy.lib.api.config.value.WLColor;
import main.walksy.lib.api.config.value.WLKeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

/**
 * The WalksyLib config screen and file. Pathing options are bound straight to the core {@link Settings} (also
 * reachable with {@code #set}); visual options live here.
 */
public final class LazuliConfig implements WalksyLibApi {
    public static final String ID = "lazuli";
    public static final Settings SETTINGS = new Settings();

    public static final WLKeyMapping OPEN_CONFIG = WLKeyMapping.create("Open config", GLFW.GLFW_KEY_RIGHT_SHIFT, "Lazuli");
    public static final WLKeyMapping STOP = WLKeyMapping.create("Stop pathing", InputConstants.UNKNOWN.getValue(), "Lazuli");
    public static final WLKeyMapping FREECAM = WLKeyMapping.create("Freecam", GLFW.GLFW_KEY_F6, "Lazuli");

    private static final WLColor DEFAULT_PATH = new WLColor(80, 160, 255, 255);
    private static final WLColor DEFAULT_PREDICTION = new WLColor(255, 200, 40, 255);
    private static final WLColor DEFAULT_GOAL = new WLColor(60, 255, 120, 255);

    public static boolean renderPrediction = true;
    public static boolean renderGoal = true;
    public static boolean renderSelection = true;
    public static boolean showHud = true;
    public static boolean hudBars = true;
    public static boolean hudStats = true;
    public static float lineWidth = 2.5f;
    public static WLColor pathColor = DEFAULT_PATH.copy();
    public static WLColor predictionColor = DEFAULT_PREDICTION.copy();
    public static WLColor goalColor = DEFAULT_GOAL.copy();

    /** Set by the client: stops the current path. */
    public static Runnable stopAction = () -> {};

    @Override
    public Config define() {
        Settings s = SETTINGS;
        return Config.builder()
            .path(PathUtils.ofConfigDir("lazuli-settings"))
            .category(Category.builder("Movement")
                .group(OptionGroup.builder("Moving")
                    .option(bool(s.allowSprint, "Sprint when food allows."))
                    .option(bool(s.sprintSwim, "Swim mode (sprint-swimming) across open water, just under the surface, coming up for air before it runs low."))
                    .option(bool(s.useBoats, "Cross long stretches of water by boat when you have one."))
                    .option(Options.ofNumber("Boat at water length", s.boatMinWater::get, s.boatMinWater.defaultValue(), s.boatMinWater::set)
                        .range(3, 128, 1).description(OptionDescription.ofText(() -> "Water blocks in a row on the path before it takes the boat.")).build())
                    .option(bool(s.boatPickup, "Break the boat after getting out, to take it along."))
                    .option(bool(s.waterClutch, "Water bucket clutch: place water just before landing from a fall that would hurt."))
                    .option(Options.ofNumber("Clutch fall height", s.clutchFallHeight::get, s.clutchFallHeight.defaultValue(), s.clutchFallHeight::set)
                        .range(0, 200, 1).description(OptionDescription.ofText(() -> "With a water bucket, paths may drop this far onto ground and clutch (0: never on purpose).")).build())
                    .option(bool(s.clutchPickup, "Scoop the clutch water back up after landing."))
                    .option(bool(s.surfaceSwim, "Otherwise swim at the surface with your head out of the water (never sprint-swim under)."))
                    .option(bool(s.allowParkour, "Jump gaps, any direction, up to 7 blocks."))
                    .option(Options.ofNumber("Max fall height", s.maxFallHeight::get, s.maxFallHeight.defaultValue(), s.maxFallHeight::set)
                        .range(0, 20, 1)
                        .description(OptionDescription.ofText(() -> "Highest drop it takes without water, in blocks.")).build())
                    .option(bool(s.allowSneakStop, "Crouch to stop dead before a long or curved jump. Off: slows down walking instead."))
                    .option(bool(s.allowFlicks, "Snap the view sideways to slow down or steer mid-air. Off: keeps looking where it goes and steers with A, D and S."))
                    .build())
                .group(OptionGroup.builder("Elytra")
                    .option(bool(s.elytraGoto, "#goto far away flies there with an elytra and firework rockets when it has them (#fly always does)."))
                    .option(Options.ofNumber("Fly when farther than", s.elytraMinDistance::get, s.elytraMinDistance.defaultValue(), s.elytraMinDistance::set)
                        .range(50, 5000, 50).description(OptionDescription.ofText(() -> "Blocks: #goto closer than this walks.")).build())
                    .option(Options.ofNumber("Flying height", s.elytraHeight::get, s.elytraHeight.defaultValue(), s.elytraHeight::set)
                        .range(6, 120, 1).description(OptionDescription.ofText(() -> "Blocks over the highest ground ahead to fly at.")).build())
                    .option(bool(s.elytraSwapBack, "Put the chestplate back on after landing."))
                    .build())
                .group(OptionGroup.builder("Looking")
                    .option(Options.ofNumber("Rotation speed", s.rotationSpeed::get, s.rotationSpeed.defaultValue(), s.rotationSpeed::set)
                        .range(5.0, 180.0, 5.0)
                        .description(OptionDescription.ofText(() -> "Most the view turns per tick, in degrees.")).build())
                    .option(bool(s.freeLook, "Turn only for the server: your camera stays where you point it."))
                    .build())
                .build())
            .category(Category.builder("Building")
                .group(OptionGroup.builder("Placing")
                    .option(bool(s.allowPlace, "Bridge gaps and pillar up with throwaway blocks from the hotbar."))
                    .option(bool(s.eagleBridge, "Eagle bridge: walk back without sneaking and only crouch at the edge to place (faster). Off: ninja bridge, sneaking the whole way."))
                    .option(idList("Throwaway blocks", s.throwawayBlocks, ListEntries.blocks(), "Blocks it may place for bridging and pillaring."))
                    .option(bool(s.keepCollected, "Never bridge or pillar with blocks it's collecting (what #mine mines, what #getitem needs); only with spares beyond that."))
                    .build())
                .group(OptionGroup.builder("Mining")
                    .option(bool(s.breakNeedsTool, "Never dig through blocks that need a tool it doesn't have (stone without a pickaxe) on the way somewhere."))
                    .option(bool(s.allowBreak, "Mine through blocks in the way, with the best tool in the hotbar."))
                    .option(bool(s.minePickup, "#mine walks over to pick up what each broken block drops."))
                    .option(bool(s.mineSearch, "None found: strip-mine at the ore's best Y level, or explore for anything else (logs, sand...)."))
                    .option(bool(s.antiXray, "The server hides ores (anti-xray): only trust ores you can see, find the rest by strip mining."))
                    .option(Options.ofNumber("Strip mine length", s.stripLength::get, s.stripLength.defaultValue(), s.stripLength::set)
                        .range(8, 256, 8)
                        .description(OptionDescription.ofText(() -> "How long each tunnel of the strip mine is (they're 4 apart).")).build())
                    .option(Options.ofNumber("Search minutes", s.searchMinutes::get, s.searchMinutes.defaultValue(), s.searchMinutes::set)
                        .range(1, 240, 1)
                        .description(OptionDescription.ofText(() -> "Searching (strip mining, exploring) gives up after this long.")).build())
                    .build())
                .build())
            .category(Category.builder("Inventory")
                .group(OptionGroup.builder("Tools and armor")
                    .option(bool(s.allowInventory, "Move the best tool for the job from the inventory into the hotbar."))
                    .option(bool(s.saveTools, "Never use a tool's last few points of durability."))
                    .option(bool(s.autoArmor, "Put on better armor from the inventory while busy."))
                    .build())
                .group(OptionGroup.builder("Crafting")
                    .option(bool(s.craftIngredients, "#craft also crafts the ingredients it lacks first (logs into planks, planks into sticks...)."))
                    .option(bool(s.craftPlaceTable, "#craft places a crafting table (crafting one if need be) when a recipe needs one and none is near."))
                    .option(bool(s.pickupStations, "Pick up a crafting table or furnace it placed itself once #craft / #smelt is done."))
                    .option(Options.ofNumber("Station range", s.stationRange::get, s.stationRange.defaultValue(), s.stationRange::set)
                        .range(4, 64, 1)
                        .description(OptionDescription.ofText(() -> "How far (blocks) #craft and #smelt look for a crafting table or furnace.")).build())
                    .build())
                .group(OptionGroup.builder("Smelting")
                    .option(idList("Fuel", s.smeltFuels, ListEntries.items(), "What #smelt may burn, best first (drag to reorder). It never burns what it's cooking."))
                    .option(bool(s.smeltWait, "#smelt waits at the furnace and takes everything out. Off: loads it, leaves, says when it's done; #smelt collect goes back."))
                    .option(bool(s.smeltFastFurnaces, "#smelt uses a blast furnace (ores) or smoker (food), twice as fast, when there's one near or in the inventory."))
                    .option(bool(s.smeltPlaceFurnace, "#smelt places a furnace (crafting one from cobblestone if need be) when none is near."))
                    .build())
                .group(OptionGroup.builder("Brewing and enchanting")
                    .option(bool(s.brewGather, "#brew gets missing ingredients, glass bottles or a brewing stand with #getitem first."))
                    .option(bool(s.brewPlaceStand, "#brew places a brewing stand (crafting one if need be) when none is near."))
                    .option(bool(s.enchantPlace, "#enchant / #repair / #combine place an enchanting table or anvil they carry when none is near."))
                    .build())
                .group(OptionGroup.builder("Clicking")
                    .option(bool(s.openInventory, "Open the inventory screen and stand still for inventory clicks (tool and armor swaps, #drop, 2x2 crafting), like a player: anticheats flag clicks with no screen open or while moving (InvMove). Off: click straight away, faster."))
                    .option(Options.ofNumber("Clicks per tick", s.clicksPerTick::get, s.clicksPerTick.defaultValue(), s.clicksPerTick::set)
                        .range(1, 20, 1)
                        .description(OptionDescription.ofText(() -> "Inventory clicks (moving, dropping items) per tick: more is faster, fewer looks less like a bot.")).build())
                    .build())
                .build())
            .category(Category.builder("Survival")
                .group(OptionGroup.builder("Food")
                    .option(bool(s.autoEat, "Eat from the hotbar when hungry while Lazuli is busy."))
                    .option(Options.ofNumber("Eat at hunger", s.autoEatHunger::get, s.autoEatHunger.defaultValue(), s.autoEatHunger::set)
                        .range(1, 19, 1)
                        .description(OptionDescription.ofText(() -> "Hunger (out of 20) at or below which it eats.")).build())
                    .build())
                .group(OptionGroup.builder("Restock")
                    .option(bool(s.restock, "While busy, refill what's running low from chests you've opened (or shulker boxes); the job waits."))
                    .option(Options.ofString("Restock items", s.restockItems::get, s.restockItems.defaultValue(), v -> {
                        try {
                            s.restockItems.parse(v);
                        } catch (IllegalArgumentException ignored) {
                            // keep the previous list
                        }
                    }).description(OptionDescription.ofText(() -> "item:amount, comma separated. Groups: food, pickaxe, axe, shovel, sword, blocks. Refills when under half.")).build())
                    .build())
                .group(OptionGroup.builder("Danger")
                    .option(bool(s.dodgeCreepers, "Run from a creeper that starts hissing nearby; the job waits."))
                    .option(bool(s.defend, "Fight monsters that come close with the best sword or axe; the job waits."))
                    .option(bool(s.lowHpRetreat, "At low health get away from monsters, eat and heal; the job waits."))
                    .option(Options.ofNumber("Retreat at health", s.retreatHealth::get, s.retreatHealth.defaultValue(), s.retreatHealth::set)
                        .range(1, 19, 1)
                        .description(OptionDescription.ofText(() -> "Health (out of 20, 2 per heart) at or below which it retreats.")).build())
                    .option(bool(s.useBow, "In a fight, shoot skeletons and creepers with a bow while they're still a way off."))
                    .option(bool(s.useShield, "In a fight, hold the shield up between swings and against arrows."))
                    .option(bool(s.fightOrFlee, "Run instead of fighting when there are too many monsters for your health, armor and weapon."))
                    .option(bool(s.autoTotem, "Keep a totem of undying in the offhand when you have one."))
                    .option(bool(s.autoShield, "Keep a shield in the offhand when there's no totem for it."))
                    .option(bool(s.avoidMobs, "Paths bend around hostile mobs instead of walking right past them."))
                    .option(Options.ofNumber("Mob avoid radius", s.mobAvoidRadius::get, s.mobAvoidRadius.defaultValue(), s.mobAvoidRadius::set)
                        .range(2, 16, 1)
                        .description(OptionDescription.ofText(() -> "How far from a mob paths start to keep away (creepers: half again).")).build())
                    .option(Options.ofNumber("Mob avoid cost", s.mobAvoidCost::get, s.mobAvoidCost.defaultValue(), s.mobAvoidCost::set)
                        .range(1, 400, 1)
                        .description(OptionDescription.ofText(() -> "How much detour (ticks) is worth it to stay clear of a mob.")).build())
                    .option(bool(s.avoidLava, "Paths keep a block away from lava, fire and cactus when there's room."))
                    .build())
                .group(OptionGroup.builder("Light")
                    .option(bool(s.autoTorch, "Place torches from the hotbar where it's dark underground."))
                    .option(Options.ofNumber("Torch below light", s.torchLight::get, s.torchLight.defaultValue(), s.torchLight::set)
                        .range(1, 14, 1)
                        .description(OptionDescription.ofText(() -> "Block light under which a torch goes down (higher: more torches).")).build())
                    .build())
                .group(OptionGroup.builder("Sleep")
                    .option(bool(s.sleepPlaceBed, "#sleep places a bed from the hotbar when none is near."))
                    .option(bool(s.sleepPickupBed, "#sleep takes the bed it placed along in the morning."))
                    .build())
                .group(OptionGroup.builder("Death")
                    .option(bool(s.deathWaypoint, "Save a 'death' waypoint where you die; #back goes there."))
                    .build())
                .build())
            .category(Category.builder("Processes")
                .group(OptionGroup.builder("Get item")
                    .option(bool(s.getItemMine, "#getitem may mine for what it needs (logs, stone, ores...)."))
                    .option(bool(s.getItemSmelt, "#getitem may smelt for what it needs (iron ingots, glass...)."))
                    .option(bool(s.sortEnabled, "#sort may move items between the chests and barrels around."))
                    .option(bool(s.selEnabled, "#sel fill and #sel clear may place and break the blocks in the selected box."))
                    .option(bool(s.chestFinal, "#getitem and #brew take the finished item from a chest you've opened if one has it."))
                    .option(bool(s.shulkers, "Taking items also looks in shulker boxes: one you carry (placed, opened, picked back up) or one in a known chest."))
                    .option(bool(s.chestIngredients, "#getitem and #brew take ingredients and materials from chests you've opened instead of getting them."))
                    .option(bool(s.getItemBatch, "#getitem crafts planks, sticks... all at once instead of over several steps."))
                    .option(bool(s.getItemNearby, "#getitem prefers blocks that are loaded around you (the trees here, the stone here)."))
                    .option(Options.ofNumber("Chest range", s.chestRange::get, s.chestRange.defaultValue(), s.chestRange::set)
                        .range(8, 2048, 8)
                        .description(OptionDescription.ofText(() -> "How far #getitem and #brew walk to a remembered chest, in blocks.")).build())
                    .option(bool(s.netherGoto, "A far #goto goes through the nether when that's quicker (needs known portals, or obsidian and flint and steel)."))
                    .option(Options.ofNumber("Nether min distance", s.netherMinDistance::get, s.netherMinDistance.defaultValue(), s.netherMinDistance::set)
                        .range(200, 20000, 100)
                        .description(OptionDescription.ofText(() -> "How far a #goto has to be before going through the nether.")).build())
                    .option(bool(s.portalGather, "#portal and nether trips get missing obsidian and flint and steel with #getitem first."))
                    .option(bool(s.portalMemory, "Remember every nether portal seen (#portal list)."))
                    .option(bool(s.chestMemory, "Remember what's in every chest, barrel and shulker box opened (#find)."))
                    .option(bool(s.depositSorted, "#deposit puts each item into a remembered chest that already has it (within chest range), else the nearest."))
                    .build())
                .group(OptionGroup.builder("Trading")
                    .option(bool(s.tradeMemory, "Remember every villager's trades when you (or Lazuli) open its trading screen."))
                    .option(bool(s.tradeForEmeralds, "#trade without enough emeralds: sell things for them first (string, sticks, crops...)."))
                    .option(bool(s.tradeLootChests, "#trade needing things to sell: look in the chests near the villagers that buy them."))
                    .option(bool(s.getItemTrade, "#getitem trades with villagers when that's the only way, or cheaper than making it."))
                    .option(bool(s.getItemHunt, "#getitem kills mobs for what they drop (leather, bones, string, gunpowder...)."))
                    .option(Options.ofNumber("Hunt minutes", s.huntMinutes::get, s.huntMinutes.defaultValue(), s.huntMinutes::set)
                        .range(1, 60, 1)
                        .description(OptionDescription.ofText(() -> "How long a hunt looks for mobs before giving up.")).build())
                    .option(Options.ofNumber("Trade range", s.tradeRange::get, s.tradeRange.defaultValue(), s.tradeRange::set)
                        .range(16, 512, 16)
                        .description(OptionDescription.ofText(() -> "How far #trade looks for villagers, in blocks.")).build())
                    .option(Options.ofNumber("Restock wait (min)", s.tradeRestockMinutes::get, s.tradeRestockMinutes.defaultValue(), s.tradeRestockMinutes::set)
                        .range(0, 60, 1)
                        .description(OptionDescription.ofText(() -> "Sold out: buy what's left, then wait this long for the villagers to restock (0: don't wait).")).build())
                    .option(bool(s.chatControl, "Let the players listed below run commands by saying them in chat or whispering them."))
                    .option(Options.ofSelectionList("Chat control players", () -> names(s.chatControlPlayers.get()),
                            names(s.chatControlPlayers.defaultValue()), v -> s.chatControlPlayers.parse(String.join(",", v)), ListEntries.onlinePlayers())
                        .description(OptionDescription.ofText(() -> "Who may run commands from chat (pick online players or type a name).")).build())
                    .build())
                .group(OptionGroup.builder("Follow and farm")
                    .option(Options.ofNumber("Follow distance", s.followRadius::get, s.followRadius.defaultValue(), s.followRadius::set)
                        .range(1, 32, 1)
                        .description(OptionDescription.ofText(() -> "How close #follow stays, in blocks.")).build())
                    .option(Options.ofNumber("Farm range", s.farmRange::get, s.farmRange.defaultValue(), s.farmRange::set)
                        .range(4, 128, 4)
                        .description(OptionDescription.ofText(() -> "How far around its start #farm works.")).build())
                    .option(Options.ofNumber("Breed range", s.breedRange::get, s.breedRange.defaultValue(), s.breedRange::set)
                        .range(4, 96, 4)
                        .description(OptionDescription.ofText(() -> "How far around its start #breed looks for animals.")).build())
                    .build())
                .build())
            .category(Category.builder("Controls")
                .group(OptionGroup.builder("Stopping")
                    .option(bool(s.cancelOnUserInput, "Stop pathing when you press a movement key."))
                    .option(Options.ofButton("Stop pathing", () -> stopAction.run()).build())
                    .build())
                .group(OptionGroup.builder("Keys")
                    .option(Options.ofKeybind("Stop pathing key", STOP).build())
                    .option(Options.ofKeybind("Open config key", OPEN_CONFIG).build())
                    .option(Options.ofKeybind("Freecam key", FREECAM).build())
                    .build())
                .build())
            .category(Category.builder("Planner")
                .group(OptionGroup.builder("Search")
                    .option(Options.ofNumber("Evaluation horizon", s.evalHorizon::get, s.evalHorizon.defaultValue(), s.evalHorizon::set)
                        .range(4, 256, 4)
                        .description(OptionDescription.ofText(() -> "How many path edges ahead are physics-checked before moving.")).build())
                    .option(bool(s.unstick, "Stuck for a few seconds mid-trip: plan it again from scratch."))
                    .option(bool(s.unstickRestart, "When stuck: fresh planner thread and trip state, like #kill, but the job (#getitem...) keeps going (needs the option above)."))
                    .option(Options.ofNumber("Heuristic weight", s.heuristicWeight::get, s.heuristicWeight.defaultValue(), s.heuristicWeight::set)
                        .range(1.0, 5.0, 0.1)
                        .description(OptionDescription.ofText(() -> "Above 1 trades path optimality for faster planning on long trips.")).build())
                    .option(Options.ofString("Command prefix", s.prefix::get, s.prefix.defaultValue(), v -> {
                        try {
                            s.prefix.parse(v);
                        } catch (IllegalArgumentException ignored) {
                            // keep the previous prefix
                        }
                    }).build())
                    .build())
                .build())
            .category(Category.builder("Visuals")
                .group(OptionGroup.builder("World")
                    .option(bool(s.renderPath, "Draw the planned path."))
                    .option(Options.ofColor("Path color", () -> pathColor, DEFAULT_PATH, v -> pathColor = v).build())
                    .option(Options.ofBoolean("Show predicted motion", () -> renderPrediction, true, v -> renderPrediction = v)
                        .description(OptionDescription.ofText(() -> "Draw where the input planner expects to go next.")).build())
                    .option(Options.ofColor("Prediction color", () -> predictionColor, DEFAULT_PREDICTION, v -> predictionColor = v).build())
                    .option(Options.ofBoolean("Show goal", () -> renderGoal, true, v -> renderGoal = v).build())
                    .option(Options.ofBoolean("Show selection", () -> renderSelection, true, v -> renderSelection = v).build())
                    .option(Options.ofColor("Goal color", () -> goalColor, DEFAULT_GOAL, v -> goalColor = v).build())
                    .option(bool(s.renderWaypoints, "Draw waypoints (#wp) in the world."))
                    .option(Options.ofNumber("Line width", () -> lineWidth, 2.5f, v -> lineWidth = v).range(1.0f, 6.0f, 0.5f).build())
                    .build())
                .group(OptionGroup.builder("HUD")
                    .option(Options.ofBoolean("Status panel", () -> showHud, true, v -> showHud = v)
                        .description(OptionDescription.ofText(() -> "What Lazuli is doing (job, step, trip) in the top left.")).build())
                    .option(Options.ofBoolean("Progress bars", () -> hudBars, true, v -> hudBars = v)
                        .description(OptionDescription.ofText(() -> "Bars for the job (blocks mined, #getitem steps) and the trip.")).build())
                    .option(Options.ofBoolean("Speed and ETA", () -> hudStats, true, v -> hudStats = v)
                        .description(OptionDescription.ofText(() -> "Speed, time left and path nodes under the trip.")).build())
                    .build())
                .build())
            .build();
    }

    /** A comma-separated id setting as a picker list (ids get their namespace so the picker knows them). */
    private static Option<java.util.List<String>> idList(String name, Setting<String> setting, ListEntries entries, String description) {
        return Options.ofSelectionList(name, () -> ids(setting.get()), ids(setting.defaultValue()),
                v -> setting.parse(String.join(",", v)), entries)
            .reorderable()
            .description(OptionDescription.ofText(() -> description)).build();
    }

    private static java.util.List<String> names(String csv) {
        return java.util.Arrays.stream(csv.split(",")).filter(x -> !x.isBlank()).toList();
    }

    private static java.util.List<String> ids(String csv) {
        return java.util.Arrays.stream(csv.split(",")).filter(x -> !x.isBlank())
            .map(x -> x.contains(":") ? x : "minecraft:" + x).toList();
    }

    private static Option<Boolean> bool(Setting<Boolean> setting, String description) {
        return Options.ofBoolean(pretty(setting.name()), setting::get, setting.defaultValue(), setting::set)
            .description(OptionDescription.ofText(() -> description)).build();
    }

    /** "allowSprint" -> "Allow sprint". */
    private static String pretty(String camel) {
        String spaced = camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public static void save() {
        new LazuliConfig().getConfig().save();
    }
}
