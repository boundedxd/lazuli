package dev.lazuli.api;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.task.Task;

import java.util.List;

/**
 * Lazuli for other mods (and for Lazuli's own chains of work, like getting an item). Call it on the client thread.
 * Each job comes back as a {@link Task}: wait on {@link Task#done()} to hear how it ended. One job runs at a time,
 * so starting one cancels the last.
 */
public interface Lazuli {
    /** The running instance (null before the client has started). */
    static Lazuli get() {
        return Holder.instance;
    }

    /** Every setting, as in the config screen and {@code #set}. */
    Settings settings();

    /** Walks (and mines, bridges, jumps...) to the block at x y z. */
    default Task goTo(int x, int y, int z) {
        return goTo(new dev.lazuli.core.goal.GoalBlock(x, y, z), false);
    }

    /** Goes to {@code goal}; {@code fallback}: if it can't be reached, the nearest safe spot will do. */
    Task goTo(Goal goal, boolean fallback);

    /**
     * Mines {@code quantity} of these blocks (ids like {@code iron_ore}; 0 means every one it can find). Done once it
     * has the number asked for (or found none left after mining some), failed if it found none at all.
     */
    Task mine(int quantity, String... blocks);

    /**
     * Moves items between the inventory and a container (a chest, barrel, shulker box...). Done once it moved the
     * number asked for (all of them if {@code count} is negative).
     *
     * @param deposit into the container, else out of it
     * @param item    an id like {@code iron_ingot}, or "all"
     * @param at      the container {x, y, z}, or null for the nearest chest, barrel, shulker box or ender chest
     */
    default Task container(boolean deposit, String item, int count, int[] at) {
        return container(deposit, List.of(new ItemCount(item, count)), at);
    }

    /** Like {@link #container(boolean, String, int, int[])} for several items in one visit; done once every one moved. */
    Task container(boolean deposit, List<ItemCount> items, int[] at);

    /** Throws out {@code count} of an item ("all": anything) from the inventory; negative: all of it. */
    default Task drop(String item, int count) {
        return drop(List.of(new ItemCount(item, count)));
    }

    /** Throws out several items at once; done once the clicks have been played. */
    Task drop(List<ItemCount> items);

    /**
     * Crafts {@code count} of an item (an id like {@code stick}), crafting the ingredients first if the
     * craftIngredients setting allows, at a crafting table when it doesn't fit the inventory grid.
     */
    default Task craft(String item, int count) {
        return craft(List.of(new ItemCount(item, count)));
    }

    /** Crafts several items, one after another. */
    Task craft(List<ItemCount> items);

    /**
     * Smelts {@code count} of an item: what comes out (iron_ingot) or what goes in (raw_iron); negative: all the
     * input it has. With the smeltWait setting off it's done once the furnace is loaded (#smelt collect goes back).
     */
    Task smelt(String item, int count);

    /**
     * Gets an item from scratch until the inventory holds {@code count} of it: mines, crafts and smelts what it takes,
     * tools, fuel, crafting table and furnace included, planning again after every step.
     */
    Task getItem(String item, int count);

    /** Brews {@code count} bottles of a potion (fire_resistance, long_swiftness...) in a brewing stand. */
    Task brew(String potion, dev.lazuli.core.craft.Brewing.Form form, int count);

    /** Trades with villagers for these (selling things for emeralds first if need be). */
    Task trade(List<dev.lazuli.core.trade.Want> wants);

    /** Sells {@code count} of an item (an id like {@code minecraft:string}; negative: all of it) to villagers for emeralds. */
    Task tradeSell(String item, int count);

    /**
     * Fills {@code n} more buckets or casts obsidian. {@code item}: {@code minecraft:water_bucket}, {@code lava_bucket},
     * {@code milk_bucket} (from empty buckets in the inventory) or {@code minecraft:obsidian} (needs a water bucket).
     */
    Task bucket(String item, int n);

    /** Kills the mobs that drop an item until {@code count} more are in the inventory (like {@code #hunt string 20}). */
    Task hunt(String item, int count);

    /** Upgrades at a smithing table (netherite_pickaxe...); the template, base item and addition must be in the inventory. */
    Task smith(String item, int count);

    /** The running job, or null. */
    Task current();

    /** Pathing or running anything (started here or from chat). */
    boolean busy();

    /** Stops everything. */
    void stop();

    /** Runs a Lazuli command as if typed in chat, with or without the prefix: {@code command("goto 0 64 0")}. */
    void command(String line);

    /** Where {@link #get()} finds the instance; set by the mod. */
    final class Holder {
        static volatile Lazuli instance;

        private Holder() {}

        public static void set(Lazuli lazuli) {
            instance = lazuli;
        }
    }
}
