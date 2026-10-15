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
    public static boolean showHud = true;
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
            .category(Category.builder("Pathing")
                .group(OptionGroup.builder("Movement")
                    .option(bool(s.allowSprint, "Sprint when food allows."))
                    .option(bool(s.allowParkour, "Jump gaps, any direction, up to 7 blocks."))
                    .option(bool(s.allowBreak, "Mine through blocks in the way, with the best tool in the hotbar."))
                    .option(bool(s.allowPlace, "Bridge gaps and pillar up with throwaway blocks from the hotbar."))
                    .option(bool(s.eagleBridge, "Eagle bridge: walk back without sneaking and only crouch at the edge to place (faster). Off: ninja bridge, sneaking the whole way."))
                    .option(Options.ofString("Throwaway blocks", s.throwawayBlocks::get, s.throwawayBlocks.defaultValue(),
                        v -> s.throwawayBlocks.parse(v))
                        .description(OptionDescription.ofText(() -> "Comma-separated block ids it may place.")).build())
                    .option(Options.ofNumber("Max fall height", s.maxFallHeight::get, s.maxFallHeight.defaultValue(), s.maxFallHeight::set)
                        .range(0, 20, 1)
                        .description(OptionDescription.ofText(() -> "Highest drop it takes without water, in blocks.")).build())
                    .option(Options.ofNumber("Rotation speed", s.rotationSpeed::get, s.rotationSpeed.defaultValue(), s.rotationSpeed::set)
                        .range(5.0, 180.0, 5.0)
                        .description(OptionDescription.ofText(() -> "Most the view turns per tick, in degrees.")).build())
                    .option(bool(s.allowSneakStop, "Crouch to stop dead before a long or curved jump. Off: slows down walking instead."))
                    .option(bool(s.allowFlicks, "Snap the view sideways to slow down or steer mid-air. Off: keeps looking where it goes and steers with A, D and S."))
                    .option(bool(s.freeLook, "Turn only for the server: your camera stays where you point it."))
                    .build())
                .group(OptionGroup.builder("Behaviour")
                    .option(bool(s.cancelOnUserInput, "Stop pathing when you press a movement key."))
                    .option(bool(s.minePickup, "#mine walks over to pick up what each broken block drops."))
                    .option(bool(s.autoEat, "Eat from the hotbar when hungry while Lazuli is busy."))
                    .option(Options.ofNumber("Eat at hunger", s.autoEatHunger::get, s.autoEatHunger.defaultValue(), s.autoEatHunger::set)
                        .range(1, 19, 1)
                        .description(OptionDescription.ofText(() -> "Hunger (out of 20) at or below which it eats.")).build())
                    .option(bool(s.autoArmor, "Put on better armor from the inventory while busy."))
                    .option(bool(s.allowInventory, "Move the best tool for the job from the inventory into the hotbar."))
                    .option(bool(s.saveTools, "Never use a tool's last few points of durability."))
                    .option(Options.ofNumber("Follow distance", s.followRadius::get, s.followRadius.defaultValue(), s.followRadius::set)
                        .range(1, 32, 1)
                        .description(OptionDescription.ofText(() -> "How close #follow stays, in blocks.")).build())
                    .option(Options.ofNumber("Farm range", s.farmRange::get, s.farmRange.defaultValue(), s.farmRange::set)
                        .range(4, 128, 4)
                        .description(OptionDescription.ofText(() -> "How far around its start #farm works.")).build())
                    .option(Options.ofButton("Stop pathing", () -> stopAction.run()).build())
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
                    .option(Options.ofColor("Goal color", () -> goalColor, DEFAULT_GOAL, v -> goalColor = v).build())
                    .option(Options.ofNumber("Line width", () -> lineWidth, 2.5f, v -> lineWidth = v).range(1.0f, 6.0f, 0.5f).build())
                    .build())
                .group(OptionGroup.builder("HUD")
                    .option(Options.ofBoolean("Status line", () -> showHud, true, v -> showHud = v)
                        .description(OptionDescription.ofText(() -> "State, distance and remaining path while pathing.")).build())
                    .build())
                .build())
            .build();
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
