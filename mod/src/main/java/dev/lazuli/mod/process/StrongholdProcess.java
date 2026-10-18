package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.portal.Triangulation;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.world.BlockSearch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * #stronghold: throws an eye of ender, reads which way it flies, walks {@code strongholdBaseline} blocks square to
 * that and throws another; the two lines cross at the stronghold. Picks the eyes back up when they drop
 * (strongholdPickupEyes), walks there, then looks round the loaded chunks for the end portal room and goes to it
 * (digging down as the path needs). Saves a "stronghold" waypoint either way.
 */
public final class StrongholdProcess implements Process {
    private enum Phase { IDLE, THROW, WATCH, PICKUP, SIDESTEP, TRAVEL, SEARCH, TO_ROOM, FETCH }

    /** Ticks of flight read for the direction; ticks to wait for a thrown eye to show; to look for its drop. */
    private static final int READ_TICKS = 30, SHOW_TICKS = 20, DROP_TICKS = 40, PICKUP_TICKS = 200;
    /** How far round the crossing to look for the portal room, and for how long. */
    private static final int ROOM_RANGE = 128, SEARCH_TICKS = 400, TRIES = 3;

    private final Settings settings;
    private final Consumer<String> chat;
    private final BiConsumer<Minecraft, BlockPos> remember;
    private final Trip trip;
    private final InputDriver input = new InputDriver();

    private Phase phase = Phase.IDLE;
    private Triangulation.Throw first, last;
    private int throwsDone, since, tries, refines;
    /** Throws again near the crossing when the portal room isn't in sight, at most this often. */
    private static final int REFINES = 2;
    private EyeOfEnder eye;
    private Vec3 eyeStart, eyeEnd, from;
    /** Where it thinks the stronghold is; the portal frame block it found. */
    private BlockPos target, room;

    /** Eyes of ender from remembered chests when it has too few (see {@link Fetch}). */
    private Fetch fetch;
    /** After the fetch: begin the job (it was missing eyes at the start), else go on throwing. */
    private boolean restart;

    public void useChests(ContainerProcess containers, java.util.function.Function<Minecraft, dev.lazuli.core.storage.ChestMemory> chests,
                          java.util.function.Function<Minecraft, String> dimension) {
        this.fetch = new Fetch(settings, containers, chests, dimension);
    }

    private boolean fetchEyes(Minecraft mc, LocalPlayer p, int have, boolean atStart) {
        if (fetch == null || !fetch.start(mc, p, java.util.Map.of("minecraft:ender_eye", Math.max(1, 12 - have)))) return false;
        chat.accept(Chat.info("Taking eyes of ender from a chest"));
        restart = atStart;
        trip.clear();
        enter(Phase.FETCH, p);
        return true;
    }

    public StrongholdProcess(PathingSession session, Settings settings, BiConsumer<Minecraft, BlockPos> remember, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.remember = remember;
        this.trip = new Trip(session);
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    @Override
    public String status() {
        return switch (phase) {
            case IDLE -> null;
            case THROW, WATCH, PICKUP -> "Throwing an eye of ender";
            case SIDESTEP -> "Moving over for a second throw";
            case TRAVEL -> "Going to the stronghold at " + target.getX() + " " + target.getZ();
            case SEARCH -> "Looking for the portal room";
            case TO_ROOM -> "Going to the end portal";
            case FETCH -> "Taking eyes of ender from a chest";
        };
    }

    public void start(Minecraft mc) {
        stop(false);
        if (fetch != null) fetch.reset();
        begin(mc);
    }

    private void begin(Minecraft mc) {
        stop(false);
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (!mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            chat.accept(Chat.err("Strongholds are in the overworld; eyes of ender don't lead anywhere here"));
            return;
        }
        int eyes = Hands.count(p, s -> s.is(Items.ENDER_EYE));
        if (eyes < 2 && fetchEyes(mc, p, eyes, true)) return;
        if (eyes < 2) {
            chat.accept(Chat.err("#stronghold takes at least 2 eyes of ender (have {})", eyes));
            return;
        }
        first = last = null;
        throwsDone = tries = refines = 0;
        target = room = null;
        trip.clear();
        chat.accept(Chat.info("Finding the stronghold ({} eyes)", eyes));
        enter(Phase.THROW, p);
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.FETCH && fetch != null) fetch.stop();
        end(announce ? Chat.warn("Stopped") : null);
    }

