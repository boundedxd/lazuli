package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;

/** How the player gets around: walking, water, flying, the nether, looking, boat races. */
public final class MovementTab {
    private MovementTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Movement",
            Ui.group("Walking",
                Ui.bool(s.allowSprint, "Sprint when food allows."),
                Ui.bool(s.allowParkour, "Jump gaps, any direction, up to 7 blocks."),
                Ui.num(s.maxFallHeight, 0, 20, 1, "Highest drop it takes without water, in blocks."),
                Ui.bool(s.allowSneakStop, "Crouch to stop dead before a long or curved jump. Off: slows down walking instead."),
                Ui.bool(s.allowFlicks, "Snap the view sideways to slow down or steer mid-air. Off: keeps looking where it goes and steers with A, D and S.")),
            Ui.group("Water",
                Ui.bool(s.sprintSwim, "Swim mode (sprint-swimming) across open water, just under the surface, coming up for air before it runs low."),
                Ui.bool(s.surfaceSwim, "Otherwise swim at the surface with your head out of the water (never sprint-swim under)."),
                Ui.bool(s.useBoats, "Cross long stretches of water by boat when you have one."),
                Ui.num(s.boatMinWater, 3, 128, 1, "Water blocks in a row on the path before it takes the boat."),
                Ui.bool(s.boatPickup, "Break the boat after getting out, to take it along.")),
            Ui.group("Falling",
                Ui.bool(s.waterClutch, "Water bucket clutch: place water just before landing from a fall that would hurt."),
                Ui.num(s.clutchFallHeight, 0, 200, 1, "With a water bucket, paths may drop this far onto ground and clutch (0: never on purpose)."),
                Ui.bool(s.clutchPickup, "Scoop the clutch water back up after landing.")),
            Ui.group("Looking",
                Ui.dec(s.rotationSpeed, 5.0, 180.0, 5.0, "Most the view turns per tick, in degrees."),
                Ui.bool(s.freeLook, "Turn only for the server: your camera stays where you point it.")),
            Ui.folded("Elytra",
                Ui.bool(s.elytraGoto, "#goto far away flies there with an elytra and firework rockets when it has them (#fly always does)."),
                Ui.num(s.elytraMinDistance, 50, 5000, 50, "Blocks: #goto closer than this walks."),
                Ui.num(s.elytraHeight, 6, 120, 1, "Blocks over the highest ground ahead to fly at."),
                Ui.bool(s.elytraSwapBack, "Put the chestplate back on after landing.")),
            Ui.folded("Nether travel",
                Ui.bool(s.netherGoto, "A far #goto goes through the nether when that's quicker (needs known portals, or obsidian and flint and steel)."),
                Ui.num(s.netherMinDistance, 200, 20000, 100, "How far a #goto has to be before going through the nether."),
                Ui.bool(s.portalGather, "#portal and nether trips get missing obsidian and flint and steel with #getitem first."),
                Ui.bool(s.portalMemory, "Remember every nether portal seen (#portal list).")),
            Ui.folded("Advanced: ice boat racing",
                Ui.num(s.iceboatBeam, 8, 512, 8, "#iceboat: how many runs it keeps looking at (more: better lines, slower)."),
                Ui.num(s.iceboatBudgetMs, 2, 45, 1, "#iceboat: time per tick it may think (a tick is 50 ms).")));
    }
}
