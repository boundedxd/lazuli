package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.move.Boating;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.physics.BoatSim;
import dev.lazuli.core.race.IceRacer;
import dev.lazuli.core.race.RaceLine;
import dev.lazuli.core.race.TrackField;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.mixin.AbstractBoatAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Crosses long stretches of water on the path by boat: places one on the water, gets in, drives it to the far side,
 * gets out and breaks the boat to take it along. Gives up (and swims) when a step doesn't work out.
 * <p>
 * Driving is {@link IceRacer}'s look-ahead search over the game's own boat physics, along a water-only
 * {@link TrackField} to the landing: it knows how the boat drifts and spins and that keys reach it a tick late, so it
 * doesn't orbit a point it can't turn tightly enough for, or ram the shore.
 * <p>
 * With lag the boat shows up a while after the click: a click the game took (a boat gone from the hand) isn't
 * repeated, it waits for the boat. Clicks on the water and on the boat are made only when the look actually hits them.
 */
final class BoatRider {
    private enum Phase { IDLE, PLACE, MOUNT, RIDE, DISMOUNT, BREAK }

    private static final double WATER_TOP = 8.0 / 9.0;
    /** Ticks each step may take before the boat is given up on (waiting on a placed boat: lag, so longer). */
    private static final int PLACE_TICKS = 40, SPAWN_TICKS = 100, SURFACE_TICKS = 100, MOUNT_TICKS = 80, DISMOUNT_TICKS = 20,
        BREAK_TICKS = 60;
    /** Ticks of hardly moving while riding before it's taken as stuck. */
    private static final int STUCK_TICKS = 60;
    /** Cells of water the crossing may take in; how near the landing (blocks) counts as there. */
    private static final int FIELD_CELLS = 120_000;
    private static final double LANDING_RADIUS = 2;
    /** How far a boat can be put down (block reach, with a little to spare); how far it may be to get in. */
    private static final double PLACE_REACH = 4.3, MOUNT_REACH = 2.6;
    /** The racer's search per tick: runs kept, time. Water is slow and open, so a small search does. */
    private static final int BEAM = 24;
    private static final long BUDGET_NANOS = 6_000_000L;

    private final BlockView world;
    private final Settings settings;
    private Phase phase = Phase.IDLE;
    private AbstractBoat boat;
    /** The water nodes being crossed (path positions) and the node to land on. */
    private List<Long> run = List.of();
    private long landing;
    private int ticks, slow, usedAt, surfacing;
    /** Boats in the inventory when the last click went out: fewer since means the game took it. */
    private int boatsAtClick = -1;
    /** Boats that were around before placing ours: never taken for the one just placed. */
    private final java.util.Set<Integer> before = new java.util.HashSet<>();
    private long spot;
    /** The last node of a crossing given up on or done: not tried again from there. */
    private long skipUntil = Long.MIN_VALUE;

