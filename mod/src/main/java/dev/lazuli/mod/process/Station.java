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

    private final Settings settings;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private Phase phase;
    private Block block;
    private String item, name, error;
    private Predicate<AbstractContainerMenu> menu;
    private boolean mayPlace, acted;
    private BlockPos pos;
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
        this.block = block;
        this.item = item;
        this.name = name;
        this.menu = menu;
        this.mayPlace = mayPlace;
        pos = at;
        spot = null;
        stands = null;
        error = null;
        tries = moves = 0;
        trip.clear();
        enter(Phase.FIND, p);
    }

    BlockPos pos() {
        return pos;
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
                if (pos != null && !mc.level.getBlockState(pos).is(block)) pos = null; // broken meanwhile
                if (pos == null) pos = nearest(mc, p, block, settings.stationRange.get());
                if (pos == null) {
                    if (mayPlace) enter(Phase.PLACE, p);
                    else return fail("No " + name + " within " + settings.stationRange.get() + " blocks, and placing one is off");
                    return State.WORKING;
                }
                enter(Phase.TRAVEL, p);
            }
            case TRAVEL -> {
                if (!mc.level.getBlockState(pos).is(block)) {
                    enter(Phase.FIND, p);
                    return State.WORKING;
                }
                if (Aim.at(mc, p, pos, null) != null) {
                    trip.cancel();
                    enter(Phase.OPEN, p);
                    return State.WORKING;
                }
                if (trip.running()) return State.WORKING;
                if (++tries > TRIES) return fail("Can't get to the " + name);
                trip.go(new GoalAdjacent(pos.getX(), pos.getY(), pos.getZ()), p);
            }
            case OPEN -> {
                if (p.tickCount - phaseStart > OPEN_TICKS) return fail("The " + name + " wouldn't open");
                if (!acted) acted = Aim.useOn(mc, p, pos, null, input, settings);
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

    private State place(Minecraft mc, LocalPlayer p) {
        if (spot != null && mc.level.getBlockState(at(spot)).is(block)) {
            pos = at(spot);
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
        BlockPos feet = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int minY = Math.max(mc.level.getMinY(), feet.getY() - r / 2), maxY = Math.min(mc.level.getMaxY(), feet.getY() + r / 2);
        for (int y = minY; y <= maxY; y++)
            for (int x = feet.getX() - r; x <= feet.getX() + r; x++)
                for (int z = feet.getZ() - r; z <= feet.getZ() + r; z++) {
                    at.set(x, y, z);
                    if (!mc.level.getBlockState(at).is(block)) continue;
                    double d = at.distSqr(feet);
                    if (d < bestD) {
                        bestD = d;
                        best = at.immutable();
                    }
                }
        return best;
    }
}
