package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.PathExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * #farm [range]: around where it started, harvests ripe crops (wheat, carrots, potatoes, beetroot, nether wart),
 * melons and pumpkins, and sugar cane / bamboo above the bottom block; replants empty farmland and soul sand from the
 * hotbar; picks up what drops. Runs until stopped, waiting when there's nothing to do. Never jumps on the way, so
 * farmland doesn't get trampled.
 */
public final class FarmProcess implements Process {
    private static final int RESCAN_TICKS = 40;
    private static final int BELOW = 4, ABOVE = 8;
    /** Ticks one job may take before it's set aside for a while. */
    private static final int JOB_TICKS = 400;
    private static final int SKIP_TICKS = 600;

    private enum Kind { HARVEST, PLANT, PICKUP }

    private record Job(Kind kind, BlockPos pos) {}

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private final List<Job> jobs = new ArrayList<>();
    /** Jobs that failed, and the tick until which they're left alone. */
    private final Map<BlockPos, Integer> skip = new HashMap<>();
    private boolean active, driving, idleSaid;
    private BlockPos center;
    private int range, lastScan, jobStart, harvested;
    private Job job;

    public FarmProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    @Override
    public boolean active() {
        return active;
    }

    /** @param range blocks around here to farm, or 0 for the farmRange setting */
    public void start(Minecraft mc, int range) {
        center = mc.player.blockPosition();
        this.range = range > 0 ? range : settings.farmRange.get();
        jobs.clear();
        skip.clear();
        job = null;
        harvested = 0;
        lastScan = -RESCAN_TICKS;
        idleSaid = false;
        active = true;
        PathExecutor.gentle = true;
        trip.clear();
        chat.accept(Chat.ok("Farming within {} blocks", this.range));
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        active = false;
        PathExecutor.gentle = false;
        release();
        trip.cancel();
        if (announce) chat.accept(Chat.warn("Stopped farming ({} harvested)", harvested));
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        LocalPlayer player = mc.player;
        if (player.tickCount - lastScan >= RESCAN_TICKS) scan(mc, player);
        if (job != null && (!stillToDo(mc, job) || player.tickCount - jobStart > JOB_TICKS)) {
            if (stillToDo(mc, job)) skip.put(job.pos(), player.tickCount + SKIP_TICKS);
            job = null;
            trip.cancel();
            release();
        }
        if (job == null) {
            job = next(mc, player);
            if (job == null) {
                release();
                if (!idleSaid) chat.accept(Chat.info("Nothing to farm right now; waiting"));
                idleSaid = true;
                return;
            }
            idleSaid = false;
            jobStart = player.tickCount;
            trip.clear();
        }
        work(mc, player);
    }

    private void work(Minecraft mc, LocalPlayer player) {
        switch (job.kind()) {
            case PICKUP -> {
                if (!trip.running()) trip.go(new GoalNear(job.pos().getX(), job.pos().getY(), job.pos().getZ(), 1), player);
                if (trip.ended() == PathingSession.State.FAILED) giveUp(player);
            }
            case HARVEST -> {
                if (trip.running()) return;
                Aim.Result r = Aim.breakTick(mc, player, job.pos(), input, settings);
                driving = true;
                if (r == Aim.Result.DONE) {
                    harvested++;
                    BlockState below = mc.level.getBlockState(job.pos().below());
                    job = below.is(Blocks.FARMLAND) || below.is(Blocks.SOUL_SAND) ? new Job(Kind.PLANT, job.pos().below()) : null;
                    jobStart = player.tickCount;
                    release();
                } else if (r == Aim.Result.BLOCKED) {
                    goNextTo(player, job.pos());
                }
            }
            case PLANT -> {
                if (trip.running()) return;
                int slot = seedSlot(player, mc.level.getBlockState(job.pos()).is(Blocks.SOUL_SAND));
                if (slot < 0) {
                    job = null; // nothing to plant here
                    release();
                    return;
                }
                player.getInventory().setSelectedSlot(slot);
                driving = true;
                if (Aim.useOn(mc, player, job.pos(), Direction.UP, input, settings)) {
                    job = null;
                    release();
                } else if (Aim.at(mc, player, job.pos(), Direction.UP) == null) {
                    goNextTo(player, job.pos().above());
                }
            }
        }
    }