    private IceRacer racer;
    private TrackField field;
    private RaceLine.Cursor cursor;
    private CompletableFuture<TrackField> building;
    private int builtAt;

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
        dropField();
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
        // a boat only goes down with water (or air) all around it, near enough to get in: none yet, maybe a bit further on
        long s = placeSpot(p, Pos.y(path.get(first)));
        if (s == Long.MIN_VALUE) return false;
        run = List.copyOf(path.subList(first, end + 1));
        landing = path.get(end + 1);
        spot = s;
        ticks = slow = 0;
        usedAt = -100;
        boatsAtClick = -1;
        surfacing = 0;
        boat = null;
        dropField();
        before.clear();
        for (AbstractBoat b : Minecraft.getInstance().level.getEntitiesOfClass(AbstractBoat.class, p.getBoundingBox().inflate(16))) {
            before.add(b.getId());
        }
        phase = Phase.PLACE;
        return true;
    }

    private Controls place(Minecraft mc, LocalPlayer p, float yaw0) {
        // the boat this placed: any new one near (with lag it shows up a good while after the click)
        AbstractBoat b = newBoat(mc, p);
        if (b != null) {
            boat = b;
            phase = Phase.MOUNT;
            ticks = 0;
            return still(yaw0, p.getXRot());
        }
        boolean taken = boatsAtClick >= 0 && boats(p) < boatsAtClick;
        if (taken) {
            // the game took the click (a boat left the hand): wait for it to turn up, never put down a second one
            return ticks - usedAt > SPAWN_TICKS ? giveUp() : still(yaw0, p.getXRot());
        }
        if (ticks - surfacing > PLACE_TICKS || surfacing > SURFACE_TICKS) return giveUp();
        int slot = boatSlot(p, settings.allowInventory.get() && mc.screen == null ? 36 : 9);
        if (slot < 0) return giveUp();
        if (slot >= 9) {
            // into the selected hotbar slot first (the click driver may stop and open the inventory to do it)
            ClickDriver clicks = ClickDriver.get();
            if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, p.getInventory().getSelectedSlot())));
            return still(yaw0, p.getXRot());
        }
        p.getInventory().setSelectedSlot(slot);
        // on a water block with room all around (the boat sticks out into the blocks beside it), aiming at its surface
        Vec3 at = new Vec3(Pos.x(spot) + 0.5, Pos.y(spot) + WATER_TOP, Pos.z(spot) + 0.5);
        float[] aim = angles(p, at);
        // a boat can't be put down from under the water: swim up until the eyes are above its surface (and stay up)
        boolean under = p.isUnderWater() || p.getEyeY() < at.y + 0.05;
        if (under) surfacing++;
        // only when the look really lands on that water (the game places the boat where the look hits it); then time
        // for the boat to appear (creative keeps the boat in hand: no telling, so a pause before trying again)
        if (!under && looksAtWater(mc, p) && ticks - usedAt > 12) {
            boatsAtClick = boats(p);
            useAsServerSees(p, () -> mc.gameMode.useItem(p, InteractionHand.MAIN_HAND));
            usedAt = ticks;
        }
        return new Controls(false, false, p.isInWater(), false, aim[0], aim[1]);
    }

    /** The look (as the server has it) hits the surface of the chosen water within reach. */
    private boolean looksAtWater(Minecraft mc, LocalPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(RotationDriver.pitch(p), RotationDriver.yaw(p));
        HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE,
            ClipContext.Fluid.ANY, p));
        if (!(h instanceof BlockHitResult bh) || bh.getType() != HitResult.Type.BLOCK) return false;
        BlockPos hp = bh.getBlockPos();
        return hp.getX() == Pos.x(spot) && hp.getY() == Pos.y(spot) && hp.getZ() == Pos.z(spot)
            && Math.abs(bh.getLocation().x - (Pos.x(spot) + 0.5)) < 0.3 && Math.abs(bh.getLocation().z - (Pos.z(spot) + 0.5)) < 0.3;
    }

    private Controls mount(Minecraft mc, LocalPlayer p, float yaw0, float pitch0) {
        if (p.getVehicle() == boat) {
            phase = Phase.RIDE;
            ticks = 0;
            return still(yaw0, pitch0);
        }
        if (ticks > MOUNT_TICKS || boat.isRemoved()) return giveUp();
        AABB box = boat.getBoundingBox();
        float[] aim = angles(p, box.getCenter());
        // in reach: right-click it the way the game does, only while the look is really on it; else get closer
        boolean near = p.isWithinEntityInteractionRange(boat, -0.3);
        Vec3 eye = p.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(RotationDriver.pitch(p), RotationDriver.yaw(p));
        Optional<Vec3> hit = box.clip(eye, eye.add(dir.scale(p.entityInteractionRange())));
        if (near && hit.isPresent() && ticks % 4 == 0 && !p.isShiftKeyDown()) {
            EntityHitResult ehr = new EntityHitResult(boat, hit.get());
            useAsServerSees(p, () -> {
                var r = mc.gameMode.interactAt(p, boat, ehr, InteractionHand.MAIN_HAND);
                if (!r.consumesAction()) r = mc.gameMode.interact(p, boat, InteractionHand.MAIN_HAND);
                if (r.consumesAction()) p.swing(InteractionHand.MAIN_HAND);
            });
        }
        // swimming up to it, keep the head up (and don't sneak: that's "don't get in")
        return new Controls(!near, false, p.isInWater(), false, aim[0], aim[1]);
    }

    private Controls ride(LocalPlayer p, float pitch0) {
        if (p.getVehicle() != boat) return giveUp();
        Vec3 pos = boat.position();
        double lx = Pos.x(landing) + 0.5, lz = Pos.z(landing) + 0.5;
        // the way over the water to the landing (worked out off the game thread; again if the boat ends up off it)
        if (building != null && building.isDone()) {
            field = building.getNow(null);
            building = null;
            if (field == null) {
                phase = Phase.DISMOUNT; // no way across by water from here: out, and swim it
                ticks = 0;
                return still(p.getYRot(), pitch0);
            }
            cursor = field.start(pos.x, pos.y, pos.z);
        }
        if (field == null && building == null) {
            double x = pos.x, z = pos.z;
            int by = Pos.y(run.get(0)) + 1, gx = Pos.x(landing), gz = Pos.z(landing);
            building = CompletableFuture.supplyAsync(() -> {
                // from the cell under the boat's middle, else one its sides are over (its middle just over the shore)
                for (int i = 0; i < 9; i++) {
                    int bx = Mth.floor(x + (i % 3 - 1) * 0.6 * Math.signum(i)), bz = Mth.floor(z + (i / 3 - 1) * 0.6 * Math.signum(i));
                    TrackField f = TrackField.water(world, bx, by, bz, gx, gz, LANDING_RADIUS, FIELD_CELLS);
                    if (f != null) return f;
                }
                return null;
            });
            builtAt = ticks;
            if (racer == null) racer = new IceRacer(world).beam(BEAM).budget(BUDGET_NANOS);
        }
        Vec3 v = boat.getDeltaMovement();
        double speed2 = v.x * v.x + v.z * v.z;
        slow = speed2 < 0.02 * 0.02 && ticks > 20 ? slow + 1 : 0;
        double toGo = field == null ? Double.NaN : field.toGo(cursor, pos.x, pos.y, pos.z);
        boolean there = sq(lx - pos.x) + sq(lz - pos.z) < 1.9 * 1.9;
        if (there || !Double.isNaN(toGo) && field.finished(cursor, toGo) || slow > STUCK_TICKS) {
            phase = Phase.DISMOUNT; // there (or stuck on something: get out and carry on from here)
            ticks = 0;
            return still(p.getYRot(), pitch0);
        }
        if (field == null) return new Controls(false, false, false, false, boat.getYRot(), 10f); // working it out
        if (Double.isNaN(toGo) && ticks - builtAt > 20 && building == null) {
            field = null; // pushed off the water it knows: work it out again from here
            return new Controls(false, false, false, false, boat.getYRot(), 10f);
        }
        if (ticks % 40 == 0) racer.reset(); // blocks change (a lily pad broken): look them up again now and then
        AbstractBoatAccessor acc = (AbstractBoatAccessor) boat;
        BoatSim sim = new BoatSim(racer.view(), pos.x, pos.y, pos.z, v.x, v.y, v.z, boat.getYRot(), acc.lazuli$spin());
        IceRacer.Keys pending = new IceRacer.Keys(acc.lazuli$left(), acc.lazuli$right(), acc.lazuli$forward(), acc.lazuli$back());
        IceRacer.Keys k = racer.next(sim, pending, field, cursor);
        int strafe = k.left() ? 1 : k.right() ? -1 : 0;
        // look the way the boat's going
        return new Controls(k.forward(), k.back(), false, false, boat.getYRot(), 10f, strafe);
    }

    /**
     * The water cell (level y) nearest the player with room for a boat all around, where the click reaches its surface
     * and the boat that turns up there is near enough to get in without walking (walking up pushes it away); or MIN_VALUE.
     */
    private long placeSpot(LocalPlayer p, int y) {
        long best = Long.MIN_VALUE;
        double bd = Double.MAX_VALUE;
        Vec3 eye = p.getEyePosition();
        int px = Mth.floor(p.getX()), pz = Mth.floor(p.getZ());
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int x = px + dx, z = pz + dz;
                double d = eye.distanceToSqr(x + 0.5, y + WATER_TOP, z + 0.5);
                if (d > PLACE_REACH * PLACE_REACH || d >= bd || !Boating.fits(world, x, y, z)) continue;
                AABB boatBox = new AABB(x + 0.5 - BoatSim.HALF_WIDTH, y + 0.5, z + 0.5 - BoatSim.HALF_WIDTH,
                    x + 0.5 + BoatSim.HALF_WIDTH, y + 0.5 + BoatSim.HEIGHT, z + 0.5 + BoatSim.HALF_WIDTH);
                if (boatBox.distanceToSqr(eye) > MOUNT_REACH * MOUNT_REACH) continue;
                bd = d;
                best = Pos.pack(x, y, z);
            }
        }
        return best;
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
        dropField();
    }

    private void dropField() {
        if (building != null) building.cancel(true);
        building = null;
        field = null;
        cursor = null;
        racer = null;
    }

    /** Swims this one instead. */
    private Controls giveUp() {
        finish();
        return null;
    }

    private static Controls still(float yaw, float pitch) {
        return new Controls(false, false, false, false, yaw, pitch);
    }

    /**
     * Runs a click with the player turned the way the server sees it (with free look the camera's is another): using
     * an item sends the player's rotation along, and the game aims it with that.
     */
    private static void useAsServerSees(LocalPlayer p, Runnable click) {
        float y = p.getYRot(), x = p.getXRot();
        p.setYRot(RotationDriver.yaw(p));
        p.setXRot(RotationDriver.pitch(p));
        try {
            click.run();
        } finally {
            p.setYRot(y);
            p.setXRot(x);
        }
    }

    /** The nearest empty boat within a few blocks that wasn't there before placing, or null. */
    private AbstractBoat newBoat(Minecraft mc, LocalPlayer p) {
        AbstractBoat best = null;
        double bd = 8 * 8;
        for (AbstractBoat b : mc.level.getEntitiesOfClass(AbstractBoat.class, p.getBoundingBox().inflate(8))) {
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
        for (int i = 0; i < limit; i++) if (isBoat(p.getInventory().getItem(i))) return i;
        return -1;
    }

    /** Boats and rafts in the whole inventory. */
    private static int boats(LocalPlayer p) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (isBoat(s)) n += s.getCount();
        }
        return n;
    }

    private static boolean isBoat(ItemStack s) {
        if (s.isEmpty()) return false;
        String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
        return id.endsWith("_boat") || id.endsWith("_raft");
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
