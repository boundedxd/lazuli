package dev.lazuli.mod.process;

import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.PlaceSpots;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Gets a work block's menu open (a crafting table, a furnace...): the given one or the nearest, else places one from
 * the inventory next to the player (on the ground or against a wall; walks somewhere roomier if there's no room);
 * walks over and right-clicks it.
 */
final class Station {
    enum State { WORKING, OPEN, FAILED }

    private enum Phase { FIND, TRAVEL, OPEN, PLACE, MOVE }

    private static final int OPEN_TICKS = 60, PLACE_TICKS = 100, TRIES = 2, MOVES = 3;
    /** With one to place: farther than this (blocks), or a walk longer than TRAVEL_TICKS, places its own instead. */
    private static final int NEAR = 8, TRAVEL_TICKS = 300, BREAK_TICKS = 200, COLLECT_TICKS = 80;

    private final Settings settings;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private Phase phase;
    /** What counts as the station (an anvil: any of its damage stages). */
    private Predicate<net.minecraft.world.level.block.state.BlockState> block;
    private String item, name, error;
    private Predicate<AbstractContainerMenu> menu;
    private boolean mayPlace, acted, placeOnly;
    private BlockPos pos, placed, skip;
    private PlaceSpots.Spot spot;
    private List<int[]> stands;
    private int phaseStart, tries, usedAt, moves;

    Station(PathingSession session, Settings settings, ClickDriver clicks) {
        this.settings = settings;
        this.clicks = clicks;
        this.trip = new Trip(session);
    }

    /**
     * @param at       the block to use, or null for the nearest within stationRange
     * @param item     the item that places {@code block}
     * @param mayPlace place one from the inventory when none is near
     */
    void start(LocalPlayer p, Block block, String item, String name, Predicate<AbstractContainerMenu> menu, BlockPos at, boolean mayPlace) {
        start(p, s -> s.is(block), item, name, menu, at, mayPlace);
    }

    /** @param block what counts as the station (several kinds of block, like the anvil's damage stages) */
    void start(LocalPlayer p, Predicate<net.minecraft.world.level.block.state.BlockState> block, String item, String name,
               Predicate<AbstractContainerMenu> menu, BlockPos at, boolean mayPlace) {
        this.block = block;
        this.item = item;
        this.name = name;
        this.menu = menu;
        this.mayPlace = mayPlace;
        placeOnly = false;
        pos = at;
        skip = null;
        // one it placed earlier and hasn't picked up yet stays its own
        if (placed != null && !test(Minecraft.getInstance(), placed)) placed = null;
        spot = null;
        stands = null;
        error = null;
        tries = moves = 0;
        trip.clear();
        enter(Phase.FIND, p);
    }

    /** Places {@code item} (a shulker box) next to the player and opens it, never using one already standing around. */
    void startPlaced(LocalPlayer p, Predicate<net.minecraft.world.level.block.state.BlockState> block, String item, String name,
                     Predicate<AbstractContainerMenu> menu) {
        start(p, block, item, name, menu, null, true);
        placed = null;
        placeOnly = true;
        enter(Phase.PLACE, p);
    }

    BlockPos pos() {
        return pos;
    }

    /** The block this placed itself (for picking it up again after), or null. */
    BlockPos placed() {
        return placed;
    }

    String error() {
        return error;
    }

    void cancel() {
        trip.cancel();
        input.release();
    }

    State tick(Minecraft mc, LocalPlayer p) {
        if (menu.test(p.containerMenu)) {
            cancel();
            return State.OPEN;
        }
        switch (phase) {
            case FIND -> {
                if (pos != null && !test(mc, pos)) pos = null; // broken meanwhile
                if (pos == null && !placeOnly) {
                    pos = nearest(mc, p, s -> block.test(s), settings.stationRange.get(), skip, avoid);
                    // carrying one: placing it beats a long walk to another
                    if (pos != null && mayPlace && has(p) && pos.distSqr(p.blockPosition()) > NEAR * NEAR) pos = null;
                }
                if (pos == null) {
                    if (mayPlace) enter(Phase.PLACE, p);
                    else return fail("No " + name + " within " + settings.stationRange.get() + " blocks, and placing one is off");
                    return State.WORKING;
                }
                enter(Phase.TRAVEL, p);
            }
            case TRAVEL -> {
                if (!test(mc, pos)) {
                    enter(Phase.FIND, p);
                    return State.WORKING;
                }
                if (Aim.at(mc, p, pos, null) != null) {
                    trip.cancel();
                    enter(Phase.OPEN, p);
                    return State.WORKING;
                }
                boolean slow = p.tickCount - phaseStart > TRAVEL_TICKS;
                if (trip.running() && !slow) return State.WORKING;
                if (++tries > TRIES || slow) {
                    if (!(mayPlace && has(p))) return fail("Can't get to the " + name);
                    trip.cancel();
                    skip = pos; // carrying one: put it down here instead
                    pos = null;
                    enter(Phase.PLACE, p);
                    return State.WORKING;
                }
                trip.go(new GoalAdjacent(pos.getX(), pos.getY(), pos.getZ()), p);
            }
            case OPEN -> {
                if (p.tickCount - phaseStart > OPEN_TICKS) return fail("The " + name + " wouldn't open");
                // no screen a second after the click (missed, or lag): click again
                if (acted && p.tickCount - clickedAt > REOPEN_TICKS) acted = false;
                if (!acted && (acted = Aim.useOn(mc, p, pos, null, input, settings))) clickedAt = p.tickCount;
            }
            case PLACE -> {
                return place(mc, p);
            }
            case MOVE -> {
                if (!trip.running()) enter(Phase.PLACE, p);
            }
        }
        return State.WORKING;
    }

    private static final int REOPEN_TICKS = 20;
    private int clickedAt;

    private State place(Minecraft mc, LocalPlayer p) {
        if (spot != null && test(mc, at(spot))) {
            pos = placed = at(spot);
            spot = null;
            tries = 0;
            enter(Phase.TRAVEL, p);
            return State.WORKING;
        }
        if (p.tickCount - phaseStart > PLACE_TICKS) return move(p, "Couldn't place a " + name);
        int slot = -1;
        for (int i = 0; i < 36; i++) if (Inventories.key(p.getInventory().getItem(i)).equals(item)) slot = i;
        if (slot < 0) return fail("No " + name + " to place");
        int sel = p.getInventory().getSelectedSlot();
        if (slot != sel) {
            if (slot < 9) p.getInventory().setSelectedSlot(slot);
            else if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, sel)));
            phaseStart = p.tickCount; // the clock starts once it's in hand
            return State.WORKING;
        }
        if (spot == null || !mc.level.getBlockState(at(spot)).canBeReplaced()) spot = spot(mc, p);
        if (spot == null) return move(p, "No room to place a " + name + " here");
        if (p.tickCount - usedAt < 10) return State.WORKING; // give the last try time to show up
        if (Aim.useOn(mc, p, against(spot), face(spot), input, settings)) usedAt = p.tickCount;
        return State.WORKING;
    }

    private boolean test(Minecraft mc, BlockPos at) {
        return block.test(mc.level.getBlockState(at));
    }

    private boolean has(LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (Inventories.key(p.getInventory().getItem(i)).equals(item)) return true;
        return false;
    }

    private int pickTicks;
    private boolean broken;

    /** Starts picking up the block this placed (see {@link #pickUp}); false if it placed none. */
    boolean startPickUp(LocalPlayer p) {
        return startPickUp(p, false);
    }

    /** @param always even with pickupStations off (a shulker box with things in it) */
    boolean startPickUp(LocalPlayer p, boolean always) {
        cancel();
        if (placed == null || !placed.equals(pos) || !(always || settings.pickupStations.get())) return false;
        // a furnace broken without a pickaxe is gone: leave it then
        var state = Minecraft.getInstance().level.getBlockState(placed);
        if (state.requiresCorrectToolForDrops()) {
            boolean tool = false;
            for (int i = 0; i < 36 && !tool; i++) tool = p.getInventory().getItem(i).isCorrectToolForDrops(state);
            if (!tool) return false;
        }
        pickTicks = 0;
        broken = false;
        return true;
    }

    /** One tick of breaking the placed block and collecting its drop: true once done (or given up). */
    boolean pickUp(Minecraft mc, LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        pickTicks++;
        if (!broken) {
            if (!test(mc, placed)) {
                broken = true;
                pickTicks = 0;
                input.release();
                return false;
            }
            if (pickTicks > BREAK_TICKS) return endPickUp();
            if (Aim.breakTick(mc, p, placed, input, settings) == Aim.Result.BLOCKED && !trip.running()) {
                trip.go(new GoalAdjacent(placed.getX(), placed.getY(), placed.getZ()), p);
            }
            return false;
        }
        // its drop: walk over it until it's picked up
        var drops = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(placed).inflate(4),
            e -> e.isAlive() && base(Inventories.key(e.getItem())).equals(base(item)));
        if (drops.isEmpty() || pickTicks > COLLECT_TICKS) return endPickUp();
        if (!trip.running()) {
            BlockPos at = drops.get(0).blockPosition();
            trip.go(new GoalBlock(at.getX(), at.getY(), at.getZ()), p);
        }
        return false;
    }

    /** A key without its components part (a shulker box comes back with other contents). */
    private static String base(String key) {
        int h = key.indexOf('#');
        return h < 0 ? key : key.substring(0, h);
    }

    private boolean endPickUp() {
        placed = null;
        cancel();
        return true;
    }

    /** Nowhere to place it from here: walk to the nearest roomier place and try again (a few times). */
    private State move(LocalPlayer p, String why) {
        spot = null;
        if (stands == null) {
            BlockPos f = Trip.start(p);
            stands = new ArrayList<>(PlaceSpots.standsNear(probe(Minecraft.getInstance()), f.getX(), f.getY(), f.getZ(), 6));
        }
        if (moves++ >= MOVES || stands.isEmpty()) return fail(why + (moves > 1 ? " (moved too)" : ""));
        int[] to = stands.remove(0);
        trip.go(new GoalBlock(to[0], to[1], to[2]), p);
        enter(Phase.MOVE, p);
        return State.WORKING;
    }

    private State fail(String msg) {
        error = msg;
        cancel();
        return State.FAILED;
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
        acted = false;
    }

    /**
     * A free block next to the player to place on, against something solid that doesn't open when clicked (the ground
     * first, else a wall or ceiling), out of the player's way and with that face in reach.
     */
    private static PlaceSpots.Spot spot(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        for (PlaceSpots.Spot s : PlaceSpots.around(probe(mc), feet.getX(), feet.getY(), feet.getZ())) {
            if (p.getBoundingBox().intersects(new AABB(at(s)))) continue;
            if (Aim.at(mc, p, against(s), face(s)) != null) return s;
        }
        return null;
    }

    private static PlaceSpots.Probe probe(Minecraft mc) {
        return new PlaceSpots.Probe() {
            @Override
            public boolean free(int x, int y, int z) {
                var state = mc.level.getBlockState(new BlockPos(x, y, z));
                return state.canBeReplaced() && state.getFluidState().isEmpty();
            }

            @Override
            public boolean support(int x, int y, int z) {
                BlockPos at = new BlockPos(x, y, z);
                var state = mc.level.getBlockState(at);
                return state.isCollisionShapeFullBlock(mc.level, at) && !(state.getBlock() instanceof EntityBlock)
                    && state.getMenuProvider(mc.level, at) == null;
            }
        };
    }

    private static BlockPos at(PlaceSpots.Spot s) {
        return new BlockPos(s.x(), s.y(), s.z());
    }

    private static BlockPos against(PlaceSpots.Spot s) {
        return new BlockPos(s.againstX(), s.againstY(), s.againstZ());
    }

    /** The face of the against block that looks at the spot. */
    private static Direction face(PlaceSpots.Spot s) {
        int dx = s.x() - s.againstX(), dy = s.y() - s.againstY(), dz = s.z() - s.againstZ();
        for (Direction d : Direction.values()) if (d.getStepX() == dx && d.getStepY() == dy && d.getStepZ() == dz) return d;
        throw new IllegalStateException(s.toString());
    }

    /** The nearest {@code block} within {@code r} blocks (half that up and down), or null. */
    static BlockPos nearest(Minecraft mc, LocalPlayer p, Block block, int r) {
        return nearest(mc, p, s -> s.is(block), r);
    }

    /** The nearest block matching {@code test} within {@code r} blocks (half that up and down), or null. */
    static BlockPos nearest(Minecraft mc, LocalPlayer p, Predicate<net.minecraft.world.level.block.state.BlockState> test, int r) {
        return nearest(mc, p, test, r, null);
    }

    private static BlockPos nearest(Minecraft mc, LocalPlayer p, Predicate<net.minecraft.world.level.block.state.BlockState> test,
                                    int r, BlockPos skip) {
        return nearest(mc, p, test, r, skip, java.util.Set.of());
    }

    private static BlockPos nearest(Minecraft mc, LocalPlayer p, Predicate<net.minecraft.world.level.block.state.BlockState> test,
                                    int r, BlockPos skip, java.util.Set<BlockPos> avoid) {
        BlockPos feet = p.blockPosition();
        // only the chunk sections that can hold one are looked through
        return dev.lazuli.mod.world.BlockSearch.best(mc.level, feet.getX() - r, feet.getY() - r / 2, feet.getZ() - r,
            feet.getX() + r, feet.getY() + r / 2, feet.getZ() + r, test,
            at -> at.equals(skip) || avoid.contains(at) ? Double.MAX_VALUE : at.distSqr(feet));
    }

    /** Stations not to use (busy with someone else's things), until {@link #clearAvoid}. */
    private final java.util.Set<BlockPos> avoid = new java.util.HashSet<>();

    void avoid(BlockPos at) {
        avoid.add(at.immutable());
    }

    void clearAvoid() {
        avoid.clear();
    }
}
