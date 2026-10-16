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
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

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
        }
        if (trip.running()) return;
        if (p.tickCount - since > STEP_TICKS * 4) {
            end(Chat.err("Building the portal took too long"));
            return;
        }
        BlockPos stand = new BlockPos(site.standX(), site.standY(), site.standZ());
        PortalFrame.Cell c = nextCell(mc);
        if (c == null) {
            ignite(mc, p, stand);
            return;
        }
        BlockPos b = new BlockPos(c.x(), c.y(), c.z());
        if (needsBreak(mc, b, c.part())) {
            if (Aim.breakTick(mc, p, b, input, settings) == Aim.Result.BLOCKED) walkTo(p, stand);
            return;
        }
        if (!holdFor(p, c.part())) {
            end(Chat.err("Out of blocks for the portal"));
            return;
        }
        BlockPos against = against(mc, b);
        if (against == null) {
            end(Chat.err("Nothing to place the portal's {} against", b.toShortString()));
            return;
        }
        Direction face = Direction.getApproximateNearest(b.getX() - against.getX(), b.getY() - against.getY(), b.getZ() - against.getZ());
        if (Aim.at(mc, p, against, face) == null) {
            walkTo(p, stand);
            return;
        }
        if (p.tickCount - usedAt < 4) return;
        if (Aim.useOn(mc, p, against, face, input, settings)) usedAt = p.tickCount;
    }

    private void ignite(Minecraft mc, LocalPlayer p, BlockPos stand) {
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
        if (Aim.at(mc, p, b, Direction.UP) == null) {
            walkTo(p, stand);
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

    private void walkTo(LocalPlayer p, BlockPos stand) {
        if (trip.running()) return;
        if (++tries > TRIES * 3) {
            end(Chat.err("Can't reach the portal frame from {}", stand.toShortString()));
            return;
        }
        input.release();
        trip.go(new GoalBlock(stand.getX(), stand.getY(), stand.getZ()), p, false);
    }

    /** A solid neighbour to place {@code b} against (below first), or null. */
    private static BlockPos against(Minecraft mc, BlockPos b) {
        if (!mc.level.getBlockState(b.below()).canBeReplaced()) return b.below();
        for (Direction d : Direction.values()) if (!mc.level.getBlockState(b.relative(d)).canBeReplaced()) return b.relative(d);
        return null;
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
