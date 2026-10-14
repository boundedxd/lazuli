package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.PathFollower;
import dev.lazuli.core.control.PlayerState;
import dev.lazuli.core.control.RotationController;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

import java.util.List;

/** Runs once per client tick: follows the planner's latest path. */
public final class PathExecutor {
    private static final long NONE = Long.MIN_VALUE + 1;
    private static final int OFF_PATH_TICKS = 10;

    private final PathingSession session;
    private final Settings settings;
    private final PathFollower follower;
    private final RotationController rotation = new RotationController();
    private final InputDriver input = new InputDriver();
    private boolean driving;
    private long lastBlock = NONE;
    private Controls lastControls;
    private int lastIdx = -1;
    private long lastVersion = -1;
    private int offPathTicks;

    public PathExecutor(PathingSession session, Settings settings, dev.lazuli.core.world.BlockView world) {
        this.follower = new PathFollower(world);
        this.session = session;
        this.settings = settings;
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset(mc);
            return;
        }
        PathingSession.Snapshot snap = session.snapshot();
        if (snap.state() != PathingSession.State.PATHING || mc.screen != null) {
            if (snap.state() != PathingSession.State.PATHING) lastBlock = NONE;
            reset(mc);
            return;
        }
        if (settings.cancelOnUserInput.get() && input.userInterfering(mc)) {
            session.stop();
            reset(mc);
            return;
        }

        BlockPos bp = player.blockPosition();
        long block = Pos.pack(bp.getX(), bp.getY(), bp.getZ());
        List<Long> path = snap.path();
        int idx = indexOf(path, bp);
        if (idx >= 0) {
            lastIdx = idx;
            lastVersion = snap.version();
            offPathTicks = 0;
        } else if (player.onGround()) {
            offPathTicks++;
        }
        // Run-up blocks of a gap jump aren't path nodes: only re-plan from a path node, or once we've
        // clearly left the path (knocked off, fell).
        if (player.onGround() && block != lastBlock && (idx >= 0 || offPathTicks > OFF_PATH_TICKS)) {
            lastBlock = block;
            session.playerMoved(block);
        }
        if (idx < 0 && lastVersion == snap.version() && lastIdx < path.size() - 1) {
            idx = lastIdx;
        }

        Controls c;
        if (idx >= 0) {
            boolean sprint = settings.allowSprint.get() && player.getFoodData().getFoodLevel() > 6;
            PlayerState state = new PlayerState(player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision);
            c = follower.tick(state, path.subList(idx, path.size()), sprint);
            lastControls = c;
        } else if (lastControls != null) {
            c = lastControls; // mid-air or waiting for the planner to catch up
        } else {
            return;
        }
        float[] rot = rotation.step(player.getYRot(), player.getXRot(), c.targetYaw(), c.targetPitch(),
            settings.rotationSpeed.get().floatValue());
        input.apply(mc, c, rot);
        driving = true;

        if (settings.renderPath.get() && player.tickCount % 10 == 0) {
            for (int i = 0; i < Math.min(64, path.size()); i++) {
                long n = path.get(i);
                mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, Pos.x(n) + 0.5, Pos.y(n) + 0.1, Pos.z(n) + 0.5, 0, 0, 0);
            }
        }
    }

    public void release(Minecraft mc) {
        reset(mc);
        lastBlock = NONE;
    }

    private void reset(Minecraft mc) {
        if (driving && mc.options != null) {
            input.release(mc);
        }
        driving = false;
        lastControls = null;
        lastIdx = -1;
        offPathTicks = 0;
        rotation.reset();
    }

    private static int indexOf(List<Long> path, BlockPos bp) {
        int limit = Math.min(path.size(), 12);
        for (int i = 0; i < limit; i++) {
            long n = path.get(i);
            if (Pos.x(n) == bp.getX() && Pos.z(n) == bp.getZ() && Pos.y(n) == bp.getY()) return i;
        }
        for (int i = 0; i < limit; i++) {
            long n = path.get(i);
            if (Pos.x(n) == bp.getX() && Pos.z(n) == bp.getZ()) return i;
        }
        return -1;
    }
}
