package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

import java.util.function.Consumer;

/**
 * #sleep: gets into the nearest bed (or one placed from the inventory, sleepPlaceBed), stays until morning, then
 * breaks a bed it placed to take it along (sleepPickupBed).
 */
public final class SleepProcess implements Process {
    private enum Phase { FIND, TRAVEL, PLACE, USE, SLEEP, PICKUP }

    private static final int USE_TICKS = 60, PLACE_TICKS = 80, PICKUP_TICKS = 100, TRIES = 2;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private Phase phase;
    private boolean active, placed, acted;
    private BlockPos bed;
    private int phaseStart, tries, prevSlot = -1;

    public SleepProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        if (!active) return null;
        return switch (phase) {
            case FIND, TRAVEL -> "Sleep (to the bed)";
            case PLACE -> "Sleep (placing a bed)";
            case USE, SLEEP -> "Sleeping";
            case PICKUP -> "Sleep (taking the bed)";
        };
    }

    /** Night (or a thunderstorm), when beds work. */
    public static boolean night(Minecraft mc) {
        long t = mc.level.getDayTime() % 24000;
        return t >= 12542 && t <= 23459 || mc.level.isThundering();
    }

    public void start(Minecraft mc) {
        LocalPlayer p = mc.player;
        String dim = mc.level.dimension().identifier().getPath();
        if (dim.equals("the_nether") || dim.equals("the_end")) {
            chat.accept(Chat.err("Beds explode in this dimension"));
            return;
        }
        if (!night(mc)) {
            chat.accept(Chat.err("You can only sleep at night or in a thunderstorm"));
            return;
        }
        active = true;
        placed = false;
        bed = null;
        tries = 0;
        trip.clear();
        enter(Phase.FIND, p);
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        active = false;
        trip.cancel();
        input.release();
        restoreSlot(Minecraft.getInstance().player);
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.isSleeping()) {
            p.connection.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING));
        }
        if (announce) chat.accept(Chat.info("Stopped sleeping"));
    }

    private void finish(String msg, boolean ok) {
        stop(false);
        chat.accept(ok ? Chat.ok(msg) : Chat.err(msg));
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null) {
            stop(false);
            return;
        }
        int age = p.tickCount - phaseStart;
        switch (phase) {
            case FIND -> {
                bed = Station.nearest(mc, p, b -> b.getBlock() instanceof BedBlock, settings.stationRange.get());
                if (bed != null) enter(Phase.TRAVEL, p);
                else if (!settings.sleepPlaceBed.get()) finish("No bed within " + settings.stationRange.get() + " blocks", false);
                else if (bedSlot(p) < 0) finish("No bed near and none in the hotbar", false);
                else enter(Phase.PLACE, p);
            }
            case TRAVEL -> {
                if (!(mc.level.getBlockState(bed).getBlock() instanceof BedBlock)) {
                    enter(Phase.FIND, p);
                } else if (Aim.at(mc, p, bed, null) != null) {
                    trip.cancel();
                    enter(Phase.USE, p);
                } else if (!trip.running()) {
                    if (++tries > TRIES) finish("Can't get to the bed", false);
                    else trip.go(new GoalAdjacent(bed.getX(), bed.getY(), bed.getZ()), p);
                }
            }
            case PLACE -> place(mc, p, age);
            case USE -> {
                if (p.isSleeping()) {
                    enter(Phase.SLEEP, p);
                } else if (age > USE_TICKS) {
                    finish(night(mc) ? "Couldn't sleep (monsters near, or the bed's taken)" : "It's morning", !night(mc));
                } else if (!acted) {
                    acted = Aim.useOn(mc, p, bed, null, input, settings);
                }
            }
            case SLEEP -> {
                if (p.isSleeping()) return;
                if (placed && settings.sleepPickupBed.get()) enter(Phase.PICKUP, p);
                else finish("Good morning", true);
            }
            case PICKUP -> {
                boolean gone = !(mc.level.getBlockState(bed).getBlock() instanceof BedBlock);
                // the bed's gone: a moment for it to fly into the inventory
                if (gone && age > 20 || age > PICKUP_TICKS) finish("Good morning", true);
                else if (!gone && Aim.breakTick(mc, p, bed, input, settings) == Aim.Result.BLOCKED) finish("Good morning (left the bed)", true);
            }
        }
    }

    /** Lays a bed on the floor next to the player, the head pointing away, where two blocks are free. */
    private void place(Minecraft mc, LocalPlayer p, int age) {
        if (age > PLACE_TICKS) {
            finish("Couldn't place a bed here", false);
            return;
        }
        BlockPos feet = p.blockPosition();
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos foot = feet.relative(d);
            if (mc.level.getBlockState(foot).getBlock() instanceof BedBlock) {
                bed = foot;
                placed = true;
                restoreSlot(p);
                enter(Phase.USE, p);
                return;
            }
        }
        int slot = bedSlot(p);
        if (slot < 0) {
            finish("No bed in the hotbar", false);
            return;
        }
        if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(slot);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos foot = feet.relative(d), head = foot.relative(d);
            if (!free(mc, foot) || !free(mc, head) || p.getBoundingBox().intersects(new AABB(foot)) || p.getBoundingBox().intersects(new AABB(head))) continue;
            if (!mc.level.getBlockState(foot.below()).isFaceSturdy(mc.level, foot.below(), Direction.UP)
                || !mc.level.getBlockState(head.below()).isFaceSturdy(mc.level, head.below(), Direction.UP)) continue;
            float[] aim = Aim.at(mc, p, foot.below(), Direction.UP);
            if (aim == null) continue;
            // the bed's head points the way the player faces, so only once facing that way
            Aim.turn(mc, p, input, aim, settings);
            BlockHitResult hit = Aim.current(mc, p);
            if (age % 5 == 0 && hit != null && hit.getBlockPos().equals(foot.below()) && hit.getDirection() == Direction.UP
                && p.getDirection() == d && mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit).consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
            }
            return;
        }
        finish("No room for a bed here (two free blocks on flat ground)", false);
    }

    private static boolean free(Minecraft mc, BlockPos at) {
        var s = mc.level.getBlockState(at);
        return s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    private static int bedSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            var s = p.getInventory().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().endsWith("_bed")) return i;
        }
        return -1;
    }

    private void restoreSlot(LocalPlayer p) {
        if (p != null && prevSlot >= 0) p.getInventory().setSelectedSlot(prevSlot);
        prevSlot = -1;
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
        acted = false;
    }
}
