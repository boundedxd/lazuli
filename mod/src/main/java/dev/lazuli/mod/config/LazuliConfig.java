package dev.lazuli.mod.config;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.tabs.BuildingTab;
import dev.lazuli.mod.config.tabs.ControlsTab;
import dev.lazuli.mod.config.tabs.InventoryTab;
import dev.lazuli.mod.config.tabs.MovementTab;
import dev.lazuli.mod.config.tabs.PlannerTab;
import dev.lazuli.mod.config.tabs.ProcessesTab;
import dev.lazuli.mod.config.tabs.SurvivalTab;
import dev.lazuli.mod.config.tabs.VisualsTab;
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

    public static final WLColor DEFAULT_PATH = new WLColor(80, 160, 255, 255);
    public static final WLColor DEFAULT_PREDICTION = new WLColor(255, 200, 40, 255);
    public static final WLColor DEFAULT_GOAL = new WLColor(60, 255, 120, 255);

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
    /** Set by the client: the saved routines, and runs one (name, times; 0 forever). */
    public static volatile dev.lazuli.core.task.Routines routines;
    public static java.util.function.BiConsumer<String, Integer> runRoutine = (n, t) -> {};
    /** How many times each routine's Run button runs it (this session). */
    private static final java.util.Map<String, Integer> routineTimes = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Config define() {
        Settings s = SETTINGS;
        Config config = Config.builder()
            .path(PathUtils.ofConfigDir("lazuli-settings"))
            .category(MovementTab.build(s))
            .category(BuildingTab.build(s))
            .category(InventoryTab.build(s))
            .category(SurvivalTab.build(s))
            .category(ProcessesTab.build(s))
            .category(ControlsTab.build(s))
            .category(PlannerTab.build(s))
            .category(Category.builder("Routines")
                .group(OptionGroup.builder("Routines")
                    .option(Options.ofPageList("Routines", LazuliConfig::routineNames, java.util.List.of(), LazuliConfig::setRoutineNames)
                        .newEntryName("routine")
                        .noSpaces()
                        .onRemoved(n -> {
                            if (routines != null) routines.remove(n);
                        })
                        .onRenamed((from, to) -> {
                            if (routines != null) routines.rename(from, to);
                        })
                        .options(LazuliConfig::routinePage)
                        .description(OptionDescription.ofText(() -> "Saved lists of commands run one after another (also #routine). A job with no end (farm, tunnel, mine with no count) moves on when the inventory is full.")).build())
                    .build())
                .build())
            .category(VisualsTab.build(s))
            .build();
        // a setting nobody gave a widget to can't be changed from the screen: say so in the log
        java.util.List<String> missing = Ui.missing(s);
        if (!missing.isEmpty()) org.slf4j.LoggerFactory.getLogger("lazuli").warn("Settings missing from the config screen: {}", missing);
        return config;
    }

    private static java.util.List<String> routineNames() {
        return routines == null ? java.util.List.of() : java.util.List.copyOf(routines.list().keySet());
    }

    /** The list edited: new names become empty routines, missing ones are deleted. */
    private static void setRoutineNames(java.util.List<String> names) {
        if (routines == null) return;
        java.util.Set<String> want = new java.util.HashSet<>();
        for (String n : names) want.add(n.toLowerCase(java.util.Locale.ROOT));
        for (String n : routines.list().keySet()) if (!want.contains(n)) routines.remove(n);
        for (String n : names) if (routines.get(n) == null) routines.set(n, java.util.List.of());
    }

    /** One routine's page: the step list and editor, how many runs, Run, Stop (see {@link RoutineEditor}). */
    private static java.util.List<Option<?>> routinePage(String name) {
        return RoutineEditor.page(name);
    }

    public static void save() {
        new LazuliConfig().getConfig().save();
    }
}
