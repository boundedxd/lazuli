package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Crosses long stretches of water on the path by boat: places one on the water, gets in, steers along the path's
 * water nodes, gets out at the far side and breaks the boat to take it along. Gives up (and swims) when a step
 * doesn't work out.
 */
final class BoatRider {
    private enum Phase { IDLE, PLACE, MOUNT, RIDE, DISMOUNT, BREAK }

    private static final double WATER_TOP = 8.0 / 9.0;
    /** Ticks each step may take before the boat is given up on. */
    private static final int PLACE_TICKS = 40, MOUNT_TICKS = 80, DISMOUNT_TICKS = 20, BREAK_TICKS = 60;
    /** Ticks of hardly moving while riding before it's taken as stuck. */
    private static final int STUCK_TICKS = 60;
    /** Path nodes ahead it steers for. */
    private static final int AHEAD = 4;

    private final BlockView world;
    private final Settings settings;
    private Phase phase = Phase.IDLE;
    private AbstractBoat boat;
    /** The water nodes being crossed (path positions), where along them the boat is, and the node to land on. */
    private List<Long> run = List.of();
    private int progress, ticks, slow, usedAt;
    /** Boats that were around before placing ours: never taken for the one just placed. */
    private final java.util.Set<Integer> before = new java.util.HashSet<>();
    private long landing;
    /** The last node of a crossing given up on or done: not tried again from there. */
    private long skipUntil = Long.MIN_VALUE;

    BoatRider(BlockView world, Settings settings) {
        this.world = world;
        this.settings = settings;
    }

    boolean active() {
        return phase != Phase.IDLE;
    }

    void reset(Minecraft mc) {
        if (phase == Phase.RIDE && mc.player != null && mc.player.isPassenger()) mc.player.stopRiding();
        phase = Phase.IDLE;
        boat = null;
    }

    /** Controls while crossing by boat (or starting to), else null. */
    Controls tick(Minecraft mc, LocalPlayer p, List<Long> path, int idx, float yaw0, float pitch0) {
        if (phase == Phase.IDLE && !start(p, path, idx)) return null;
        ticks++;
        return switch (phase) {
            case PLACE -> place(mc, p, yaw0);
            case MOUNT -> mount(mc, p, yaw0, pitch0);
            case RIDE -> ride(p, pitch0);
            case DISMOUNT -> dismount(p, yaw0, pitch0);
            case BREAK -> breakBoat(mc, p, yaw0, pitch0);
            case IDLE -> null;
        };
    }

    /** A long enough stretch of water starts at the next node and there's a boat to cross it with. */
    private boolean start(LocalPlayer p, List<Long> path, int idx) {
        if (!settings.useBoats.get() || idx < 0 || p.isPassenger() || !(p.onGround() || p.isInWater())) return false;
        int first = idx + 1;
        if (first >= path.size() || !swims(path.get(first))) return false;
        int end = first;
        while (end + 1 < path.size() && swims(path.get(end + 1))) end++;
        if (path.get(end) == skipUntil) return false;
        if (end - first + 1 < settings.boatMinWater.get() || end + 1 >= path.size()) return false; // too short, or no shore after
        if (boatSlot(p, 36) < 0) return false;
        run = List.copyOf(path.subList(first, end + 1));
        landing = path.get(end + 1);
        progress = 0;
        ticks = slow = 0;
        usedAt = -100;
        boat = null;
        before.clear();
        for (AbstractBoat b : Minecraft.getInstance().level.getEntitiesOfClass(AbstractBoat.class, p.getBoundingBox().inflate(16))) {
            before.add(b.getId());
        }
        phase = Phase.PLACE;
        return true;
    }

