package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;

/** Staying alive while busy: eating, armor, the guard, light, sleep, restocking, what happens after dying. */
public final class SurvivalTab {
    private SurvivalTab() {}

    public static Category build(Settings s) {
        return Ui.tab("Survival",
            Ui.group("Eating",
                Ui.bool(s.autoEat, "Eat from the hotbar when hungry while Lazuli is busy."),
                Ui.num(s.autoEatHunger, 1, 19, 1, "Hunger (out of 20) at or below which it eats.")),
            Ui.group("Armor",
                Ui.bool(s.autoArmor, "Put on better armor from the inventory while busy."),
                Ui.bool(s.autoTotem, "Keep a totem of undying in the offhand when you have one."),
                Ui.bool(s.autoShield, "Keep a shield in the offhand when there's no totem for it.")),
            Ui.group("Guard",
                Ui.bool(s.dodgeCreepers, "Run from a creeper that starts hissing nearby; the job waits."),
                Ui.bool(s.defend, "Fight monsters that come close with the best sword or axe; the job waits."),
                Ui.bool(s.lowHpRetreat, "At low health get away from monsters, eat and heal; the job waits."),
                Ui.num(s.retreatHealth, 1, 19, 1, "Health (out of 20, 2 per heart) at or below which it retreats."),
                Ui.bool(s.useBow, "In a fight, shoot skeletons and creepers with a bow while they're still a way off."),
                Ui.bool(s.useShield, "In a fight, hold the shield up between swings and against arrows."),
                Ui.bool(s.fightOrFlee, "Run instead of fighting when there are too many monsters for your health, armor and weapon.")),
            Ui.group("Avoiding danger",
                Ui.bool(s.avoidMobs, "Paths bend around hostile mobs instead of walking right past them."),
                Ui.num(s.mobAvoidRadius, 2, 16, 1, "How far from a mob paths start to keep away (creepers: half again)."),
                Ui.num(s.mobAvoidCost, 1, 400, 1, "How much detour (ticks) is worth it to stay clear of a mob."),
                Ui.bool(s.avoidLava, "Paths keep a block away from lava, fire and cactus when there's room.")),
            Ui.group("Light",
                Ui.bool(s.autoTorch, "Place torches from the hotbar where it's dark underground."),
                Ui.num(s.torchLight, 1, 14, 1, "Block light under which a torch goes down (higher: more torches).")),
            Ui.group("Death",
                Ui.bool(s.deathWaypoint, "Save a 'death' waypoint where you die; #back goes there."),
                Ui.bool(s.deathRecovery, "After respawning, go back and pick up the items you dropped (same dimension, before they despawn).")),
            Ui.group("Restock",
                Ui.bool(s.restock, "While busy, refill what's running low from chests you've opened (or shulker boxes); the job waits. Refills when under half."),
                Ui.bool(s.restockFollow, "Also restock while following someone (#follow)."),
                Ui.bool(s.restockUrgent, "Hungry with no food (or too hurt to heal): fetch food from a chest you've opened at once, even if nothing else is running.")),
            Ui.folded("Restock: what to keep",
                Ui.bool(s.restockFood, "Keep food (best first: golden carrots, steak...)."),
                Ui.num(s.restockFoodCount, 1, 64, 1, "How much food to keep."),
                Ui.bool(s.restockPickaxe, "Keep a pickaxe."),
                Ui.bool(s.restockAxe, "Keep an axe."),
                Ui.bool(s.restockShovel, "Keep a shovel."),
                Ui.bool(s.restockSword, "Keep a sword."),
                Ui.bool(s.restockTorch, "Keep torches."),
                Ui.num(s.restockTorchCount, 1, 64, 1, "How many torches to keep."),
                Ui.bool(s.restockBlocks, "Keep building blocks (the throwaway blocks: dirt, cobblestone...)."),
                Ui.num(s.restockBlocksCount, 1, 256, 1, "How many building blocks to keep."),
                Ui.bool(s.restockArrows, "Keep arrows (32)."),
                Ui.bool(s.restockWaterBucket, "Keep a water bucket."),
                Ui.bool(s.restockTotem, "Keep a totem of undying."),
                Ui.bool(s.restockShield, "Keep a shield."),
                Ui.text(s.restockItems, "More to keep: item:amount, comma separated (e.g. arrow:32,bread:16).")),
            Ui.folded("Sleep",
                Ui.bool(s.sleepPlaceBed, "#sleep places a bed from the hotbar when none is near."),
                Ui.bool(s.sleepPickupBed, "#sleep takes the bed it placed along in the morning.")));
    }
}
