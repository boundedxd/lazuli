package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.LazuliConfig;
import dev.lazuli.mod.config.RoutineEditor;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.option.OptionDescription;
import main.walksy.lib.api.config.option.Options;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Saved routines (each opens the step editor, {@link RoutineEditor}) and the limits every routine runs under. */
public final class RoutinesTab {
    private RoutinesTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Routines",
            Ui.group("Routines",
                Options.ofPageList("Routines", RoutinesTab::names, List.of(), RoutinesTab::setNames)
                    .newEntryName("routine")
                    .noSpaces()
                    .onRemoved(n -> {
                        if (LazuliConfig.routines != null) LazuliConfig.routines.remove(n);
                    })
                    .onRenamed((from, to) -> {
                        if (LazuliConfig.routines != null) LazuliConfig.routines.rename(from, to);
                    })
                    .options(RoutineEditor::page)
                    .description(OptionDescription.ofText(() -> "Saved lists of steps run one after another (also #routine). Open one to add and change steps: "
                        + "retries, timeouts, conditions, settings for a single step. A job with no end (farm, tunnel, mine with no count) moves on when the inventory is full.")).build()),
            Ui.folded("Limits",
                Ui.num(s.routineStepTimeoutMinutes, 0, 1440, 5, "A routine step that isn't endless and runs longer than this is stopped as failed. 0: no limit. (A step's own timeout wins.)"),
                Ui.num(s.routineIdleWaitSeconds, 0, 3600, 5, "A forever routine whose whole round did nothing (an empty chest...) waits this long and tries again. 0: stops instead."),
                Ui.num(s.routineIdleRounds, 1, 10000, 1, "A forever routine stops after this many idle rounds in a row."),
                Ui.num(s.routineLoopLimit, 1, 100000000, 1000, "A while / repeat loop in a routine stops the routine after going round this many times, so a bad loop can't hang it. Stop always works at once.")));
    }

    private static List<String> names() {
        var r = LazuliConfig.routines;
        return r == null ? List.of() : List.copyOf(r.list().keySet());
    }

    /** The list edited: new names become empty routines, missing ones are deleted. */
    private static void setNames(List<String> names) {
        var r = LazuliConfig.routines;
        if (r == null) return;
        Set<String> want = new HashSet<>();
        for (String n : names) want.add(n.toLowerCase(Locale.ROOT));
        for (String n : r.list().keySet()) if (!want.contains(n)) r.remove(n);
        for (String n : names) if (r.get(n) == null) r.set(n, List.of());
    }
}
