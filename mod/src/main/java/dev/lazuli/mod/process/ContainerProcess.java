package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * #deposit / #withdraw: walks next to a container (a given one, or the nearest chest, barrel, shulker box or ender
 * chest), opens it, moves the items and closes it again.
 */
public final class ContainerProcess implements Process {
    private static final int OPEN_TICKS = 60, MOVE_TICKS = 400, TRIES = 2;

    private enum Phase { IDLE, TRAVEL, OPEN, MOVE }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private BlockPos pos;
    /** Into the container (else out of it); whether this phase's one-off step (the click, the plan) is done. */
    private boolean deposit, acted;
    private List<ItemCount> items;
    private List<Predicate<String>> matches;
    private int[] before;
    private int phaseStart, tries;

    public ContainerProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.trip = new Trip(session);
    }

    @Override
    public String status() {
        return active() ? "Moving items" : null;
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    /** How the last run ended. */
    public Outcome outcome() {
        return outcome;
    }

    /**
     * @param deposit into the container (else out of it)
     * @param items   ids (or "all") and how many; a negative count: all of them
     * @param at      the container, or null for the nearest storage
     */
    public void start(Minecraft mc, boolean deposit, List<ItemCount> items, BlockPos at) {
        stop(false);
        LocalPlayer p = mc.player;
        pos = at != null ? at : nearest(mc, p);
        outcome = Outcome.NONE;
        if (pos == null) {
            outcome = Outcome.FAILED;
            chat.accept(Chat.err("No chest, barrel, shulker box or ender chest nearby"));
            return;
        }
        this.deposit = deposit;
        this.items = List.copyOf(items);
        this.matches = items.stream().map(i -> Inventories.matcher(i.item())).toList();
        tries = 0;
        trip.clear();
        enter(Phase.TRAVEL, p);
        chat.accept(Chat.ok("{} {} {} {} {} {}", deposit ? "Putting" : "Taking", ItemCount.describe(items),
            deposit ? "into" : "from", pos.getX(), pos.getY(), pos.getZ()));
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        switch (phase) {
            case TRAVEL -> {
                if (Aim.at(mc, p, pos, null) != null) {
                    trip.cancel();
                    enter(Phase.OPEN, p);
                    return;
                }
                if (trip.running()) return;
                if (++tries > TRIES) {
                    end(Outcome.FAILED, Chat.err("Can't get to the container"));
                    return;
                }
                trip.go(new GoalAdjacent(pos.getX(), pos.getY(), pos.getZ()), p);
            }
            case OPEN -> {
                if (p.containerMenu != p.inventoryMenu) {
                    input.release();
                    enter(Phase.MOVE, p);
                    return;
                }
                if (p.tickCount - phaseStart > OPEN_TICKS) {
                    end(Outcome.FAILED, Chat.err("The container wouldn't open"));
                    return;
                }
                // once clicked, keep still and wait for the menu to arrive
                if (!acted) acted = Aim.useOn(mc, p, pos, null, input, settings);
            }
            case MOVE -> {
                if (p.containerMenu == p.inventoryMenu) {
                    end(Outcome.FAILED, Chat.err("The container closed"));
                    return;
                }
                var menu = p.containerMenu;
                int[] mine = Inventories.slots(menu, p, true), theirs = Inventories.slots(menu, p, false);
                if (!acted) {
                    // one plan for every item, each checked on the menu as the ones before it leave it
                    Menu sim = Inventories.read(menu, p);
                    before = new int[items.size()];
                    List<Click> plan = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        before[i] = sim.count(mine, matches.get(i));
                        int count = items.get(i).count();
                        List<Click> part = deposit ? Moves.transfer(sim, mine, theirs, matches.get(i), count)
                            : Moves.transfer(sim, theirs, mine, matches.get(i), count);
                        sim.applyAll(part);
                        plan.addAll(part);
                    }
                    clicks.play(menu.containerId, plan);
                    acted = true;
                    return;
                }
                if (clicks.busy() && p.tickCount - phaseStart < MOVE_TICKS) return;
                Menu now = Inventories.read(menu, p);
                List<ItemCount> moved = new ArrayList<>();
                boolean ok = true;
                for (int i = 0; i < items.size(); i++) {
                    int n = Math.abs(now.count(mine, matches.get(i)) - before[i]), count = items.get(i).count();
                    moved.add(new ItemCount(items.get(i).item(), n));
                    ok &= n > 0 && (count < 0 || n >= count);
                }
                p.closeContainer();
                end(ok ? Outcome.DONE : Outcome.FAILED, ok ? Chat.ok("{} {}", deposit ? "Put away" : "Took", ItemCount.describe(moved))
                    : Chat.err("Moved {} (asked for {})", ItemCount.describe(moved), ItemCount.describe(items)));
            }
            default -> {}
        }
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
        acted = false;
    }

    private void end(Outcome how, String msg) {
        phase = Phase.IDLE;
        outcome = how;
        trip.cancel();
        input.release();
        if (msg != null) chat.accept(msg);
    }

    /** The nearest storage block in loaded chunks that can be opened (a chest's lid must be free), or null. */
    private static BlockPos nearest(Minecraft mc, LocalPlayer p) {
        int r = mc.options.getEffectiveRenderDistance();
        int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!storage(be)) continue;
                    BlockPos bp = be.getBlockPos();
                    if ((be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity)
                        && mc.level.getBlockState(bp.above()).isRedstoneConductor(mc.level, bp.above())) continue;
                    double d = bp.distToCenterSqr(p.position());
                    if (d < bestD) {
                        bestD = d;
                        best = bp;
                    }
                }
            }
        return best;
    }

    static boolean storage(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity
            || be instanceof EnderChestBlockEntity;
    }
}
