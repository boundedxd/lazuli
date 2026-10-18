package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.LazuliConfig;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.option.Options;
import main.walksy.lib.api.config.value.ListEntries;

/** Stopping, keys, and who else may give commands. */
public final class ControlsTab {
    private ControlsTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Controls",
            Ui.group("Stopping",
                Ui.bool(s.cancelOnUserInput, "Stop pathing when you press a movement key."),
                Ui.button("Stop pathing", () -> LazuliConfig.stopAction.run(), "Stop whatever's running.")),
            Ui.group("Keys",
                Options.ofKeybind("Stop pathing key", LazuliConfig.STOP).build(),
                Options.ofKeybind("Open config key", LazuliConfig.OPEN_CONFIG).build(),
                Options.ofKeybind("Freecam key", LazuliConfig.FREECAM).build()),
            Ui.folded("Chat control",
                Ui.bool(s.chatControl, "Let the players listed below run commands by saying them in chat or whispering them."),
                Ui.nameList(s.chatControlPlayers, ListEntries.onlinePlayers(), "Who may run commands from chat (pick online players or type a name).")));
    }
}
