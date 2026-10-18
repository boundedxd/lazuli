package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.LazuliConfig;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.option.OptionDescription;
import main.walksy.lib.api.config.option.Options;

/** What's drawn: the path and goal in the world, the status panel, the minimap. */
public final class VisualsTab {
    private VisualsTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Visuals",
            Ui.group("Path",
                Ui.bool(s.renderPath, "Draw the planned path."),
                Options.ofColor("Path color", () -> LazuliConfig.pathColor, LazuliConfig.DEFAULT_PATH, v -> LazuliConfig.pathColor = v).build(),
                Options.ofBoolean("Show predicted motion", () -> LazuliConfig.renderPrediction, true, v -> LazuliConfig.renderPrediction = v)
                    .description(OptionDescription.ofText(() -> "Draw where the input planner expects to go next.")).build(),
                Options.ofColor("Prediction color", () -> LazuliConfig.predictionColor, LazuliConfig.DEFAULT_PREDICTION, v -> LazuliConfig.predictionColor = v).build(),
                Options.ofBoolean("Show goal", () -> LazuliConfig.renderGoal, true, v -> LazuliConfig.renderGoal = v).build(),
                Options.ofColor("Goal color", () -> LazuliConfig.goalColor, LazuliConfig.DEFAULT_GOAL, v -> LazuliConfig.goalColor = v).build(),
                Options.ofNumber("Line width", () -> LazuliConfig.lineWidth, 2.5f, v -> LazuliConfig.lineWidth = v).range(1.0f, 6.0f, 0.5f).build()),
            Ui.group("World",
                Options.ofBoolean("Show selection", () -> LazuliConfig.renderSelection, true, v -> LazuliConfig.renderSelection = v).build(),
                Ui.bool(s.renderWaypoints, "Draw waypoints (#wp) in the world."),
                Ui.bool(s.renderTargets, "Draw saved targets (#pick, #target: chests, villagers, stations) with their @names in the world.")),
            Ui.group("Status panel",
                Options.ofBoolean("Status panel", () -> LazuliConfig.showHud, true, v -> LazuliConfig.showHud = v)
                    .description(OptionDescription.ofText(() -> "What Lazuli is doing (job, step, trip) in the top left.")).build(),
                Options.ofBoolean("Progress bars", () -> LazuliConfig.hudBars, true, v -> LazuliConfig.hudBars = v)
                    .description(OptionDescription.ofText(() -> "Bars for the job (blocks mined, #getitem steps) and the trip.")).build(),
                Options.ofBoolean("Speed and ETA", () -> LazuliConfig.hudStats, true, v -> LazuliConfig.hudStats = v)
                    .description(OptionDescription.ofText(() -> "Speed, time left and path nodes under the trip.")).build(),
                Ui.bool(s.hudCard, "Draw the status as a Material card with chips and a progress bar (off: plain text)."),
                Ui.num(s.hudCorner, 0, 16, 1, "How round the status card and minimap corners are, in pixels.")),
            Ui.group("Minimap",
                Ui.bool(s.minimap, "A minimap in the top right."),
                Ui.num(s.minimapSize, 48, 192, 8, "Width and height in pixels."),
                Ui.dec(s.minimapZoom, 0.5, 4.0, 0.25, "1 shows about 32 blocks each way; higher is closer."),
                Ui.bool(s.minimapRotate, "Turn the map with you (off: north is up)."),
                Ui.bool(s.minimapPath, "Draw the path, goal and waypoints on the map.")),
            Ui.folded("Advanced: ghost view",
                Ui.bool(s.renderGhost, "Ghost view: where you really went on the last trip (red: off the planned path), the final plan and the dropped ones. Stays after the trip."),
                Ui.num(s.ghostTicks, 20, 12000, 20, "Ticks of ghost trail kept (20 a second).")));
    }
}
