package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.control.Tools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * #tunnel [height width depth]: digs a straight tunnel the way the player faces, one slice at a time: clear the
 * slice (top down, middle first), step into it, repeat. Stops at lava or water, at a drop, after {@code depth}
 * blocks, or when something can't be dug.
 */
public final class TunnelProcess implements Process {
    /** Ticks a block may resist (out of sight, unbreakable) before the tunnel gives up. */
    private static final int STUCK_TICKS = 600;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private MakeRoom room;
    private final InputDriver input = new InputDriver();
    private boolean active, driving;
    private BlockPos origin;
    private Direction dir;
    private int height, width, depth, step, stuck, sealTicks;
    /** Ticks one stretch of sealing off fluid may take before the tunnel gives up. */
    private static final int SEAL_TICKS = 400;

    public TunnelProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
        this.room = new MakeRoom(settings, null, chat);
    }

    /** Lets a full inventory be put away in a remembered chest, not just thrown out. */
    public void useContainers(ContainerProcess containers) {
        room = new MakeRoom(settings, containers, chat);
    }

    @Override
    public String status() {
        return active() ? "Tunnelling" : null;
    }

    @Override
    public boolean active() {
        return active;
    }

    /** Blocks dug forward so far. */
    public int dug() {
        return step;
    }

    /** @param depth blocks to dig, or 0 for no end */
    public void start(Minecraft mc, int height, int width, int depth) {
        start(mc, height, width, depth, Direction.fromYRot(RotationDriver.yaw(mc.player)));
    }

    /** Digs toward {@code dir} instead of the way the player faces. */
    public void start(Minecraft mc, int height, int width, int depth, Direction dir) {
        LocalPlayer p = mc.player;
        this.height = height;
        this.width = width;
        this.depth = depth;
        this.dir = dir;
        origin = p.blockPosition();
        step = 0;
        stuck = 0;
        sealTicks = 0;
        room.reset();
        active = true;
        trip.clear();
        chat.accept(Chat.ok("Tunnelling {} ({}x{}{})", dir.getName(), height, width, depth > 0 ? ", " + depth + " long" : ""));
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        active = false;
        release();
        trip.cancel();
        if (announce) chat.accept(Chat.warn("Stopped tunnelling after {} blocks", step));
    }

    private void finish(String msg) {
        active = false;
        release();
        trip.cancel();
        chat.accept(msg);
    }

    /** The cells of slice {@code k} (k blocks ahead of the start), in digging order: middle column top-down first. */
    private List<BlockPos> slice(int k) {
        BlockPos base = origin.relative(dir, k);
        Direction right = dir.getClockWise();
        List<BlockPos> out = new ArrayList<>();
        int[] offsets = new int[width];
        // 0, 1, -1, 2, -2...: the middle first, alternating outwards, the extra column (even widths) to the right
        for (int i = 0, n = 0; n < width; i++) {
            offsets[n++] = (i + 1) / 2 * (i % 2 == 1 ? 1 : -1);
        }
        for (int w : offsets)
            for (int h = height - 1; h >= 0; h--) out.add(base.relative(right, w).above(h));
        return out;
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer player = mc.player;
        if (trip.running()) return;
        PathingSession.State ended = trip.ended();
        if (ended != null) {
            trip.clear();
            if (ended != PathingSession.State.ARRIVED) {
                finish(Chat.err("Can't step into the tunnel; stopped after {} blocks", step));
                return;
            }
            step++;
        }
        if (depth > 0 && step >= depth) {
            finish(Chat.ok("Tunnel done: {} blocks", step));
            return;
        }
        List<BlockPos> cells = slice(step + 1);
        BlockPos fluid = null;
        for (BlockPos c : cells) {
            fluid = fluidNear(mc, c);
            if (fluid != null) break;
        }
        if (fluid != null) {
            if (!settings.tunnelSealFluid.get()) {
                finish(Chat.warn("Lava or water ahead; stopped after {} blocks", step));
                return;
            }
            if (!seal(mc, player, fluid) || ++sealTicks > SEAL_TICKS) {
                finish(Chat.err("Lava or water ahead and nothing to seal it with (or can't reach it); stopped after {} blocks", step));
            }
            return;
        }
        sealTicks = 0;
        for (BlockPos c : cells) {
            BlockState s = mc.level.getBlockState(c);
            if (s.isAir() || s.getCollisionShape(mc.level, c).isEmpty() && s.getFluidState().isEmpty()) continue;
            if (s.getDestroySpeed(mc.level, c) < 0) {
                finish(Chat.err("Unbreakable block ahead; stopped after {} blocks", step));
                return;
            }
            if (s.requiresCorrectToolForDrops() && !player.getAbilities().instabuild && !hasTool(player, s)) {
                finish(Chat.err("No tool that gets anything from {}; stopped after {} blocks", s.getBlock().getName().getString(), step));
                return;
            }
            if (MakeRoom.full(player)) {
                switch (room.tick(mc, player, null)) {
                    case WORKING -> {
                        release();
                        return;
                    }
                    case STUCK -> {
                        finish(room.reason() + " (stopped after " + step + " blocks)");
                        return;
                    }
                    default -> { }
                }
            }
            Aim.Result r = Aim.breakTick(mc, player, c, input, settings);
            driving = true;
            if (r == Aim.Result.BLOCKED || ++stuck > STUCK_TICKS) {
                finish(Chat.err("Can't reach {}; stopped after {} blocks", c.toShortString(), step));
            }
            return;
        }
        stuck = 0;
        release();
        BlockPos next = origin.relative(dir, step + 1);
        if (mc.level.getBlockState(next.below()).getCollisionShape(mc.level, next.below()).isEmpty()) {
            finish(Chat.warn("Drop ahead; stopped after {} blocks", step));
            return;
        }
        trip.go(new GoalBlock(next.getX(), next.getY(), next.getZ()), player);
    }

    private static boolean hasTool(LocalPlayer p, BlockState s) {
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isCorrectToolForDrops(s)) return true;
        return false;
    }

    /** A lava or water block in or right next to a cell about to be opened (null: none). */
    private static BlockPos fluidNear(Minecraft mc, BlockPos c) {
        var f = mc.level.getFluidState(c);
        if (f.is(FluidTags.LAVA) || f.is(FluidTags.WATER)) return c;
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            f = mc.level.getFluidState(n);
            if (f.is(FluidTags.LAVA) || f.is(FluidTags.WATER)) return n;
        }
        return null;
    }

    /** One tick of filling the fluid block {@code f} with a block from the hotbar. False: can't (no blocks, none in reach). */
    private boolean seal(Minecraft mc, LocalPlayer player, BlockPos f) {
        int slot = Tools.throwaway(player, settings);
        if (slot < 0) return false;
        player.getInventory().setSelectedSlot(slot);
        for (Direction d : Direction.values()) {
            BlockPos n = f.relative(d);
            Direction face = d.getOpposite();
            if (!mc.level.getFluidState(n).isEmpty() || !mc.level.getBlockState(n).isFaceSturdy(mc.level, n, face)) continue;
            if (Aim.at(mc, player, n, face) == null) continue;
            driving = true;
            Aim.useOn(mc, player, n, face, input, settings);
            return true;
        }
        return false;
    }

    private void release() {
        if (driving) input.release();
        driving = false;
    }
}
