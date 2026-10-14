package dev.lazuli.mod.control;

import dev.lazuli.core.control.Builder;
import dev.lazuli.core.control.Climber;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.EdgeWatch;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.control.PathFollower;
import dev.lazuli.core.control.PathTracker;
import dev.lazuli.core.control.PlayerState;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import dev.lazuli.mod.mixin.LivingEntityAccessor;
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
    /** No jumping except up onto higher blocks, and no sprinting: set while farming, so farmland isn't trampled. */
    public static volatile boolean gentle;
    private static final int OFF_PATH_TICKS = 10;
    /** How far ahead to look for the next block to break or place. */
    private static final int WALK_HORIZON = 64;
    private static final float PITCH_SPEED = 6f;

    private final PathingSession session;
    private final Settings settings;
    private final InputPlanner planner;
    private final PathFollower follower;
    private final Climber climber;
    private final PathTracker tracker = new PathTracker();
    private final BlockView world;
    private final EdgeWatch edgeWatch = new EdgeWatch();
    private final InputDriver input = new InputDriver();
    private final Interactor interactor;
    private boolean driving;
    private long lastBlock = NONE;
    private int offPathTicks;

    public PathExecutor(PathingSession session, Settings settings, BlockView world, java.util.function.Consumer<String> chat) {
        this.interactor = new Interactor(world, settings, session, chat);
        this.session = session;
        this.settings = settings;
        this.world = world;
        this.planner = new InputPlanner(world);
        this.follower = new PathFollower(world);
        this.climber = new Climber(world);
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
        long swim = player.isInWater() ? Standing.swimNode(world, player.getX(), player.getY(), player.getZ()) : Long.MIN_VALUE;
        boolean settled = player.onGround() || swim != Long.MIN_VALUE; // standing, or floating at the surface
        long block = swim != Long.MIN_VALUE ? swim
            : player.onGround() ? Standing.nodeUnder(world, player.getX(), player.getY(), player.getZ())
            : Pos.pack(bp.getX(), bp.getY(), bp.getZ());
        List<Long> path = snap.path();
        int idx = tracker.update(path, snap.version(), Pos.x(block), Pos.y(block), Pos.z(block));
        boolean onNode = PathTracker.onNode(path, idx, Pos.x(block), Pos.y(block), Pos.z(block));
        if (onNode) offPathTicks = 0;
        else if (settled) offPathTicks++;
        // Run-up blocks and corner cuts aren't path nodes: only re-plan from a node, or once clearly off the path.
        if (settled && block != lastBlock && (onNode || offPathTicks > OFF_PATH_TICKS)) {
            lastBlock = block;
            session.playerMoved(block);
        }

        boolean sprint = settings.allowSprint.get() && player.getFoodData().getFoodLevel() > 6;
        float maxTurn = settings.rotationSpeed.get().floatValue();
        Vec3 v = player.getDeltaMovement();
        float curYaw = RotationDriver.yaw(player), curPitch = RotationDriver.pitch(player);
        PlayerState state = new PlayerState(player.getX(), player.getY(), player.getZ(), curYaw,
            curPitch, player.onGround(), player.horizontalCollision, v.x, v.y, v.z, player.isSprinting(),
            ((LivingEntityAccessor) player).lazuli$noJumpDelay(), player.minorHorizontalCollision, player.fallDistance);
        boolean eating = dev.lazuli.mod.process.Upkeep.eating();
        if (gentle) sprint = false;
        Controls c = eating ? null : interactor.tick(mc, state, path, idx, block);
        // eating: stand still meanwhile (counts as building, so standing still isn't taken as being stuck)
        if (eating) c = new Controls(false, false, false, false, curYaw, curPitch, 0, false);
        boolean building = c != null;
        // ladders and vines (still watched for getting stuck, unlike building)
        if (c == null && idx >= 0) c = climber.tick(state, path, idx);
        boolean interacting = c != null;
        if (interacting) {
            planner.reset();
        } else if (idx >= 0) {
            // move only as far as the world allows: up to the next block to break or place, never into the gap after it
            List<Long> walkable = path.subList(0, Builder.walkableEnd(world, path, idx, WALK_HORIZON) + 1);
            planner.setMaxFall(settings.maxFallHeight.get());
            c = planner.plan(state, walkable, idx, sprint, maxTurn);
            if (c == null && !player.onGround()) c = InputPlanner.airBrake(state);
            if (c == null) c = follower.tick(state, walkable.subList(Math.min(idx, walkable.size() - 1), walkable.size()), sprint);
            if (gentle && c != null && c.jump() && player.onGround() && idx + 1 < path.size()) {
                // walk, don't hop, onto anything not higher up (gaps still need their jump)
                long u = path.get(idx), w = path.get(idx + 1);
                boolean step = Math.max(Math.abs(Pos.x(w) - Pos.x(u)), Math.abs(Pos.z(w) - Pos.z(u))) <= 1;
                if (step && Pos.y(w) <= Pos.y(u)) {
                    c = new Controls(c.forward(), c.back(), false, false, c.targetYaw(), c.targetPitch(), c.strafe(), c.sneak());
                }
            }
        }
        long[] failed = edgeWatch.update(path, idx, block, settled, Math.hypot(v.x, v.z), building);
        if (failed != null) session.edgeFailed(failed[0], failed[1]);
        if (c == null) c = new Controls(false, false, false, curYaw, curPitch);
        float yaw = InputPlanner.approach(curYaw, c.targetYaw(), maxTurn);
        float pitchSpeed = interacting ? maxTurn : PITCH_SPEED;
        float pitch = curPitch + Math.max(-pitchSpeed, Math.min(pitchSpeed, c.targetPitch() - curPitch));
        input.apply(mc, c, yaw, pitch, settings.freeLook.get());
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
        interactor.reset();
        offPathTicks = 0;
        tracker.reset();
        edgeWatch.reset();
        planner.reset();
        follower.reset();
    }
}
