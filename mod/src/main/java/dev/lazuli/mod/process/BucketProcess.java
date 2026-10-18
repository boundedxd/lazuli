package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.Melee;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import dev.lazuli.mod.world.BlockSearch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #getitem's bucket steps: fills empty buckets from a water or lava source or from a cow (milk), and casts obsidian:
 * pours water into the edge of a lava pool (the water turns the lava next to it to obsidian), mines what forms with a
 * diamond pickaxe, picks it up and scoops the water back. None around: it wanders looking for some.
 */
public final class BucketProcess implements Process {
    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private static final int WANDER_MAX = 8, TARGET_TICKS = 20 * 40, TOTAL_TICKS = 20 * 60 * 6, USE_GAP = 5;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private boolean active;
    private Outcome outcome = Outcome.NONE;
    private String item;
    private int want, startTick, wanders, targetSince, usedAt, idle, closeTries;
    private BlockPos target, goal, waterAt, lastWater, pourFrom, poured;
    private int pouredTick, lastHave;
    private Direction pourFace;
    private Animal cow;
    private boolean wandering;
    private final Set<BlockPos> skip = new HashSet<>();

    public BucketProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    /** @param item water_bucket, lava_bucket, milk_bucket (from empty buckets) or obsidian (cast); {@code n} more of it */
    public void start(Minecraft mc, String item, int n) {
        stop(false);
        LocalPlayer p = mc.player;
        outcome = Outcome.NONE;
        this.item = item;
        want = have(p) + Math.max(1, n);
        lastHave = have(p);
        startTick = p.tickCount;
        wanders = 0;
        target = goal = waterAt = lastWater = pourFrom = poured = null;
        pourFace = null;
        cow = null;
        wandering = false;
        idle = closeTries = 0;
        usedAt = -100;
        skip.clear();
        trip.clear();
        if (!settings.getItemBuckets.get()) {
            fail("Buckets are off (getItemBuckets)");
            return;
        }
        boolean cast = isCast();
        if (cast ? !Hands.has(p, s -> s.is(Items.WATER_BUCKET)) : !Hands.has(p, s -> s.is(Items.BUCKET))) {
            fail(cast ? "Needs a water bucket to cast obsidian" : "Needs an empty bucket");
            return;
        }
        if ((cast || item.equals("minecraft:water_bucket")) && mc.level.dimension() == net.minecraft.world.level.Level.NETHER) {
            fail("Water evaporates in this dimension");
            return;
        }
        active = true;
        chat.accept(Chat.info(cast ? "Casting {} obsidian" : "Filling {} {}", Math.max(1, n), cast ? "" : name(item)));
    }

    public Outcome outcome() {
        return outcome;
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        if (!active) return null;
        return isCast() ? "Casting obsidian" + (waterAt != null ? "  (water down)" : "") : "Filling " + name(item);
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (p.containerMenu != p.inventoryMenu) return;
        if (p.tickCount - startTick > TOTAL_TICKS) {
            fail("Took too long");
            return;
        }
        int now = have(p);
        if (now != lastHave) {
            lastHave = now;
            closeTries = 0;
        }
        switch (item) {
            case "minecraft:water_bucket" -> fluidTick(mc, p, Fluids.WATER);
            case "minecraft:lava_bucket" -> fluidTick(mc, p, Fluids.LAVA);
            case "minecraft:milk_bucket" -> milkTick(mc, p);
            default -> castTick(mc, p);
        }
    }

    // ---- filling from a pool ----

    private void fluidTick(Minecraft mc, LocalPlayer p, Fluid f) {
        if (have(p) >= want) {
            end(Outcome.DONE, Chat.ok("Filled {}", name(item)));
            return;
        }
        if (target != null && (skip.contains(target) || !source(mc.level.getBlockState(target), f))) target = null;
        if (target == null && p.tickCount % 10 == 0) {
            target = nearestSource(mc, p, f, p.blockPosition(), settings.bucketRange.get());
            targetSince = p.tickCount;
            closeTries = 0;
            if (target != null && wandering) {
                wandering = false;
                trip.cancel();
            }
        }
        if (target == null) {
            lookElsewhere(p, f == Fluids.WATER ? "water" : "lava");
            return;
        }
        if (p.tickCount - targetSince > TARGET_TICKS) {
            skip.add(target);
            target = null;
            return;
        }
        if (reach(p, target)) {
            if (trip.running()) trip.cancel();
            if (!Hands.hold(p, ClickDriver.get(), s -> s.is(Items.BUCKET))) {
                if (!Hands.has(p, s -> s.is(Items.BUCKET))) fail("Ran out of empty buckets");
                return;
            }
            if (scoop(mc, p, target) && ++closeTries > 6) { // clicked a few times and still empty: not a real source
                skip.add(target);
                target = null;
            }
        } else {
            input.release();
            goNear(p, target, 3);
        }
    }