    private void end(String msg) {
        phase = Phase.IDLE;
        trip.cancel();
        input.release();
        eye = null;
        if (msg != null) chat.accept(msg);
    }

    private void enter(Phase ph, LocalPlayer p) {
        phase = ph;
        since = p.tickCount;
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        int age = p.tickCount - since;
        switch (phase) {
            case FETCH -> {
                switch (fetch.tick(mc, p)) {
                    case RUNNING -> {}
                    case CANCELLED -> end(null);
                    case DONE -> {
                        if (restart) {
                            phase = Phase.IDLE;
                            begin(mc);
                        } else if (Hands.has(p, s -> s.is(Items.ENDER_EYE))) {
                            enter(Phase.THROW, p);
                        } else {
                            end(Chat.err("Out of eyes of ender"));
                        }
                    }
                }
            }
            case THROW -> {
                if (trip.running()) return;
                input.release();
                if (!Hands.hold(p, ClickDriver.get(), s -> s.is(Items.ENDER_EYE))) {
                    if (!Hands.has(p, s -> s.is(Items.ENDER_EYE)) && !fetchEyes(mc, p, 0, false)) end(Chat.err("Out of eyes of ender"));
                    return;
                }
                from = p.position();
                eye = null;
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
                enter(Phase.WATCH, p);
            }
            case WATCH -> watch(mc, p, age);
            case PICKUP -> pickup(mc, p, age);
            case SIDESTEP -> {
                if (trip.running()) return;
                if (trip.ended() == PathingSession.State.FAILED && ++tries > TRIES) {
                    end(Chat.err("Can't move over for the second throw"));
                    return;
                }
                if (trip.ended() == null) {
                    double[] s = Triangulation.sideStep(last, settings.strongholdBaseline.get() * (tries + 1));
                    trip.go(new GoalXZ((int) Math.floor(s[0]), (int) Math.floor(s[1])), p, true);
                    return;
                }
                trip.clear();
                enter(Phase.THROW, p);
            }
            case TRAVEL -> {
                if (trip.running()) return;
                if (trip.ended() == PathingSession.State.FAILED && ++tries > TRIES) {
                    end(Chat.err("Can't get to the stronghold at {} {}", target.getX(), target.getZ()));
                    return;
                }
                if (trip.ended() == PathingSession.State.ARRIVED || Math.hypot(target.getX() - p.getX(), target.getZ() - p.getZ()) < 24) {
                    trip.clear();
                    enter(Phase.SEARCH, p);
                    return;
                }
                trip.go(new GoalXZ(target.getX(), target.getZ()), p, true);
            }
            case SEARCH -> search(mc, p, age);
            case TO_ROOM -> {
                if (trip.running()) return;
                if (trip.ended() == PathingSession.State.ARRIVED) {
                    remember.accept(mc, room);
                    end(Chat.ok("At the end portal ({})", room.toShortString()));
                    return;
                }
                if (trip.ended() == PathingSession.State.FAILED && ++tries > TRIES) {
                    remember.accept(mc, room);
                    end(Chat.err("Can't get to the end portal at {} (saved as waypoint 'stronghold')", room.toShortString()));
                    return;
                }
                trip.go(new GoalNear(room.getX(), room.getY() + 1, room.getZ(), 2), p, true);
            }
            case IDLE -> { }
        }
    }

