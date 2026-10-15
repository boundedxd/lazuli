package dev.lazuli.mod.process;

import net.minecraft.client.Minecraft;

/** Something Lazuli keeps doing on top of pathing until it's done or stopped: mining, following, farming... */
public interface Process {
    boolean active();

    /** Once per client tick, after the path executor. */
    void tick(Minecraft mc);

    void stop(boolean announce);

    /** What it's doing, for the HUD (null: nothing worth showing). */
    default String status() {
        return null;
    }

    /** How far along it is, 0 to 1, for the HUD's bar; negative when there's no telling. */
    default double progress() {
        return -1;
    }
}
