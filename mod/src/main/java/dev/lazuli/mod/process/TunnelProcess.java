package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.RotationDriver;
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
    private final InputDriver input = new InputDriver();
    private boolean active, driving;
    private BlockPos origin;
    private Direction dir;
    private int height, width, depth, step, stuck;

    public TunnelProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
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
        for (BlockPos c : cells) {
            if (fluidNear(mc, c)) {
                finish(Chat.warn("Lava or water ahead; stopped after {} blocks", step));
                return;
            }
        }
        for (BlockPos c : cells) {
            BlockState s = mc.level.getBlockState(c);
            if (s.isAir() || s.getCollisionShape(mc.level, c).isEmpty() && s.getFluidState().isEmpty()) continue;
            if (s.getDestroySpeed(mc.level, c) < 0) {
                finish(Chat.err("Unbreakable block ahead; stopped after {} blocks", step));
                return;
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

    /** Lava or water in or right next to a cell about to be opened. */
    private static boolean fluidNear(Minecraft mc, BlockPos c) {
        for (Direction d : Direction.values()) {
            var f = mc.level.getFluidState(c.relative(d));
            if (f.is(FluidTags.LAVA) || f.is(FluidTags.WATER)) return true;
        }
        var f = mc.level.getFluidState(c);
        return f.is(FluidTags.LAVA) || f.is(FluidTags.WATER);
    }

    private void release() {
        if (driving) input.release();
        driving = false;
    }
}
