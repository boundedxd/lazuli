package dev.lazuli.api;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.task.Task;

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
    Task container(boolean deposit, String item, int count, int[] at);

    /** Throws out {@code count} of an item ("all": anything) from the inventory; negative: all of it. */
    void drop(String item, int count);

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
