package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.ItemRequest;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.portal.NetherRoute;
import dev.lazuli.core.portal.PortalFrame;
import dev.lazuli.core.portal.PortalMemory;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.Tools;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;

/**
 * Nether portals: {@code #portal} goes through the nearest known one (or builds one here), and a far {@code #goto}
 * (netherGoto, see {@link NetherRoute}) goes through the nether: into a portal here, across the nether to the goal's
 * spot over there (an eighth as far), a portal built there, and out near the goal for the last stretch.
 * <p>
 * Building: a spot on open ground ({@link PortalFrame#find}), blocks in the way broken, the frame placed bottom up
 * (obsidian, any block in the corners), lit with flint and steel. Missing obsidian or flint and steel is fetched with
 * #getitem first (portalGather).
 */
public final class PortalProcess implements Process {
    private enum Phase { IDLE, GATHER, TO_PORTAL, IN_PORTAL, BUILD, CROSS }

    /** A known portal this close is used instead of building one. */
    private static final double USE_KNOWN = 128, USE_KNOWN_NETHER = 24;
    /** Ticks to wait in the portal before stepping out and in again; ticks to let chunks load after arriving. */
    private static final int PORTAL_WAIT = 200, SETTLE = 40;
    private static final int STEP_TICKS = 400, TRIES = 4;

    private final PathingSession session;
    private final Settings settings;
    private final BlockView world;
    private final Function<Minecraft, PortalMemory> memory;
    private final Function<Minecraft, String> dimension;
    private final Consumer<String> chat;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private GetItemProcess getItem;

    private Phase phase = Phase.IDLE;
    /** The far goal (overworld), or null for one hop (#portal). */
    private Goal goal;
    private int tx, tz;
    private String dim;
    private BlockPos portal;
    private PortalFrame.Site site;
    private int since, tries, settleUntil, usedAt;
    private boolean gathered;
    /** The cell being worked on, the spot being walked to for it, and spots it couldn't be done from. */
    private BlockPos working, goingTo;
    private final Set<BlockPos> badSpots = new HashSet<>();