    /** Follows the thrown eye: its flight's direction, or that it went down (the stronghold's right there). */
    private void watch(Minecraft mc, LocalPlayer p, int age) {
        if (eye == null) {
            eye = mc.level.getEntitiesOfClass(EyeOfEnder.class, p.getBoundingBox().inflate(4), e -> true)
                .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
            if (eye == null) {
                if (age > SHOW_TICKS) enter(Phase.THROW, p); // didn't go: again
                return;
            }
            eyeStart = eye.position();
            since = p.tickCount;
            return;
        }
        if (eye.isRemoved() || age >= READ_TICKS) {
            eyeEnd = eye.position();
            throwsDone++;
            Triangulation.Throw t = Triangulation.Throw.of(eyeStart.x, eyeStart.z, eyeEnd.x, eyeEnd.z);
            if (t == null) {
                // hardly moved sideways: it went down, the stronghold's under here
                found(mc, p, BlockPos.containing(eyeEnd.x, p.getY(), eyeEnd.z));
            } else {
                Triangulation.Throw line = new Triangulation.Throw(from.x, from.z, t.dx(), t.dz());
                if (first == null) {
                    first = last = line;
                    chat.accept(Chat.info("The eye flies toward {}°; moving over {} blocks for another", (int) bearing(line),
                        settings.strongholdBaseline.get()));
                } else {
                    double[] at = Triangulation.meet(first, line);
                    if (at == null) {
                        last = line;
                        chat.accept(Chat.warn("Throws too close to parallel: moving over further"));
                        tries++;
                    } else {
                        found(mc, p, BlockPos.containing(at[0], p.getY(), at[1]));
                    }
                }
            }
            enter(Phase.PICKUP, p);
        }
    }

    /** The thrown eye drops (or breaks) after a few seconds: picked up if it dropped, then on to the next step. */
    private void pickup(Minecraft mc, LocalPlayer p, int age) {
        if (settings.strongholdPickupEyes.get() && age < PICKUP_TICKS) {
            boolean flying = eye != null && !eye.isRemoved();
            if (flying) return;
            Vec3 at = eye != null ? eye.position() : eyeEnd;
            ItemEntity drop = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(BlockPos.containing(at)).inflate(6),
                    e -> e.isAlive() && e.getItem().is(Items.ENDER_EYE))
                .stream().min(Comparator.comparingDouble(p::distanceToSqr)).orElse(null);
            if (drop != null) {
                if (!trip.running()) trip.go(new GoalBlock(drop.getBlockX(), drop.getBlockY(), drop.getBlockZ()), p, true);
                return;
            }
            if (age < DROP_TICKS + READ_TICKS) return; // may still drop
        }
        trip.cancel();
        trip.clear();
        eye = null;
        if (target != null) {
            tries = 0;
            enter(Phase.TRAVEL, p);
        } else {
            enter(Phase.SIDESTEP, p);
        }
    }

    private void found(Minecraft mc, LocalPlayer p, BlockPos at) {
        target = at;
        remember.accept(mc, at);
        chat.accept(Chat.ok("Stronghold at about {} {} ({} blocks off; waypoint 'stronghold')", at.getX(), at.getZ(),
            (int) Math.hypot(at.getX() - p.getX(), at.getZ() - p.getZ())));
    }

    /** Looks round the loaded chunks for an end portal frame. */
    private void search(Minecraft mc, LocalPlayer p, int age) {
        if (age % 20 != 0) return;
        int x = target.getX(), z = target.getZ();
        BlockPos f = BlockSearch.best(mc.level, x - ROOM_RANGE, mc.level.getMinY(), z - ROOM_RANGE, x + ROOM_RANGE, 80, z + ROOM_RANGE,
            s -> s.is(Blocks.END_PORTAL_FRAME), b -> b.distSqr(p.blockPosition()));
        if (f != null) {
            room = f;
            tries = 0;
            chat.accept(Chat.ok("End portal room at {}", f.toShortString()));
            enter(Phase.TO_ROOM, p);
            return;
        }
        if (age > SEARCH_TICKS && refines < REFINES && Hands.has(p, s -> s.is(Items.ENDER_EYE))) {
            // not in sight: throw again from here, much nearer, for a better crossing
            refines++;
            target = null;
            chat.accept(Chat.info("No portal in sight; throwing again from here"));
            enter(Phase.THROW, p);
            return;
        }
        if (age > SEARCH_TICKS) end(Chat.warn("No end portal frame in sight round {} {}: it's underground near there (waypoint 'stronghold')",
            x, z));
    }

    /** Compass bearing of a throw, like the F3 screen's facing (0 = south, 90 = west). */
    private static double bearing(Triangulation.Throw t) {
        return Math.toDegrees(Math.atan2(-t.dx(), t.dz()));
    }
}
