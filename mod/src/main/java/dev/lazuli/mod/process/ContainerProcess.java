package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.storage.ChestMemory;
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
    private final PathingSession session;
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
    /** Sorted deposits: the containers still to visit after this one, each with its items. */
    private final java.util.ArrayDeque<Group> queue = new java.util.ArrayDeque<>();
    private boolean anyFailed, started;
    private java.util.function.Function<Minecraft, ChestMemory> memory;
    private java.util.function.Function<Minecraft, String> dimension;

    /** Items for one container (null: the nearest). */
    private record Group(BlockPos at, List<ItemCount> items) {}

    public ContainerProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.trip = new Trip(session);
        this.session = session;
    }

    PathingSession session() {
        return session;
    }

    @Override
    public String status() {
        return active() ? "Moving items" : null;
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    /** Called with the container and its menu once it's open, before anything moves (#sort reads it). */
    private java.util.function.BiConsumer<BlockPos, net.minecraft.world.inventory.AbstractContainerMenu> onOpen;

    void onOpen(java.util.function.BiConsumer<BlockPos, net.minecraft.world.inventory.AbstractContainerMenu> f) {
        onOpen = f;
    }

    /** Remembered chests, for sorted deposits. */
    public void useMemory(java.util.function.Function<Minecraft, ChestMemory> memory, java.util.function.Function<Minecraft, String> dimension) {
        this.memory = memory;
        this.dimension = dimension;
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
        queue.clear();
        anyFailed = started = false;
        if (deposit && at == null && settings.depositSorted.get() && memory != null && settings.chestMemory.get()) {
            queue.addAll(sorted(mc, mc.player, items));
            next(mc);
            return;
        }
        run(mc, deposit, items, at);
    }

    /** The next container of a sorted deposit (skipping groups with nothing left to put in), or the end. */
    private void next(Minecraft mc) {
        while (!queue.isEmpty()) {
            Group g = queue.poll();
            if (g.items().stream().anyMatch(i -> held(mc.player, i.item()) > 0)) {
                started = true;
                run(mc, true, g.items(), g.at());
                return;
            }
        }
        outcome = anyFailed ? Outcome.FAILED : Outcome.DONE;
        if (!started) chat.accept(Chat.warn("Nothing of that to put away"));
    }

    /**
     * Each item to the nearest remembered container (within chestRange) that already holds it; the rest (and "all"'s
     * other items) to the nearest container.
     */
    private List<Group> sorted(Minecraft mc, LocalPlayer p, List<ItemCount> items) {
        ChestMemory m = memory.apply(mc);
        String dim = dimension.apply(mc);
        BlockPos f = p.blockPosition();
        java.util.Map<BlockPos, List<ItemCount>> to = new java.util.LinkedHashMap<>();
        List<ItemCount> rest = new ArrayList<>();
        for (ItemCount i : items) {
            List<String> ids = new ArrayList<>();
            if (i.item().equals("all")) {
                for (int s = 0; s < 36; s++) {
                    var st = p.getInventory().getItem(s);
                    String id = st.isEmpty() ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                    if (id != null && !ids.contains(id)) ids.add(id);
                }
                rest.add(i); // whatever has no chest of its own
            } else {
                ids.add(Inventories.id(i.item()));
            }
            for (String id : ids) {
                ChestMemory.Spot s = m == null ? null : m.nearest(id, dim, f.getX(), f.getY(), f.getZ(), settings.chestRange.get());
                if (s != null && storageAt(mc, new BlockPos(s.x(), s.y(), s.z()))) {
                    to.computeIfAbsent(new BlockPos(s.x(), s.y(), s.z()), k -> new ArrayList<>())
                        .add(new ItemCount(id, i.item().equals("all") ? -1 : i.count()));
                } else if (!i.item().equals("all")) {
                    rest.add(i);
                }
            }
        }
        List<Group> out = new ArrayList<>();
        to.forEach((at, list) -> out.add(new Group(at, list)));
        if (!rest.isEmpty()) out.add(new Group(null, rest));
        return out;
    }

    /** Still a storage block there (or not loaded, so unknown: trusted). */
    private static boolean storageAt(Minecraft mc, BlockPos at) {
        if (!mc.level.isLoaded(at)) return true;
        BlockEntity be = mc.level.getBlockEntity(at);
        return be != null && storage(be);
    }

    private static int held(LocalPlayer p, String item) {
        Predicate<String> m = Inventories.matcher(item);
        int n = 0;
        for (int s = 0; s < 36; s++) {
            var st = p.getInventory().getItem(s);
            if (!st.isEmpty() && m.test(Inventories.key(st))) n += st.getCount();
        }
        return n;
    }

    private void run(Minecraft mc, boolean deposit, List<ItemCount> items, BlockPos at) {
        LocalPlayer p = mc.player;
        pos = at != null ? at : nearest(mc, p);
        outcome = Outcome.NONE;
        if (pos == null) {
            outcome = Outcome.FAILED;
            chat.accept(Chat.err("No chest, barrel, shulker box or ender chest nearby"));
            if (!queue.isEmpty()) {
                anyFailed = true;
                next(mc);
            }
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
                    if (onOpen != null) onOpen.accept(pos, menu);
                    // "shulker>diamond": the shulker boxes in there holding diamonds (one box)
                    matches = items.stream().map(i -> dev.lazuli.core.inventory.ItemKeys.isInShulker(i.item())
                        ? boxesWith(menu, dev.lazuli.core.inventory.ItemKeys.shulkerContent(i.item()))
                        : Inventories.matcher(i.item())).toList();
                    // one plan for every item, each checked on the menu as the ones before it leave it
                    Menu sim = Inventories.read(menu, p);
                    before = new int[items.size()];
                    List<Click> plan = new ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        before[i] = sim.count(mine, matches.get(i));
                        int count = dev.lazuli.core.inventory.ItemKeys.isInShulker(items.get(i).item()) ? 1 : items.get(i).count();
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
                    int n = Math.abs(now.count(mine, matches.get(i)) - before[i]);
                    int count = dev.lazuli.core.inventory.ItemKeys.isInShulker(items.get(i).item()) ? 1 : items.get(i).count();
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

    /** Matches the keys of the shulker boxes in {@code menu} holding some {@code item}. */
    private static Predicate<String> boxesWith(net.minecraft.world.inventory.AbstractContainerMenu menu, String item) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var slot : menu.slots) if (Inventories.inShulker(slot.getItem(), item) > 0) keys.add(Inventories.key(slot.getItem()));
        return keys::contains;
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
        if (how == Outcome.CANCELLED) {
            queue.clear();
        } else if (!queue.isEmpty()) {
            // a sorted deposit: on to the next container
            anyFailed |= how == Outcome.FAILED;
            outcome = Outcome.NONE;
            next(Minecraft.getInstance());
        }
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
