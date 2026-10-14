package dev.lazuli.mod.process;

import net.minecraft.client.Minecraft;

/** Something Lazuli keeps doing on top of pathing until it's done or stopped: mining, following, farming... */
public interface Process {
    boolean active();

    /** Once per client tick, after the path executor. */
    void tick(Minecraft mc);

    void stop(boolean announce);
}
