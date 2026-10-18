package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;

/** The pathfinder itself, getting unstuck, and general options. */
public final class PlannerTab {
    private PlannerTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Planner",
            Ui.group("Search",
                Ui.num(s.evalHorizon, 4, 256, 4, "How many path edges ahead are physics-checked before moving."),
                Ui.dec(s.heuristicWeight, 1.0, 5.0, 0.1, "Above 1 trades path optimality for faster planning on long trips.")),
            Ui.group("Getting unstuck",
                Ui.bool(s.unstick, "Stuck for a few seconds mid-trip: plan it again from scratch."),
                Ui.bool(s.unstickRestart, "When stuck: fresh planner thread and trip state, like #kill, but the job (#getitem...) keeps going (needs the option above).")),
            Ui.group("General",
                Ui.text(s.prefix, "What commands start with in chat (no spaces)."),
                Ui.bool(s.perServerMemory, "Keep chests, villager trades, portals and waypoints for each server (or singleplayer world) in its own folder (config/lazuli/servers/). Off: the old shared files.")));
    }
}