    /** One tick of looking at the source and clicking with the empty bucket; true when it clicked. */
    private boolean scoop(Minecraft mc, LocalPlayer p, BlockPos at) {
        Vec3 c = Vec3.atCenterOf(at);
        turnTo(mc, p, c);
        BlockHitResult h = view(mc, p, ClipContext.Fluid.SOURCE_ONLY);
        if (h == null || !h.getBlockPos().equals(at) || p.tickCount - usedAt < USE_GAP) return false;
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
        usedAt = p.tickCount;
        return true;
    }

    private BlockPos nearestSource(Minecraft mc, LocalPlayer p, Fluid f, BlockPos around, int r) {
        BlockPos[] best = {null};
        double[] bestD = {Double.MAX_VALUE};
        BlockSearch.forEach(mc.level, around.getX() - r, around.getY() - 24, around.getZ() - r, around.getX() + r, around.getY() + 24,
            around.getZ() + r, s -> source(s, f), at -> {
                double d = at.distSqr(p.blockPosition());
                if (d < bestD[0] && !skip.contains(at)) {
                    bestD[0] = d;
                    best[0] = at.immutable();
                }
            });
        return best[0];
    }

    // ---- milk ----

    private void milkTick(Minecraft mc, LocalPlayer p) {
        if (have(p) >= want) {
            end(Outcome.DONE, Chat.ok("Milked into {}", name(item)));
            return;
        }
        if (cow != null && (!cow.isAlive() || cow.isRemoved())) cow = null;
        if (cow == null && p.tickCount % 10 == 0) {
            cow = nearestCow(mc, p);
            targetSince = p.tickCount;
            if (cow != null && wandering) {
                wandering = false;
                trip.cancel();
            }
        }
        if (cow == null) {
            lookElsewhere(p, "cows");
            return;
        }
        if (p.tickCount - targetSince > TARGET_TICKS) {
            skip.add(cow.blockPosition());
            cow = null;
            return;
        }
        double dist = p.distanceTo(cow);
        if (dist > Melee.REACH + 0.5 || !p.hasLineOfSight(cow)) {
            input.release();
            goNear(p, cow.blockPosition(), 2);
            return;
        }
        if (trip.running()) trip.cancel();
        if (!Hands.hold(p, ClickDriver.get(), s -> s.is(Items.BUCKET))) {
            if (!Hands.has(p, s -> s.is(Items.BUCKET))) fail("Ran out of empty buckets");
            return;
        }
        if (Melee.face(mc, p, cow, input, settings, 2.0) && dist <= Melee.REACH && p.tickCount - usedAt >= USE_GAP) {
            mc.gameMode.interact(p, cow, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            usedAt = p.tickCount;
            input.release();
        }
    }

    private Animal nearestCow(Minecraft mc, LocalPlayer p) {
        double r = settings.bucketRange.get();
        Animal best = null;
        for (Animal a : mc.level.getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(r), e -> e.isAlive() && !e.isBaby())) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(a.getType()).getPath();
            if (!id.equals("cow") && !id.equals("mooshroom") || skip.contains(a.blockPosition())) continue;
            if (best == null || p.distanceToSqr(a) < p.distanceToSqr(best)) best = a;
        }
        return best;
    }

    // ---- obsidian ----

    private void castTick(Minecraft mc, LocalPlayer p) {
        if (have(p) >= want) {
            if (waterAt == null) {
                end(Outcome.DONE, Chat.ok("Cast {} obsidian", want));
                return;
            }
            scoopWater(mc, p);
            return;
        }
        if (waterAt != null && !source(mc.level.getBlockState(waterAt), Fluids.WATER)) waterAt = null; // gone (scooped, evaporated)
        // what's formed: next to the water that was poured, mined (not obsidian that was there before: portals...)
        BlockPos ob = lastWater == null ? null : nearestObsidian(mc, p);
        if (ob != null) {
            idle = 0;
            if (reach(p, ob)) {
                if (trip.running()) trip.cancel();
                input.release();
                Aim.Result r = Aim.breakTick(mc, p, ob, input, settings);
                if (r == Aim.Result.BLOCKED) skip.add(ob);
            } else {
                goNear(p, ob, 2);
            }
            return;
        }
        ItemEntity drop = lastWater == null ? null : nearestDrop(mc, p);
        if (drop != null && p.tickCount - usedAt > 0) {
            idle = 0;
            goNear(p, drop.blockPosition(), 1);
            return;
        }
        if (waterAt != null) {
            // water's down and nothing is left to mine: give it a moment (it spreads slowly), then take it back
            // and pour somewhere else
            if (++idle > 60) scoopWater(mc, p);
            return;
        }
        pour(mc, p);
    }

    /** Pours the water bucket into a lava source at the pool's edge. */
    private void pour(Minecraft mc, LocalPlayer p) {
        if (!Hands.hold(p, ClickDriver.get(), s -> s.is(Items.WATER_BUCKET))) {
            if (!Hands.has(p, s -> s.is(Items.WATER_BUCKET))) fail("Lost the water bucket");
            return;
        }
        if (poured != null) { // clicked: the water shows up a tick or two later
            if (source(mc.level.getBlockState(poured), Fluids.WATER)) {
                waterAt = lastWater = poured;
                poured = null;
                target = null;
                pourFrom = null;
                idle = 0;
            } else if (p.tickCount - pouredTick > 15) {
                poured = null;
                if (Hands.has(p, s -> s.is(Items.BUCKET)) && !Hands.has(p, s -> s.is(Items.WATER_BUCKET))) {
                    fail("The water didn't stay (evaporated?)");
                } else if (target != null) {
                    skip.add(target);
                    target = null;
                    pourFrom = null;
                }
            }
            return;
        }
        if (target != null && (skip.contains(target) || !source(mc.level.getBlockState(target), Fluids.LAVA))) {
            target = null;
            pourFrom = null;
        }
        if (target == null && p.tickCount % 10 == 0) {
            target = nearestSource(mc, p, Fluids.LAVA, p.blockPosition(), settings.bucketRange.get());
            targetSince = p.tickCount;
            closeTries = 0;
            pourFrom = null;
            if (target != null && wandering) {
                wandering = false;
                trip.cancel();
            }
        }
        if (target == null) {
            lookElsewhere(p, "lava");
            return;
        }
        if (p.tickCount - targetSince > TARGET_TICKS) {
            skip.add(target);
            target = null;
            return;
        }
        if (pourFrom == null && p.getEyePosition().distanceTo(Vec3.atCenterOf(target)) <= p.blockInteractionRange() + 1) {
            // a solid block next to the lava to click: the bucket's own ray runs through it and the water goes in the lava
            for (Direction d : Direction.values()) {
                BlockPos b = target.relative(d);
                BlockState s = mc.level.getBlockState(b);
                if (s.isAir() || !s.getFluidState().isEmpty() || s.getCollisionShape(mc.level, b).isEmpty()) continue;
                if (Aim.at(mc, p, b, d.getOpposite()) != null) {
                    pourFrom = b;
                    pourFace = d.getOpposite();
                    break;
                }
            }
            if (pourFrom == null && ++closeTries > 40) { // can't be clicked from anywhere it can stand
                skip.add(target);
                target = null;
                return;
            }
        }
        if (pourFrom == null) {
            input.release();
            goNear(p, target, closeTries > 10 ? 2 : 3);
            return;
        }
        if (trip.running()) trip.cancel();
        float[] aim = Aim.at(mc, p, pourFrom, pourFace);
        if (aim == null) { // moved off: look again
            pourFrom = null;
            return;
        }
        Aim.turn(mc, p, input, aim, settings);
        BlockHitResult h = view(mc, p, ClipContext.Fluid.NONE);
        if (h != null && h.getBlockPos().equals(pourFrom) && h.getDirection() == pourFace && p.tickCount - usedAt >= USE_GAP) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            usedAt = p.tickCount;
            poured = target;
            pouredTick = p.tickCount;
        }
    }

    /** Takes the poured water back with the empty bucket. */
    private void scoopWater(Minecraft mc, LocalPlayer p) {
        if (waterAt == null) return;
        if (Hands.has(p, s -> s.is(Items.WATER_BUCKET))) { // the bucket was filled again already
            waterAt = null;
            return;
        }
        if (reach(p, waterAt)) {
            if (trip.running()) trip.cancel();
            if (!Hands.hold(p, ClickDriver.get(), s -> s.is(Items.BUCKET))) {
                if (!Hands.has(p, s -> s.is(Items.BUCKET))) fail("Lost the bucket");
                return;
            }
            scoop(mc, p, waterAt);
            BlockPos block = blockerOf(mc, p, waterAt);
            if (block != null) Aim.breakTick(mc, p, block, input, settings);
            if (++idle > 200) {
                waterAt = null;
                chat.accept(Chat.warn("Couldn't get the water back"));
            }
        } else {
            input.release();
            goNear(p, waterAt, 3);
        }
    }

    /** A block in the way of the view to {@code at} that can be mined (obsidian formed round the water), or null. */
    private BlockPos blockerOf(Minecraft mc, LocalPlayer p, BlockPos at) {
        BlockHitResult h = view(mc, p, ClipContext.Fluid.SOURCE_ONLY);
        if (h == null || h.getBlockPos().equals(at)) return null;
        return mc.level.getBlockState(h.getBlockPos()).is(Blocks.OBSIDIAN) ? h.getBlockPos() : null;
    }

    private BlockPos nearestObsidian(Minecraft mc, LocalPlayer p) {
        BlockPos z = waterAt != null ? waterAt : lastWater;
        BlockPos[] best = {null};
        double[] bestD = {Double.MAX_VALUE};
        BlockSearch.forEach(mc.level, z.getX() - 8, z.getY() - 6, z.getZ() - 8, z.getX() + 8, z.getY() + 6, z.getZ() + 8,
            s -> s.is(Blocks.OBSIDIAN), at -> {
                double d = at.distSqr(p.blockPosition());
                if (d >= bestD[0] || skip.contains(at)) return;
                for (Direction dir : Direction.values()) { // part of a portal: leave it
                    if (mc.level.getBlockState(at.relative(dir)).is(Blocks.NETHER_PORTAL)) return;
                }
                bestD[0] = d;
                best[0] = at.immutable();
            });
        return best[0];
    }

    private ItemEntity nearestDrop(Minecraft mc, LocalPlayer p) {
        ItemEntity best = null;
        for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(12), e -> e.getItem().is(Items.OBSIDIAN) && !e.isRemoved())) {
            if (skip.contains(e.blockPosition())) continue;
            if (best == null || p.distanceToSqr(e) < p.distanceToSqr(best)) best = e;
        }
        return best;
    }

    // ---- shared ----

    private void lookElsewhere(LocalPlayer p, String what) {
        if (trip.running()) return;
        if (++wanders > WANDER_MAX) {
            fail("Couldn't find any " + what + " within " + settings.bucketRange.get() + " blocks");
            return;
        }
        int[][] d = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int[] v = d[wanders % 4];
        int dist = settings.bucketRange.get();
        wandering = true;
        trip.go(new GoalXZ(p.getBlockX() + v[0] * dist, p.getBlockZ() + v[1] * dist), p, true);
        if (wanders == 1) chat.accept(Chat.info("No {} around: looking for some", what));
    }

    private void goNear(LocalPlayer p, BlockPos at, int r) {
        if (!trip.running() || goal == null || goal.distSqr(at) > 4) {
            goal = at;
            trip.go(new GoalNear(at.getX(), at.getY(), at.getZ(), r), p, true);
        }
    }

    private boolean reach(LocalPlayer p, BlockPos at) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(at)) <= p.blockInteractionRange() - 0.5;
    }

    private void turnTo(Minecraft mc, LocalPlayer p, Vec3 at) {
        Vec3 eye = p.getEyePosition();
        double dx = at.x - eye.x, dy = at.y - eye.y, dz = at.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        Aim.turn(mc, p, input, new float[]{yaw, pitch}, settings);
    }

    private static BlockHitResult view(Minecraft mc, LocalPlayer p, ClipContext.Fluid fluid) {
        Vec3 eye = p.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(RotationDriver.pitch(p), RotationDriver.yaw(p));
        HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(p.blockInteractionRange())), ClipContext.Block.OUTLINE, fluid, p));
        return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    private static boolean source(BlockState s, Fluid f) {
        var fs = s.getFluidState();
        return fs.isSource() && fs.is(f);
    }

    private boolean isCast() {
        return item.equals("minecraft:obsidian");
    }

    private int have(LocalPlayer p) {
        Item it = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(item));
        return Hands.count(p, (ItemStack s) -> s.is(it));
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err("{}", msg));
    }

    private void end(Outcome how, String msg) {
        active = false;
        outcome = how;
        trip.cancel();
        input.release();
        target = null;
        cow = null;
        if (msg != null) chat.accept(msg);
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
