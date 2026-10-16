package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.mixin.FishingHookAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #fish [minutes]: walks to a dry spot beside open water, casts, reels in a few ticks after a bite (the server tells the
 * client when one's on the line), and casts again, until stopped or the time's up. A cast that lands out of the water
 * or sits too long is reeled in and tried again; a spot that keeps failing is swapped for another.
 */
public final class FishProcess implements Process {
    private static final int SEARCH = 16;
    /** Reel in after this long with no bite (open sky, rain and Lure all change the wait; vanilla's longest is ~30s). */
    private static final int NO_BITE = 20 * 45;
    private static final int MISSES_PER_SPOT = 3;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private final Set<BlockPos> badSpots = new HashSet<>();
    private boolean active;
    private int endTick, castTick, reelAt, waitUntil, catches, misses;
    private BlockPos water, stand;

    public FishProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    /** @param minutes how long (0: until stopped) */
    public void start(Minecraft mc, int minutes) {
        stop(false);
        LocalPlayer p = mc.player;
        if (!Hands.has(p, this::rod)) {
            chat.accept(Chat.err("No fishing rod"));
            return;
        }
        endTick = minutes > 0 ? p.tickCount + minutes * 60 * 20 : Integer.MAX_VALUE;
        catches = misses = 0;
        reelAt = -1;
        waitUntil = 0;
        water = stand = null;
        badSpots.clear();
        trip.clear();
        active = true;
        chat.accept(Chat.info("Fishing{}", minutes > 0 ? " for " + minutes + " min" : ""));
    }

    @Override
    public boolean active() {
        return active;
    }

    @Override
    public String status() {
        return active ? "Fishing (" + catches + " caught)" : null;
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        end(announce ? Chat.warn("Stopped fishing ({} caught)", catches) : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        if (p.tickCount >= endTick) {
            end(Chat.ok("Done fishing: {} caught", catches));
            return;
        }
        if (!Hands.has(p, this::rod)) {
            end(Chat.err("No fishing rod left ({} caught)", catches));
            return;
        }
        if (trip.running()) return;
        if (stand == null || !p.blockPosition().equals(stand)) {
            if (stand != null && trip.ended() == PathingSession.State.ARRIVED && p.blockPosition().distSqr(stand) <= 2) {
                stand = p.blockPosition(); // close enough
            } else {
                if (stand != null && trip.ended() != null) badSpots.add(stand);
                if (!findSpot(mc, p)) {
                    end(Chat.err("No open water to fish in around here"));
                    return;
                }
                trip.go(new GoalBlock(stand.getX(), stand.getY(), stand.getZ()), p, false);
                return;
            }
        }
        if (!Hands.hold(p, ClickDriver.get(), this::rod)) return;
        FishingHook hook = p.fishing;
        if (hook == null) {
            reelAt = -1;
            if (p.tickCount < waitUntil) return;
            float[] aim = lookAt(p, Vec3.atCenterOf(water).add(0, 0.4, 0));
            Aim.turn(mc, p, input, aim, settings);
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(dev.lazuli.mod.control.RotationDriver.yaw(p) - aim[0])) < 2
                && Math.abs(dev.lazuli.mod.control.RotationDriver.pitch(p) - aim[1]) < 2) {
                use(mc, p);
                castTick = p.tickCount;
            }
            return;
        }
        input.release();
        int age = p.tickCount - castTick;
        if (reelAt < 0 && ((FishingHookAccessor) hook).lazuli$biting()) {
            reelAt = p.tickCount + 3 + p.getRandom().nextInt(5); // a human's reaction, not the same tick
        }
        if (reelAt >= 0 && p.tickCount >= reelAt) {
            use(mc, p);
            catches++;
            misses = 0;
            waitUntil = p.tickCount + 10 + p.getRandom().nextInt(10);
            return;
        }
        boolean landed = age > 40 && !hook.isInWater() && hook.getDeltaMovement().lengthSqr() < 1e-3;
        if (landed || age > NO_BITE) {
            use(mc, p); // reel in and try again
            waitUntil = p.tickCount + 10;
            if (landed && ++misses >= MISSES_PER_SPOT) {
                badSpots.add(stand);
                stand = null;
                misses = 0;
            }
        }
    }

    private static void use(Minecraft mc, LocalPlayer p) {
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
    }

    /** A fishing rod (not on its last points of durability, with saveTools). */
    private boolean rod(ItemStack s) {
        if (!(s.getItem() instanceof FishingRodItem)) return false;
        return !settings.saveTools.get() || !s.isDamageableItem() || s.getMaxDamage() - s.getDamageValue() > 2;
    }

    /**
     * The nearest dry block to stand on, 2 to 4 blocks from water with open water all around it (so the bobber lands
     * in it), the water in sight from there.
     */
    private boolean findSpot(Minecraft mc, LocalPlayer p) {
        Level level = mc.level;
        BlockPos me = p.blockPosition();
        double best = Double.MAX_VALUE;
        BlockPos bestStand = null, bestWater = null;
        for (BlockPos w : BlockPos.betweenClosed(me.offset(-SEARCH, -6, -SEARCH), me.offset(SEARCH, 3, SEARCH))) {
            if (!openWater(level, w)) continue;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 < 4 || d2 > 16) continue;
                    for (int dy = 0; dy <= 2; dy++) {
                        BlockPos s = w.offset(dx, dy + 1, dz);
                        if (badSpots.contains(s) || !standable(level, s)) continue;
                        double cost = s.distSqr(me);
                        if (cost >= best) continue;
                        Vec3 eye = Vec3.atBottomCenterOf(s).add(0, p.getEyeHeight(), 0);
                        var hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(w).add(0, 0.45, 0), ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.NONE, p));
                        if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(w)) continue;
                        best = cost;
                        bestStand = s.immutable();
                        bestWater = w.immutable();
                    }
                }
            }
        }
        stand = bestStand;
        water = bestWater;
        return stand != null;
    }

    /** Water with air above, and water all around it at the same level. */
    private static boolean openWater(Level level, BlockPos w) {
        if (!level.getFluidState(w).is(FluidTags.WATER) || !level.getBlockState(w.above()).isAir()) return false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if (!level.getFluidState(w.offset(dx, 0, dz)).is(FluidTags.WATER)) return false;
        return true;
    }

    /** Dry ground with room for the player. */
    private static boolean standable(Level level, BlockPos s) {
        BlockPos below = s.below();
        return level.getBlockState(s).getCollisionShape(level, s).isEmpty() && level.getFluidState(s).isEmpty()
            && level.getBlockState(s.above()).getCollisionShape(level, s.above()).isEmpty()
            && level.getBlockState(below).isFaceSturdy(level, below, net.minecraft.core.Direction.UP)
            && level.getFluidState(below).isEmpty();
    }

    private static float[] lookAt(LocalPlayer p, Vec3 at) {
        double dx = at.x - p.getX(), dy = at.y - p.getEyeY(), dz = at.z - p.getZ();
        return new float[]{(float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))};
    }

    private void end(String msg) {
        active = false;
        trip.cancel();
        input.release();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.fishing != null) use(mc, mc.player); // reel in
        if (msg != null) chat.accept(msg);
    }
}
