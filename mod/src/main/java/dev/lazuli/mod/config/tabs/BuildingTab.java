package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.value.ListEntries;

/** Placing and breaking blocks on the way: bridging, pillaring, digging through. */
public final class BuildingTab {
    private BuildingTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Building",
            Ui.group("Placing",
                Ui.bool(s.allowPlace, "Bridge gaps and pillar up with throwaway blocks from the hotbar."),
                Ui.idList(s.throwawayBlocks, ListEntries.blocks(), true, "Blocks it may place for bridging and pillaring (also the blocks thrown out as junk)."),
                Ui.bool(s.keepCollected, "Never bridge or pillar with blocks it's collecting (what #mine mines, what #getitem needs); only with spares beyond that."),
                Ui.bool(s.blockBudget, "With building blocks not restocked: a path needing more blocks than you carry goes the way round without placing instead (when there is one).")),
            Ui.group("Breaking",
                Ui.bool(s.allowBreak, "Mine through blocks in the way, with the best tool in the hotbar."),
                Ui.bool(s.breakNeedsTool, "Never dig through blocks that need a tool it doesn't have (stone without a pickaxe) on the way somewhere."),
                Ui.bool(s.mineSneak, "Crouch while standing still to break blocks in the way (off: stand normally).")),
            Ui.folded("Advanced: bridging style",
                Ui.bool(s.eagleBridge, "Eagle bridge: walk back without sneaking and only crouch at the edge to place (faster). Off: ninja bridge, sneaking the whole way.")));
    }
}
