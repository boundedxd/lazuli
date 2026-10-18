package dev.lazuli.mod.control;

import dev.lazuli.core.control.Builder;
import dev.lazuli.core.control.Climber;
import dev.lazuli.core.control.Jumper;
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
    /** No jumping except up onto higher blocks (and no sprinting with farmSprint off): set while farming, so farmland isn't trampled. */
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
    private final Jumper jumper;
    private final PathTracker tracker = new PathTracker();
    private final BlockView world;
    private final EdgeWatch edgeWatch = new EdgeWatch();
    private final InputDriver input = new InputDriver();
    private final Interactor interactor;
    private final Swimmer swimmer;
    private final BoatRider boat;
    /** A boat crossing was under way: the first settled tick after it re-plans from there. */
    private boolean afterBoat;
    private final Clutch clutch;
    private final Torcher torcher;
    private final java.util.function.Consumer<String> chat;
    /** Watchdog: where the player was when nothing last happened, for how long, and the re-plans tried there. */
    private Vec3 stallAt = Vec3.ZERO;
    /** Stuck: a fresh planner thread plans the trip again from scratch (given the player's block). */
    private java.util.function.LongConsumer restart;
    private long stallBlock = NONE;
    private int stallTicks, unsticks;
    /** Ticks of nothing happening before the trip is planned again from scratch (no path yet: the search may be long). */
    private static final int STALL_TICKS = 120, STALL_NO_PATH_TICKS = 200, MAX_UNSTICKS = 3;
    /** Moving less than this far (blocks, sideways) from where it stalled isn't getting anywhere. */
    private static final double STALL_MOVE = 0.8;
    private boolean driving;
    private long lastBlock = NONE;
    private int offPathTicks;

    public PathExecutor(PathingSession session, Settings settings, BlockView world, java.util.function.Consumer<String> chat) {
        this.interactor = new Interactor(world, settings, session, chat);
        this.chat = chat;
        this.session = session;
        this.restart = session::unstick;
        this.settings = settings;
        this.world = world;
        this.planner = new InputPlanner(world);
        this.follower = new PathFollower(world);
        this.climber = new Climber(world);
        this.jumper = new Jumper(world);
        this.swimmer = new Swimmer(world);
        this.boat = new BoatRider(world, settings);
        this.torcher = new Torcher(settings);
        this.clutch = new Clutch(settings);
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
            // a search going on and on with the player standing there (no path yet): stuck too
            if (mc.screen == null && session.planning() && settings.unstick.get()) {
                BlockPos f = player.blockPosition();
                watchdog(player, snap, Pos.pack(f.getX(), f.getY(), f.getZ()), dev.lazuli.mod.process.Upkeep.eating(), true);
            } else {
                stallTicks = 0;
            }
            return;
        }
        // moving to cancel is handled once for everything in LazuliClient

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
        // out of a boat (at the far shore, or stuck halfway): plan on from right here, wherever that is on the path
        if (boat.active()) afterBoat = true;
        else if (afterBoat && settled) {
            afterBoat = false;
            tracker.reset();
            lastBlock = block;
            session.playerMoved(block);
        }

        boolean sprint = settings.allowSprint.get() && player.getFoodData().getFoodLevel() > 6;
        // open water ahead: swim mode (sprint-swimming just under the surface, up for air in time)
        Controls swimC = sprint && settings.sprintSwim.get() && !gentle && !dev.lazuli.mod.process.Upkeep.eating()
            ? swimmer.tick(player, path, idx, RotationDriver.yaw(player)) : null;
        if (swimC == null && settings.surfaceSwim.get() && (player.isInWater() || player.isSwimming())) {
            // sprinting with the head under, the game sprint-swims: it dives where the view points and the air runs out.
            // Not sprinting and holding jump (the planner's choice in water), it floats with the head out.
            sprint = false;
            if (player.isSprinting()) player.setSprinting(false);
        }
        float maxTurn = settings.rotationSpeed.get().floatValue();
        Vec3 v = player.getDeltaMovement();
        float curYaw = RotationDriver.yaw(player), curPitch = RotationDriver.pitch(player);
        PlayerState state = new PlayerState(player.getX(), player.getY(), player.getZ(), curYaw,
            curPitch, player.onGround(), player.horizontalCollision, v.x, v.y, v.z, player.isSprinting(),
            ((LivingEntityAccessor) player).lazuli$noJumpDelay(), player.minorHorizontalCollision, player.fallDistance);
        boolean eating = dev.lazuli.mod.process.Upkeep.eating();
        // farmland only gets trampled by landing on it, not by running: sprint unless farmSprint is off
        if (gentle && !settings.farmSprint.get()) sprint = false;
        // long water ahead and a boat: crossing by boat takes over until it's out the other side
        Controls c = eating ? null : boat.tick(mc, player, path, idx, curYaw, curPitch);
        if (c == null && !eating) c = interactor.tick(mc, state, path, idx, block);
        // dark underground: a torch at the feet (standing still meanwhile, like building)
        if (c == null && !eating && idx >= 0 && !boat.active()) c = torcher.tick(mc, player, curYaw, curPitch);
        // eating: stand still meanwhile (counts as building, so standing still isn't taken as being stuck)
        if (eating) c = new Controls(false, false, false, false, curYaw, curPitch, 0, false);
        boolean building = c != null;
        // ladders and vines (still watched for getting stuck, unlike building)
        if (c == null && idx >= 0) c = climber.tick(state, path, idx);
        // long and upward gap jumps: played exactly as proven, not left to the input planner
        jumper.setSneak(settings.allowSneakStop.get());
        if (c == null && !building) c = jumper.tick(state, path, idx, sprint);
        if (c == null && !building) c = swimC;
        boolean interacting = c != null;
        if (interacting) {
            planner.reset();
        } else if (idx >= 0) {
            // move only as far as the world allows: up to the next block to break or place, never into the gap after it
            List<Long> walkable = path.subList(0, Builder.walkableEnd(world, path, idx, WALK_HORIZON) + 1);
            // a long drop the path clutches just ahead: the planner may take it (nowhere else)
            int fall = settings.maxFallHeight.get();
            if (settings.waterClutch.get() && Clutch.ready(mc, player)) {
                for (int i = idx; i + 1 < Math.min(path.size(), idx + 4); i++) {
                    if (Pos.y(path.get(i)) - Pos.y(path.get(i + 1)) > fall) fall = Math.max(fall, settings.clutchFallHeight.get());
                }
            }
            planner.setMaxFall(fall);
            planner.setFlicks(settings.allowFlicks.get());
            c = planner.plan(state, walkable, idx, sprint, maxTurn);
            if (c == null && !player.onGround()) c = InputPlanner.airBrake(state, settings.allowFlicks.get());
            if (c == null) c = follower.tick(state, walkable.subList(Math.min(idx, walkable.size() - 1), walkable.size()), sprint);
            if (gentle && c != null && c.jump() && player.onGround() && idx + 1 < path.size()) {
                // walk, don't hop, onto anything not higher up (gaps still need their jump)
                long u = path.get(idx), w = path.get(idx + 1);
                boolean step = Math.max(Math.abs(Pos.x(w) - Pos.x(u)), Math.abs(Pos.z(w) - Pos.z(u))) <= 1;
                if (step && Pos.y(w) <= Pos.y(u)) {
                    c = new Controls(c.forward(), c.back(), false, c.sprint(), c.targetYaw(), c.targetPitch(), c.strafe(), c.sneak());
                }
            }
        }
        if (!interacting && c != null && idx >= 0 && idx + 1 < path.size()) {
            // dropping down a hole it's digging: keep looking down (the next block to mine is right below)
            long u = path.get(idx), w = path.get(idx + 1);
            if (Pos.x(u) == Pos.x(w) && Pos.z(u) == Pos.z(w) && Pos.y(w) < Pos.y(u)) {
                // straight down: no reason to turn (where the path goes after the shaft can wait till the bottom); the
                // yaw only when no key is held, since the keys were chosen for the planner's own facing
                boolean keys = c.forward() || c.back() || c.strafe() != 0;
                c = new Controls(c.forward(), c.back(), c.jump(), c.sprint(), keys ? c.targetYaw() : curYaw,
                    Math.max(curPitch, c.targetPitch()), c.strafe(), c.sneak());
            }
        }
        long[] failed = edgeWatch.update(path, idx, block, settled, Math.hypot(v.x, v.z), building);
        if (failed != null) session.edgeFailed(failed[0], failed[1]);
        if (settings.unstick.get()) watchdog(player, snap, block, eating, false);
        if (c == null) c = new Controls(false, false, false, curYaw, curPitch);
        if (!sprint && c.sprint()) c = new Controls(c.forward(), c.back(), c.jump(), false, c.targetYaw(), c.targetPitch(), c.strafe(), c.sneak());
        // falling far with a water bucket: look down and clutch (steering stays the path's)
        Clutch.Aim ca = clutch.tick(mc, player);
        if (ca != null) {
            c = ca.still() ? new Controls(false, false, false, false, c.targetYaw(), ca.pitch(), 0, false)
                : new Controls(c.forward(), c.back(), c.jump(), c.sprint(), c.targetYaw(), ca.pitch(), c.strafe(), c.sneak());
            interacting = true;
        }
        session.setClutchFall(settings.waterClutch.get() && Clutch.ready(mc, player) ? settings.clutchFallHeight.get() : 0);
        float yaw = InputPlanner.approach(curYaw, c.targetYaw(), maxTurn);
        float pitchSpeed = interacting ? maxTurn : PITCH_SPEED;
        float pitch = curPitch + Math.max(-pitchSpeed, Math.min(pitchSpeed, c.targetPitch() - curPitch));
        input.apply(mc, c, yaw, pitch, settings.freeLook.get());
        driving = true;
    }

    /**
     * A trip under way with nothing happening (not moving, not breaking a block, not eating) for a while: whatever
     * got stuck (a search, a start that wasn't a node, a stale plan), it's planned again from scratch, the way #kill
     * and running it again would. Still stuck after a few tries at the same block, the trip is given up.
     */
    private void watchdog(LocalPlayer player, PathingSession.Snapshot snap, long block, boolean eating, boolean searching) {
        boolean busyHere = Interactor.mining() != null || eating || boat.active() || player.isUsingItem()
            || dev.lazuli.mod.process.Guard.paused();
        // getting somewhere: a block sideways, or a block up or down standing (jumping into a corner over and over isn't)
        Vec3 at = player.position();
        double dx = at.x - stallAt.x, dz = at.z - stallAt.z;
        boolean progress = dx * dx + dz * dz > STALL_MOVE * STALL_MOVE || player.onGround() && Math.abs(at.y - stallAt.y) > 0.9;
        if (busyHere || progress) {
            stallAt = at;
            stallTicks = 0;
        } else {
            stallTicks++;
        }
        if (block != stallBlock) {
            stallBlock = block;
            unsticks = 0;
        }
        if (stallTicks < (searching || snap.path().isEmpty() ? STALL_NO_PATH_TICKS : STALL_TICKS)) return;
        stallTicks = 0;
        if (++unsticks > MAX_UNSTICKS) {
            unsticks = 0;
            chat.accept(dev.lazuli.core.chat.Chat.err("Stuck at {}; gave up on this trip", Pos.toString(block)));
            session.stop(true);
            return;
        }
        reset();
        chat.accept(dev.lazuli.core.chat.Chat.warn("Stuck; planning again ({}/{})", unsticks, MAX_UNSTICKS));
        // what #kill fixed, without stopping the job (a hung planner thread never gets to a plain re-plan)
        if (settings.unstickRestart.get()) restart.accept(block);
        else session.unstick(block);
    }

    /** How to plan the trip again when stuck (unstickRestart): swap the planner thread, then restart the trip. */
    public void onStuck(java.util.function.LongConsumer restart) {
        this.restart = restart;
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
        swimmer.reset();
        boat.reset(Minecraft.getInstance());
        torcher.reset(Minecraft.getInstance());
        offPathTicks = 0;
        tracker.reset();
        edgeWatch.reset();
        planner.reset();
        follower.reset();
        jumper.reset();
    }
}