    private Controls place(Minecraft mc, LocalPlayer p, float yaw0) {
        if (ticks > PLACE_TICKS) return giveUp();
        int slot = boatSlot(p, settings.allowInventory.get() && mc.screen == null ? 36 : 9);
        if (slot < 0) return giveUp();
        if (slot >= 9) {
            // into the selected hotbar slot first (the click driver may stop and open the inventory to do it)
            ClickDriver clicks = ClickDriver.get();
            if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, p.getInventory().getSelectedSlot())));
            return still(yaw0, p.getXRot());
        }
        p.getInventory().setSelectedSlot(slot);
        // the boat this placed: any new one near (it shows up a tick or two after the click)
        AbstractBoat b = newBoat(mc, p);
        // on the first water block, right next to the shore, aiming at its surface
        long cell = run.get(0);
        Vec3 at = new Vec3(Pos.x(cell) + 0.5, Pos.y(cell) + WATER_TOP, Pos.z(cell) + 0.5);
        float[] aim = angles(p, at);
        boolean onIt = Math.abs(Mth.wrapDegrees(aim[0] - RotationDriver.yaw(p))) < 3 && Math.abs(aim[1] - RotationDriver.pitch(p)) < 3;
        // one click, then time for the boat to appear: clicking on would put down a row of boats
        if (b == null && onIt && ticks - usedAt > 12) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            usedAt = ticks;
        }
        if (b != null) {
            boat = b;
            phase = Phase.MOUNT;
            ticks = 0;
        }
        return new Controls(false, false, false, false, aim[0], aim[1]);
    }

    private Controls mount(Minecraft mc, LocalPlayer p, float yaw0, float pitch0) {
        if (p.getVehicle() == boat) {
            phase = Phase.RIDE;
            ticks = 0;
            return still(yaw0, pitch0);
        }
        if (ticks > MOUNT_TICKS || boat.isRemoved()) return giveUp();
        float[] aim = angles(p, boat.position().add(0, 0.3, 0));
        // in reach (with a little to spare): look at it and right-click it, as a player would; else walk up to it
        boolean near = p.isWithinEntityInteractionRange(boat, -0.3);
        boolean onIt = Math.abs(Mth.wrapDegrees(aim[0] - RotationDriver.yaw(p))) < 10 && Math.abs(aim[1] - RotationDriver.pitch(p)) < 10;
        if (near && onIt && ticks % 4 == 0) {
            mc.gameMode.interact(p, boat, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
        }
        return new Controls(!near, false, false, false, aim[0], aim[1]);
    }

    private Controls ride(LocalPlayer p, float pitch0) {
        if (p.getVehicle() != boat) return giveUp();
        Vec3 pos = boat.position();
        // how far along: the run node nearest the boat, never going back
        double best = Double.POSITIVE_INFINITY;
        for (int i = progress; i < Math.min(run.size(), progress + 8); i++) {
            double d = sq(Pos.x(run.get(i)) + 0.5 - pos.x) + sq(Pos.z(run.get(i)) + 0.5 - pos.z);
            if (d < best) {
                best = d;
                progress = i;
            }
        }
        double lx = Pos.x(landing) + 0.5, lz = Pos.z(landing) + 0.5;
        if (progress >= run.size() - 1 || sq(lx - pos.x) + sq(lz - pos.z) < 2.5 * 2.5) {
            phase = Phase.DISMOUNT;
            ticks = 0;
            return still(p.getYRot(), pitch0);
        }
        Vec3 v = boat.getDeltaMovement();
        slow = v.x * v.x + v.z * v.z < 0.02 * 0.02 && ticks > 20 ? slow + 1 : 0;
        if (slow > STUCK_TICKS) {
            phase = Phase.DISMOUNT; // stuck on something: get out and carry on from here
            ticks = 0;
        }
        long t = run.get(Math.min(run.size() - 1, progress + AHEAD));
        double tx = Pos.x(t) + 0.5, tz = Pos.z(t) + 0.5;
        float want = (float) Math.toDegrees(Math.atan2(-(tx - pos.x), tz - pos.z));
        float diff = Mth.wrapDegrees(want - boat.getYRot());
        // A turns the boat left (its yaw down), D right; paddle when roughly facing the way
        int strafe = diff > 6 ? -1 : diff < -6 ? 1 : 0;
        return new Controls(Math.abs(diff) < 50, false, false, false, want, 10f, strafe);
    }

    private Controls dismount(LocalPlayer p, float yaw0, float pitch0) {
        if (!p.isPassenger()) {
            phase = settings.boatPickup.get() && boat != null && !boat.isRemoved() ? Phase.BREAK : Phase.IDLE;
            ticks = 0;
            if (phase == Phase.IDLE) finish();
            return still(yaw0, pitch0);
        }
        if (ticks > DISMOUNT_TICKS) {
            p.stopRiding();
        }
        return new Controls(false, false, false, false, yaw0, pitch0, 0, true); // sneak gets out
    }

    private Controls breakBoat(Minecraft mc, LocalPlayer p, float yaw0, float pitch0) {
        if (boat == null || boat.isRemoved() || ticks > BREAK_TICKS || p.distanceTo(boat) > 4.5) {
            finish();
            return still(yaw0, pitch0);
        }
        float[] aim = angles(p, boat.position().add(0, 0.3, 0));
        boolean onIt = Math.abs(Mth.wrapDegrees(aim[0] - RotationDriver.yaw(p))) < 8 && Math.abs(aim[1] - RotationDriver.pitch(p)) < 8;
        if (onIt && ticks % 5 == 0) {
            mc.gameMode.attack(p, boat);
            p.swing(InteractionHand.MAIN_HAND);
        }
        return new Controls(false, false, false, false, aim[0], aim[1]);
    }

    private void finish() {
        skipUntil = run.isEmpty() ? Long.MIN_VALUE : run.get(run.size() - 1);
        phase = Phase.IDLE;
        boat = null;
    }

    /** Swims this one instead. */
    private Controls giveUp() {
        finish();
        return null;
    }

    private static Controls still(float yaw, float pitch) {
        return new Controls(false, false, false, false, yaw, pitch);
    }

    /** The nearest empty boat within a few blocks that wasn't there before placing, or null. */
    private AbstractBoat newBoat(Minecraft mc, LocalPlayer p) {
        AbstractBoat best = null;
        double bd = 6 * 6;
        for (AbstractBoat b : mc.level.getEntitiesOfClass(AbstractBoat.class, p.getBoundingBox().inflate(6))) {
            if (b.isVehicle() || before.contains(b.getId())) continue;
            double d = b.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** Slot of a boat or raft (hotbar first; {@code limit} 36 also looks in the rest of the inventory), or -1. */
    private static int boatSlot(LocalPlayer p, int limit) {
        for (int i = 0; i < limit; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (id.endsWith("_boat") || id.endsWith("_raft")) return i;
        }
        return -1;
    }

    private static float[] angles(LocalPlayer p, Vec3 at) {
        double dx = at.x - p.getX(), dy = at.y - p.getEyeY(), dz = at.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return new float[]{yaw, pitch};
    }

    private boolean swims(long n) {
        return Standing.swimmable(world, Pos.x(n), Pos.y(n), Pos.z(n));
    }

    private static double sq(double d) {
        return d * d;
    }
}
