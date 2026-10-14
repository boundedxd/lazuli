package dev.lazuli.mod.control;

import dev.lazuli.core.goal.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.function.Consumer;

/**
 * #click: the next left-click picks a block anywhere in render distance and makes standing on it the goal. The click
 * doesn't attack, and holding the button afterwards doesn't start breaking anything.
 */
public final class ClickGoal {
    private static volatile boolean armed;
    /** The button is still held from the click that set the goal. */
    private static volatile boolean swallowing;
    private static Consumer<GoalBlock> onGoal = g -> {};
    private static Consumer<String> chat = m -> {};

    private ClickGoal() {}

    public static void init(Consumer<GoalBlock> goalSetter, Consumer<String> messages) {
        onGoal = goalSetter;
        chat = messages;
    }

    public static void arm() {
        armed = true;
        chat.accept("Left-click a block to make it the goal");
    }

    /** Called instead of vanilla's attack; true if the click was used here. */
    public static boolean click(Minecraft mc) {
        if (!armed) return false;
        Entity cam = mc.getCameraEntity();
        if (cam == null) return false;
        double range = mc.options.getEffectiveRenderDistance() * 16.0;
        HitResult hit = cam.pick(range, mc.getDeltaTracker().getGameTimeDeltaPartialTick(true), false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
            chat.accept("No block there (still waiting for a click)");
            swallowing = true;
            return true;
        }
        armed = false;
        swallowing = true;
        BlockPos p = bh.getBlockPos().above(); // stand on the clicked block
        onGoal.accept(new GoalBlock(p.getX(), p.getY(), p.getZ()));
        return true;
    }

    /** While the click's button stays held, vanilla mustn't keep attacking. */
    public static boolean swallowHeld(boolean attacking) {
        if (!swallowing) return false;
        if (!attacking) swallowing = false;
        return swallowing;
    }
}