    /** Walk next to {@code pos}; a second failed walk sets the job aside. */
    private void goNextTo(LocalPlayer player, BlockPos pos) {
        release();
        if (trip.ended() == PathingSession.State.FAILED || trip.ended() == PathingSession.State.ARRIVED) {
            giveUp(player);
            return;
        }
        trip.go(new GoalAdjacent(pos.getX(), pos.getY(), pos.getZ()), player);
    }

    private void giveUp(LocalPlayer player) {
        skip.put(job.pos(), player.tickCount + SKIP_TICKS);
        job = null;
        trip.clear();
        release();
    }

    /** The nearest job, not set aside. */
    private Job next(Minecraft mc, LocalPlayer player) {
        jobs.removeIf(j -> !stillToDo(mc, j));
        return jobs.stream().filter(j -> skip.getOrDefault(j.pos(), 0) <= player.tickCount)
            .min(Comparator.comparingDouble(j -> j.pos().distToCenterSqr(player.position()))).orElse(null);
    }

    private void scan(Minecraft mc, LocalPlayer player) {
        lastScan = player.tickCount;
        jobs.clear();
        boolean seeds = seedSlot(player, false) >= 0, wart = seedSlot(player, true) >= 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = center.getX() - range; x <= center.getX() + range; x++)
            for (int z = center.getZ() - range; z <= center.getZ() + range; z++)
                for (int y = center.getY() - BELOW; y <= center.getY() + ABOVE; y++) {
                    m.set(x, y, z);
                    BlockState s = mc.level.getBlockState(m);
                    if (s.isAir()) continue;
                    if (ripe(mc, m, s)) jobs.add(new Job(Kind.HARVEST, m.immutable()));
                    else if ((seeds && s.is(Blocks.FARMLAND) || wart && s.is(Blocks.SOUL_SAND)) && mc.level.getBlockState(m.above()).isAir()) {
                        jobs.add(new Job(Kind.PLANT, m.immutable()));
                    }
                }
        AABB area = new AABB(center).inflate(range, ABOVE, range);
        for (ItemEntity item : mc.level.getEntitiesOfClass(ItemEntity.class, area, ItemEntity::isAlive)) {
            jobs.add(new Job(Kind.PICKUP, item.blockPosition()));
        }
    }

    private boolean stillToDo(Minecraft mc, Job j) {
        return switch (j.kind()) {
            case HARVEST -> ripe(mc, j.pos(), mc.level.getBlockState(j.pos()));
            case PLANT -> mc.level.getBlockState(j.pos().above()).isAir();
            case PICKUP -> !mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(j.pos()).inflate(1.5), ItemEntity::isAlive).isEmpty();
        };
    }

    private static boolean ripe(Minecraft mc, BlockPos p, BlockState s) {
        Block b = s.getBlock();
        if (b instanceof CropBlock crop) return crop.isMaxAge(s);
        if (b instanceof NetherWartBlock) return s.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        if (s.is(Blocks.MELON) || s.is(Blocks.PUMPKIN)) return true;
        if (s.is(Blocks.SUGAR_CANE) || s.is(Blocks.BAMBOO)) {
            // the second block up: breaking it takes everything above and leaves the bottom to regrow
            BlockState below = mc.level.getBlockState(p.below());
            return below.is(b) && !mc.level.getBlockState(p.below(2)).is(b);
        }
        return false;
    }

    /** Hotbar slot with something to plant: nether wart for soul sand, otherwise crop seeds; -1 if none. */
    private static int seedSlot(LocalPlayer player, boolean soulSand) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!(s.getItem() instanceof BlockItem bi)) continue;
            Block b = bi.getBlock();
            if (soulSand ? b instanceof NetherWartBlock : b instanceof CropBlock) return i;
        }
        return -1;
    }

    private void release() {
        if (driving) input.release();
        driving = false;
    }
}