    public PortalProcess(PathingSession session, Settings settings, BlockView world, Function<Minecraft, PortalMemory> memory,
                         Function<Minecraft, String> dimension, Consumer<String> chat) {
        this.session = session;
        this.settings = settings;
        this.world = world;
        this.memory = memory;
        this.dimension = dimension;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    public void useGetItem(GetItemProcess getItem) {
        this.getItem = getItem;
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    @Override
    public String status() {
        return switch (phase) {
            case IDLE -> null;
            case GATHER -> "Getting portal materials";
            case TO_PORTAL, IN_PORTAL -> "Going through a portal";
            case BUILD -> "Building a portal";
            case CROSS -> "Crossing the nether to " + NetherRoute.toNether(tx) + " " + NetherRoute.toNether(tz);
        };
    }

    /** #portal: through the nearest known portal, or one built here. */
    public void hop(Minecraft mc) {
        begin(mc, null, 0, 0);
        chat.accept(Chat.info("Going through a portal"));
        next(mc, mc.player);
    }

    /** A far #goto in the overworld that's quicker through the nether (netherGoto): true if it set off that way. */
    public boolean route(Minecraft mc, Goal g, int x, int z) {
        LocalPlayer p = mc.player;
        if (!settings.netherGoto.get() || p == null || !"overworld".equals(dimension.apply(mc))) return false;
        double walk = Math.hypot(x + 0.5 - p.getX(), z + 0.5 - p.getZ());
        PortalMemory m = memory.apply(mc);
        PortalMemory.Portal in = m.nearest("overworld", p.getX(), p.getZ());
        double toIn = in != null && in.distXZ(p.getX(), p.getZ()) <= USE_KNOWN ? in.distXZ(p.getX(), p.getZ()) : -1;
        PortalMemory.Portal out = m.nearest("the_nether", NetherRoute.toNether(x), NetherRoute.toNether(z));
        boolean exit = out != null && out.distXZ(NetherRoute.toNether(x), NetherRoute.toNether(z)) <= USE_KNOWN_NETHER;
        if (!NetherRoute.worthIt(walk, settings.netherMinDistance.get(), toIn, exit)) return false;
        // only when it has (or may fetch) what it takes to build the portals it needs
        int builds = NetherRoute.builds(toIn, exit);
        if (builds > 0 && !settings.portalGather.get() && !missing(p, builds).isEmpty()) return false;
        begin(mc, g, x, z);
        chat.accept(Chat.info("Going through the nether ({} blocks there, about {} through it)", (int) walk,
            (int) NetherRoute.netherCost(walk, toIn, exit)));
        if (builds == 0 || !gather(mc, p, builds)) next(mc, p);
        return true;
    }

    private void begin(Minecraft mc, Goal g, int x, int z) {
        stop(false);
        goal = g;
        tx = x;
        tz = z;
        dim = dimension.apply(mc);
        gathered = false;
        tries = 0;
        trip.clear();
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.GATHER && getItem != null) getItem.stop(false);
        end(announce ? Chat.warn("Stopped") : null);
    }

    private void end(String msg) {
        phase = Phase.IDLE;
        trip.cancel();
        input.release();
        site = null;
        portal = null;
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
        String now = dimension.apply(mc);
        if (!now.equals(dim)) {
            arrived(mc, p, now);
            return;
        }
        if (p.tickCount < settleUntil) return;
        switch (phase) {
            case GATHER -> {
                if (getItem.active()) return;
                if (getItem.outcome() != GetItemProcess.Outcome.DONE) {
                    end(Chat.err("Couldn't get what a portal takes (10 obsidian, flint and steel)"));
                    return;
                }
                next(mc, p);
            }
            case TO_PORTAL -> toPortal(mc, p);
            case IN_PORTAL -> {
                input.release();
                if (!inPortal(mc, p)) {
                    enter(Phase.TO_PORTAL, p); // pushed out
                    return;
                }
                if (p.tickCount - since > PORTAL_WAIT) {
                    if (++tries > TRIES) end(Chat.err("The portal doesn't take me anywhere"));
                    else enter(Phase.TO_PORTAL, p);
                }
            }
            case BUILD -> build(mc, p);
            case CROSS -> cross(mc, p);
            case IDLE -> { }
        }
    }

    /** Came out in another dimension: remember both ends, then on to the next leg. */
    private void arrived(Minecraft mc, LocalPlayer p, String now) {
        PortalMemory m = memory.apply(mc);
        if (portal != null) m.add(dim, portal.getX(), portal.getY(), portal.getZ());
        BlockPos here = nearbyPortal(mc, p.blockPosition(), 3);
        if (here != null) m.add(now, here.getX(), here.getY(), here.getZ());
        dim = now;
        portal = null;
        site = null;
        tries = 0;
        trip.clear();
        settleUntil = p.tickCount + SETTLE;
        if (goal == null) {
            end(Chat.ok("Through the portal ({})", now));
        } else if (now.equals("the_nether")) {
            chat.accept(Chat.info("In the nether: heading for {} {}", NetherRoute.toNether(tx), NetherRoute.toNether(tz)));
            enter(Phase.CROSS, p);
        } else if (now.equals("overworld")) {
            Goal g = goal;
            end(Chat.ok("Back in the overworld; on to the goal"));
            BlockPos f = Trip.start(p);
            session.start(g, true, Pos.pack(f.getX(), f.getY(), f.getZ()));
        } else {
            end(Chat.err("Came out in {}, not where I meant to", now));
        }
    }

    /** The next step from here: a known portal near enough, else build one. */
    private void next(Minecraft mc, LocalPlayer p) {
        PortalMemory m = memory.apply(mc);
        double range = "the_nether".equals(dim) ? USE_KNOWN_NETHER : USE_KNOWN;
        PortalMemory.Portal k = m.nearest(dim, p.getX(), p.getZ());
        if (k != null && k.distXZ(p.getX(), p.getZ()) <= range) {
            portal = new BlockPos(k.x(), k.y(), k.z());
            enter(Phase.TO_PORTAL, p);
            return;
        }
        if (!missing(p, 1).isEmpty()) {
            if (!gather(mc, p, 1)) end(Chat.err("A portal takes {} and there's none known near", describe(missing(p, 1))));
            return;
        }
        site = null;
        enter(Phase.BUILD, p);
    }

    private void toPortal(Minecraft mc, LocalPlayer p) {
        if (inPortal(mc, p)) {
            trip.cancel();
            input.release();
            enter(Phase.IN_PORTAL, p);
            return;
        }
        if (trip.running()) return;
        boolean loaded = mc.level.isLoaded(portal);
        if (loaded && !mc.level.getBlockState(portal).is(Blocks.NETHER_PORTAL)) {
            BlockPos near = nearbyPortal(mc, portal, 4);
            if (near == null) {
                memory.apply(mc).remove(dim, portal.getX(), portal.getY(), portal.getZ());
                chat.accept(Chat.warn("The portal at {} is gone", portal.toShortString()));
                next(mc, p);
                return;
            }
            portal = near;
        }
        if (trip.ended() == PathingSession.State.FAILED && ++tries > TRIES) {
            end(Chat.err("Can't get to the portal at {}", portal.toShortString()));
            return;
        }
        trip.go(new GoalBlock(portal.getX(), portal.getY(), portal.getZ()), p, false);
    }

    private void cross(Minecraft mc, LocalPlayer p) {
        int nx = NetherRoute.toNether(tx), nz = NetherRoute.toNether(tz);
        PortalMemory.Portal k = memory.apply(mc).nearest(dim, nx, nz);
        if (k != null && k.distXZ(nx, nz) <= USE_KNOWN_NETHER) {
            portal = new BlockPos(k.x(), k.y(), k.z());
            enter(Phase.TO_PORTAL, p);
            return;
        }
        if (trip.running()) return;
        double left = Math.hypot(nx + 0.5 - p.getX(), nz + 0.5 - p.getZ());
        if (left <= 8 || trip.ended() == PathingSession.State.ARRIVED) {
            next(mc, p); // there (or as near as it gets): build the way out
            return;
        }
        if (trip.ended() == PathingSession.State.FAILED && ++tries > TRIES) {
            chat.accept(Chat.warn("Can't get closer in the nether ({} blocks off): building the portal here", (int) left));
            next(mc, p);
            return;
        }
        trip.go(new GoalXZ(nx, nz), p, true);
    }

    // ---- building

    private void build(Minecraft mc, LocalPlayer p) {
        if (site == null) {
            BlockPos f = Trip.start(p);
            site = PortalFrame.find(world, f.getX(), f.getY(), f.getZ());
            if (site == null) {
                if (++tries > TRIES) {
                    end(Chat.err("No room for a portal around here"));
                    return;
                }
                // look a little further off
                double a = p.getRandom().nextDouble() * Math.PI * 2;
                trip.go(new GoalXZ(p.getBlockX() + (int) (Math.cos(a) * 12), p.getBlockZ() + (int) (Math.sin(a) * 12)), p, true);
                site = null;
                return;
            }
            since = p.tickCount;
            usedAt = 0;
            tries = 0;
            working = goingTo = null;
            badSpots.clear();
        }
        if (trip.ended() == PathingSession.State.FAILED && goingTo != null) {
            badSpots.add(goingTo); // couldn't get there: somewhere else
            goingTo = null;
            trip.clear();
        }
        if (trip.running()) return;
        if (p.tickCount - since > STEP_TICKS * 4) {
            end(Chat.err("Building the portal took too long"));
            return;
        }
        PortalFrame.Cell c = nextCell(mc);
        if (c == null) {
            ignite(mc, p);
            return;
        }
        BlockPos b = new BlockPos(c.x(), c.y(), c.z());
        working(b);
        if (needsBreak(mc, b, c.part())) {
            if (Aim.breakTick(mc, p, b, input, settings) == Aim.Result.BLOCKED) moveFor(mc, p, b, eye -> Aim.at(mc, p, eye, b, null) != null);
            return;
        }
        if (!holdFor(p, c.part())) {
            end(Chat.err("Out of blocks for the portal"));
            return;
        }
        if (!hasSupport(mc, b)) {
            end(Chat.err("Nothing to place the portal's {} against", b.toShortString()));
            return;
        }
        Click k = click(mc, p, p.getEyePosition(), b);
        if (k == null) {
            moveFor(mc, p, b, eye -> click(mc, p, eye, b) != null);
            return;
        }
        if (p.tickCount - usedAt < 4) return;
        if (Aim.useOn(mc, p, k.against(), k.face(), input, settings)) usedAt = p.tickCount;
    }

    /** A new cell to work on: the spots that didn't do for the last one may do for this. */
    private void working(BlockPos b) {
        if (b.equals(working)) return;
        working = b;
        badSpots.clear();
    }

    private void ignite(Minecraft mc, LocalPlayer p) {
        PortalFrame.Cell in = site.frame().entrance();
        BlockPos inside = new BlockPos(in.x(), in.y(), in.z());
        if (mc.level.getBlockState(inside).is(Blocks.NETHER_PORTAL)) {
            memory.apply(mc).add(dim, inside.getX(), inside.getY(), inside.getZ());
            chat.accept(Chat.ok("Portal built"));
            portal = inside;
            site = null;
            tries = 0;
            trip.clear();
            enter(Phase.TO_PORTAL, p);
            return;
        }
        if (!Hands.hold(p, ClickDriver.get(), s -> s.is(Items.FLINT_AND_STEEL) || s.is(Items.FIRE_CHARGE))) {
            if (!Hands.has(p, s -> s.is(Items.FLINT_AND_STEEL) || s.is(Items.FIRE_CHARGE))) end(Chat.err("No flint and steel to light the portal"));
            return;
        }
        PortalFrame.Cell base = site.frame().ignite();
        BlockPos b = new BlockPos(base.x(), base.y(), base.z());
        working(b);
        if (Aim.at(mc, p, b, Direction.UP) == null) {
            moveFor(mc, p, b, eye -> Aim.at(mc, p, eye, b, Direction.UP) != null);
            return;
        }
        if (p.tickCount - usedAt < 10) return;
        if (Aim.useOn(mc, p, b, Direction.UP, input, settings)) usedAt = p.tickCount;
    }

    /** The first cell still to do: something to break (top down), else something to place (bottom up). */
    private PortalFrame.Cell nextCell(Minecraft mc) {
        List<PortalFrame.Cell> cells = site.frame().cells();
        for (int i = cells.size() - 1; i >= 0; i--) {
            PortalFrame.Cell c = cells.get(i);
            if (needsBreak(mc, new BlockPos(c.x(), c.y(), c.z()), c.part())) return c;
        }
        for (PortalFrame.Cell c : cells) {
            if (c.part() == PortalFrame.Part.INSIDE) continue;
            if (mc.level.getBlockState(new BlockPos(c.x(), c.y(), c.z())).canBeReplaced()) return c;
        }
        return null;
    }

    /** Something's there that has to go: in the opening anything solid, in the frame anything but obsidian (corners: anything soft). */
    private static boolean needsBreak(Minecraft mc, BlockPos b, PortalFrame.Part part) {
        var s = mc.level.getBlockState(b);
        if (s.canBeReplaced()) return false;
        return switch (part) {
            case INSIDE -> !s.is(Blocks.NETHER_PORTAL);
            case OBSIDIAN -> !s.is(Blocks.OBSIDIAN);
            case CORNER -> !s.isCollisionShapeFullBlock(mc.level, b);
        };
    }

    /** Obsidian for the frame; a throwaway block for a corner (obsidian if there's none). */
    private boolean holdFor(LocalPlayer p, PortalFrame.Part part) {
        if (part == PortalFrame.Part.CORNER) {
            int slot = Tools.throwaway(p, settings);
            if (slot >= 0) {
                p.getInventory().setSelectedSlot(slot);
                return true;
            }
        }
        if (Hands.hold(p, ClickDriver.get(), s -> s.is(Items.OBSIDIAN))) return true;
        return Hands.has(p, s -> s.is(Items.OBSIDIAN));
    }

    /**
     * Out of reach or sight from here: off to the nearest spot round the frame from which it can be done, on the
     * ground or pillared up on throwaway blocks (the trip places them) for the top of the frame.
     */
    private void moveFor(Minecraft mc, LocalPlayer p, BlockPos b, Predicate<Vec3> sees) {
        if (trip.running()) return;
        input.release();
        badSpots.add(p.blockPosition()); // it can't be done from here
        BlockPos spot = ++tries > TRIES * 4 ? null : spotFor(mc, p, sees);
        if (spot == null) {
            end(Chat.err("Can't get to the portal's {} from anywhere near", b.toShortString()));
            return;
        }
        goingTo = spot;
        trip.go(new GoalBlock(spot.getX(), spot.getY(), spot.getZ()), p, false);
    }

    /** The cheapest spot (near, little pillaring) round the frame whose eye {@code sees}, or null. */
    private BlockPos spotFor(Minecraft mc, LocalPlayer p, Predicate<Vec3> sees) {
        PortalFrame f = site.frame();
        boolean pillar = settings.allowPlace.get() && Tools.throwaway(p, settings) >= 0;
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int along = -2; along <= PortalFrame.WIDTH + 1; along++) {
            for (int out = -3; out <= 3; out++) {
                if (out == 0) continue; // the frame's own plane
                int x = f.alongX() ? f.x() + along : f.x() + out, z = f.alongX() ? f.z() + out : f.z() + along;
                for (int y = f.y() - 2; y <= f.y() + 2; y++) {
                    BlockPos g = new BlockPos(x, y, z);
                    if (!support(mc, g.below()) || !clear(mc, g) || !clear(mc, g.above())) continue;
                    for (int h = 0; h <= (pillar ? 3 : 0); h++) {
                        BlockPos s = g.above(h);
                        if (h > 0 && !clear(mc, s.above())) break;
                        if (badSpots.contains(s)) continue;
                        double score = h * 3 + Math.sqrt(s.distSqr(p.blockPosition()));
                        if (score >= bestScore) continue;
                        if (!sees.test(new Vec3(s.getX() + 0.5, s.getY() + p.getEyeHeight(), s.getZ() + 0.5))) continue;
                        bestScore = score;
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    private static boolean clear(Minecraft mc, BlockPos b) {
        return mc.level.getBlockState(b).getCollisionShape(mc.level, b).isEmpty() && mc.level.getFluidState(b).isEmpty();
    }

    private record Click(BlockPos against, Direction face) {}

    private static final Direction[] SIDES = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};

    /** A solid neighbour of {@code b} (below first) whose face toward it the eye at {@code eye} can click, or null. */
    private static Click click(Minecraft mc, LocalPlayer p, Vec3 eye, BlockPos b) {
        for (Direction d : SIDES) {
            BlockPos n = b.relative(d);
            if (support(mc, n) && Aim.at(mc, p, eye, n, d.getOpposite()) != null) return new Click(n, d.getOpposite());
        }
        return null;
    }

    private static boolean hasSupport(Minecraft mc, BlockPos b) {
        for (Direction d : SIDES) if (support(mc, b.relative(d))) return true;
        return false;
    }

    /** Something a block can be placed against: solid (not a flower, not leaf litter). */
    private static boolean support(Minecraft mc, BlockPos b) {
        var s = mc.level.getBlockState(b);
        return !s.canBeReplaced() && !s.getCollisionShape(mc.level, b).isEmpty();
    }

    // ---- materials

    /** What building {@code builds} portals takes that the inventory doesn't have. */
    private static List<ItemCount> missing(LocalPlayer p, int builds) {
        List<ItemCount> out = new ArrayList<>();
        int obsidian = count(p, Items.OBSIDIAN);
        if (obsidian < PortalFrame.OBSIDIAN * builds) out.add(new ItemCount("minecraft:obsidian", PortalFrame.OBSIDIAN * builds));
        if (count(p, Items.FLINT_AND_STEEL) == 0 && count(p, Items.FIRE_CHARGE) < builds) out.add(new ItemCount("minecraft:flint_and_steel", 1));
        return out;
    }

    /** Fetches the materials with #getitem (once); false if that's off or was tried. */
    private boolean gather(Minecraft mc, LocalPlayer p, int builds) {
        List<ItemCount> need = missing(p, builds);
        if (need.isEmpty() || gathered || getItem == null || !settings.portalGather.get()) return false;
        gathered = true;
        chat.accept(Chat.info("Getting {} for the portal first", describe(need)));
        enter(Phase.GATHER, p);
        getItem.start(mc, new ItemRequest(need, null, false), true);
        return true;
    }

    private static int count(LocalPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private static String describe(List<ItemCount> items) {
        return String.join(", ", items.stream().map(i -> i.count() + " " + i.item().replace("minecraft:", "")).toList());
    }

    // ---- portal blocks

    private static boolean inPortal(Minecraft mc, LocalPlayer p) {
        return nearbyPortal(mc, p.blockPosition(), 0) != null || mc.level.getBlockState(p.blockPosition().above()).is(Blocks.NETHER_PORTAL);
    }

    /** A nether portal block within {@code r} of {@code at} (the bottom one of its column), or null. */
    private static BlockPos nearbyPortal(Minecraft mc, BlockPos at, int r) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : BlockPos.betweenClosed(at.offset(-r, -r, -r), at.offset(r, r, r))) {
            if (!mc.level.getBlockState(b).is(Blocks.NETHER_PORTAL)) continue;
            double d = b.distSqr(at);
            if (d < bd) {
                bd = d;
                best = b.immutable();
            }
        }
        while (best != null && mc.level.getBlockState(best.below()).is(Blocks.NETHER_PORTAL)) best = best.below();
        return best;
    }
}
