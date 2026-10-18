package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;

/** What it does with the inventory and chests: tools, clicking, junk, making room, remembered chests. */
public final class InventoryTab {
    private InventoryTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Inventory",
            Ui.group("Tools",
                Ui.bool(s.allowInventory, "Move the best tool for the job from the inventory into the hotbar."),
                Ui.bool(s.saveTools, "Never use a tool's last few points of durability."),
                Ui.bool(s.mineSpareTool, "Mining prefers a fresh tool over a nearly broken one, and #getitem makes a spare pickaxe for a long job.")),
            Ui.group("Clicks",
                Ui.bool(s.openInventory, "Open the inventory screen and stand still for inventory clicks (tool and armor swaps, #drop, 2x2 crafting), like a player: anticheats flag clicks with no screen open or while moving (InvMove). Off: click straight away, faster."),
                Ui.num(s.clicksPerTick, 1, 20, 1, "Inventory clicks (moving, dropping items) per tick: more is faster, fewer looks less like a bot.")),
            Ui.group("Chests",
                Ui.bool(s.chestMemory, "Remember what's in every chest, barrel and shulker box opened (#find)."),
                Ui.num(s.chestRange, 8, 2048, 8, "How far #getitem and #brew walk to a remembered chest, in blocks."),
                Ui.bool(s.depositSorted, "#deposit puts each item into a remembered chest that already has it (within chest range), else the nearest."),
                Ui.bool(s.chestFinal, "#getitem and #brew take the finished item from a chest you've opened if one has it."),
                Ui.bool(s.chestIngredients, "#getitem and #brew take ingredients and materials from chests you've opened instead of getting them."),
                Ui.bool(s.shulkers, "Taking items also looks in shulker boxes: one you carry (placed, opened, picked back up) or one in a known chest.")),
            Ui.group("Junk",
                Ui.bool(s.mineDropJunk, "#mine with a full inventory throws away junk blocks (the throwaway list: deepslate, cobblestone, dirt...) to make room for what it's after."),
                Ui.bool(s.makeRoomDropJunk, "#hunt, #farm, #fish, #tunnel and #sort with a full inventory throw out a stack of junk blocks to make room.")),
            Ui.group("Making room",
                Ui.bool(s.makeRoomDeposit, "Else they put non-essentials (not tools, food, armor or what's being collected) into a remembered chest, else stop.")));
    }
}
