package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.control.PathFollower;
import dev.lazuli.core.control.PathTracker;
import dev.lazuli.core.control.PlayerState;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Runs at the end of every client tick: tells the planner where the player is, then picks next tick's input with
 * the sim-based {@link InputPlanner} (falling back to the rule-based {@link PathFollower} when it finds nothing,
 * e.g. on terrain the sim doesn't model).
 */
public final class PathExecutor {
    private static final long NONE = Long.MIN_VALUE + 1;
    private static final int OFF_PATH_TICKS = 10;
    private static final float PITCH_SPEED = 6f;

    private final PathingSession session;
    private final Settings settings;
    private final InputPlanner planner;
    private final PathFollower follower;
    private final PathTracker tracker = new PathTracker();
    private final BlockView world;
    private final InputDriver input = new InputDriver();
    private boolean driving;
    private long lastBlock = NONE;
    private int offPathTicks;

    public PathExecutor(PathingSession session, Settings settings, BlockView world) {
        this.session = session;
        this.settings = settings;
        this.world = world;
        this.planner = new InputPlanner(world);
        this.follower = new PathFollower(world);
    }

    public void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            reset();
            return;
        }
        PathingSession.Snapshot snap = session.snapshot();
        if (snap.state() != PathingSession.State.PATHING || mc.screen != null) {
            if (snap.state() != PathingSession.State.PATHING) lastBlock = NONE;
            reset();
            return;
        }
        if (settings.cancelOnUserInput.get() && input.userInterfering(mc)) {
            session.stop();
            reset();
            return;
        }

        BlockPos bp = player.blockPosition();
        // standing over an edge, the node is the block actually under the player, not the air beside it
        long block = player.onGround() ? Standing.nodeUnder(world, player.getX(), player.getY(), player.getZ())
            : Pos.pack(bp.getX(), bp.getY(), bp.getZ());
        List<Long> path = snap.path();
        int idx = tracker.update(path, snap.version(), Pos.x(block), Pos.y(block), Pos.z(block));
        boolean onNode = PathTracker.onNode(path, idx, Pos.x(block), Pos.y(block), Pos.z(block));
        if (onNode) offPathTicks = 0;
        else if (player.onGround()) offPathTicks++;
        // Run-up blocks and corner cuts aren't path nodes: only re-plan from a node, or once clearly off the path.
        if (player.onGround() && block != lastBlock && (onNode || offPathTicks > OFF_PATH_TICKS)) {
            lastBlock = block;
            session.playerMoved(block);
        }

        boolean sprint = settings.allowSprint.get() && player.getFoodData().getFoodLevel() > 6;
        float maxTurn = settings.rotationSpeed.get().floatValue();
        Vec3 v = player.getDeltaMovement();
        PlayerState state = new PlayerState(player.getX(), player.getY(), player.getZ(), player.getYRot(),
            player.getXRot(), player.onGround(), player.horizontalCollision, v.x, v.y, v.z, player.isSprinting());
        Controls c = null;
        if (idx >= 0) {
            c = planner.plan(state, path, idx, sprint, maxTurn);
            if (c == null) c = follower.tick(state, path.subList(idx, path.size()), sprint);
        }
        if (c == null) c = new Controls(false, false, false, player.getYRot(), player.getXRot());
        float yaw = InputPlanner.approach(player.getYRot(), c.targetYaw(), maxTurn);
        float pitch = player.getXRot() + Math.max(-PITCH_SPEED, Math.min(PITCH_SPEED, c.targetPitch() - player.getXRot()));
        input.apply(mc, c, yaw, pitch);
        driving = true;
    }

    /** The input planner's predicted motion (x, y, z triples), empty when not driving. */
    public double[] predicted() {
        return driving ? planner.predicted() : new double[0];
    }

    public void release(Minecraft mc) {
        reset();
        lastBlock = NONE;
    }

    private void reset() {
        if (driving) input.release();
        driving = false;
        offPathTicks = 0;
        tracker.reset();
        planner.reset();
        follower.reset();
    }
}
