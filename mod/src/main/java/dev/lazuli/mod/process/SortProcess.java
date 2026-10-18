package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.storage.SortPlan;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * #sort [range]: puts each item of the chests and barrels around into as few of them as it fits, where most of it
 * already is ({@link SortPlan}). Opens each once to see what's inside, then takes misplaced items out a load at a time
 * and carries them to their boxes. Only what it took out is moved: the player's own things stay.
 */
public final class SortProcess implements Process {
    private static final int MAX_RUNS = 400;

    private enum Phase { IDLE, SCAN, WORK, LABEL }

    /** Ticks to spend labelling one box before leaving it. */
    private static final int LABEL_TICKS = 200;

    private final Settings settings;
    private final Consumer<String> chat;
    private final ContainerProcess containers;
    private final MakeRoom room;
    private Phase phase = Phase.IDLE;
    /** The boxes (a double chest by its lower-corner half) and what's in each, kept up to date as things move. */
    private final Map<BlockPos, Map<String, Integer>> boxes = new LinkedHashMap<>();
    private final Map<BlockPos, Integer> slots = new HashMap<>();
    private final List<BlockPos> toScan = new ArrayList<>();
    private SortPlan plan;
    /** What it took out to sort and still carries. */
    private final Map<String, Integer> carried = new LinkedHashMap<>();
    private BlockPos at;
    private boolean depositing;
    private List<ItemCount> moving;
    private Map<String, Integer> before;
    private int runs, moved, scanned;

    public SortProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.containers = new ContainerProcess(session, settings, clicks, s -> {});
        this.room = new MakeRoom(settings, null, chat);
        containers.onOpen((pos, menu) -> {
            if (phase == Phase.SCAN) read(pos, menu);
        });
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    @Override
    public String status() {
        return switch (phase) {
            case IDLE -> null;
            case SCAN -> "Sort: looking in " + scanned + "/" + boxes.size();
            case WORK -> "Sort: " + moved + " items moved" + (depositing ? " (putting away)" : " (taking out)");
            case LABEL -> "Sort: labelling (" + toLabel.size() + " left)";
        };
    }

    public void start(Minecraft mc, int range) {
        stop(false);
        boxes.clear();
        slots.clear();
        carried.clear();
        toScan.clear();
        boxesRead.clear();
        runs = moved = scanned = 0;
        LocalPlayer p = mc.player;
        find(mc, p, range);
        if (boxes.size() < 2) {
            chat.accept(Chat.err("Needs at least 2 chests or barrels within {} blocks", range));
            return;
        }
        toScan.addAll(boxes.keySet());
        phase = Phase.SCAN;
        chat.accept(Chat.ok("Sorting {} containers", boxes.size()));
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        containers.stop(false);
        phase = Phase.IDLE;
        if (announce) chat.accept(Chat.warn("Stopped sorting"));
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        containers.tick(mc);
        LocalPlayer p = mc.player;
        if (p == null) {
            stop(false);
            return;
        }
        if (containers.active()) return;
        if (phase == Phase.LABEL) {
            label(mc, p);
            return;
        }
        if (at != null) {
            if (containers.outcome() == ContainerProcess.Outcome.CANCELLED) {
                stop(false);
                return;
            }
            settle(p);
        }
        if (++runs > MAX_RUNS) {
            end(Chat.warn("Gave up sorting after {} trips ({} items moved)", MAX_RUNS, moved));
            return;
        }
        if (phase == Phase.SCAN) {
            if (!toScan.isEmpty()) {
                BlockPos next = nearest(p, toScan);
                toScan.remove(next);
                run(mc, p, next, false, List.of());
                return;
            }
            plan = SortPlan.of(boxList(), SortProcess::stackSize);
            if (plan.sorted()) {
                finish(mc, p, Chat.ok("Already sorted"));
                return;
            }
            phase = Phase.WORK;
        }
        // carrying things: to the nearest box with room for some
        if (!carried.isEmpty()) {
            BlockPos best = null;
            for (BlockPos b : boxes.keySet()) {
                if (room(b).isEmpty()) continue;
                if (best == null || b.distSqr(p.blockPosition()) < best.distSqr(p.blockPosition())) best = b;
            }
            if (best != null) {
                run(mc, p, best, true, room(best));
                return;
            }
            chat.accept(Chat.warn("No room left for {}: keeping it", ItemCount.describe(asList(carried))));
            carried.clear();
        }
        // else take misplaced things out of the nearest box that has some, as much as fits
        BlockPos from = null;
        for (BlockPos b : boxes.keySet()) {
            if (misplaced(b).isEmpty()) continue;
            if (from == null || b.distSqr(p.blockPosition()) < from.distSqr(p.blockPosition())) from = b;
        }
        if (from == null) {
            finish(mc, p, Chat.ok("Sorted: {} items moved", moved));
            return;
        }
        List<ItemCount> take = fit(p, misplaced(from));
        if (take.isEmpty()) {
            // full: throw a stack of junk out (makeRoomDropJunk), else say so
            if (room.tick(mc, p, null) == MakeRoom.State.WORKING) return;
            end(Chat.err("Inventory full: make some room to sort"));
            return;
        }
        room.reset();
        run(mc, p, from, false, take);
    }

    private void run(Minecraft mc, LocalPlayer p, BlockPos box, boolean deposit, List<ItemCount> items) {
        at = box;
        depositing = deposit;
        moving = items;
        before = counts(p);
        containers.start(mc, deposit, items, box); // nothing: just a look inside
    }

    /** After a run: what moved, into the model. */
    private void settle(LocalPlayer p) {
        BlockPos box = at;
        at = null;
        if (phase == Phase.SCAN) {
            scanned++;
            if (containers.outcome() != ContainerProcess.Outcome.DONE && !boxesRead.contains(box)) {
                boxes.remove(box); // couldn't open it: leave it out
            }
            return;
        }
        Map<String, Integer> now = counts(p);
        Map<String, Integer> contents = boxes.get(box);
        for (ItemCount i : moving) {
            int d = now.getOrDefault(i.item(), 0) - before.getOrDefault(i.item(), 0);
            if (depositing) {
                int in = Math.max(0, -d);
                contents.merge(i.item(), in, Integer::sum);
                carried.computeIfPresent(i.item(), (k, v) -> v - in > 0 ? v - in : null);
                moved += in;
            } else {
                int out = Math.max(0, d);
                contents.computeIfPresent(i.item(), (k, v) -> v - out > 0 ? v - out : null);
                if (out > 0) carried.merge(i.item(), out, Integer::sum);
                // asked for more than came out: the box had less than thought
                if (out < i.count()) contents.computeIfPresent(i.item(), (k, v) -> Math.max(0, v - (i.count() - out)) > 0 ? v - (i.count() - out) : null);
            }
        }
        if (depositing && moving.stream().allMatch(i -> now.getOrDefault(i.item(), 0) >= before.getOrDefault(i.item(), 0))) {
            // nothing went in: that box is full (or gone), don't count on it
            slots.put(box, 0);
            plan = SortPlan.of(boxList(), SortProcess::stackSize);
        }
    }

    private final java.util.Set<BlockPos> boxesRead = new java.util.HashSet<>();

    /** What an opened box holds (scan). */
    private void read(BlockPos pos, AbstractContainerMenu menu) {
        Map<String, Integer> items = new HashMap<>();
        int size = menu.slots.size() - 36;
        for (int i = 0; i < size; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        boxes.put(pos, items);
        slots.put(pos, size);
        boxesRead.add(pos);
    }

    private List<SortPlan.Box> boxList() {
        List<SortPlan.Box> out = new ArrayList<>();
        boxes.forEach((pos, items) -> out.add(new SortPlan.Box(key(pos), slots.getOrDefault(pos, 27), Map.copyOf(items))));
        return out;
    }

    /** What {@code box} holds beyond its share. */
    private Map<String, Integer> misplaced(BlockPos box) {
        Map<String, Integer> out = new LinkedHashMap<>();
        boxes.get(box).forEach((i, n) -> {
            int extra = n - plan.share(i, key(box));
            if (extra > 0) out.put(i, extra);
        });
        return out;
    }

    /** Of what's carried, how much {@code box} still takes. */
    private List<ItemCount> room(BlockPos box) {
        List<ItemCount> out = new ArrayList<>();
        if (slots.getOrDefault(box, 27) == 0) return out;
        carried.forEach((i, n) -> {
            int r = plan.share(i, key(box)) - boxes.get(box).getOrDefault(i, 0);
            if (r > 0) out.add(new ItemCount(i, Math.min(r, n)));
        });
        return out;
    }

    /** As much of {@code want} as fits in the inventory's empty slots. */
    private static List<ItemCount> fit(LocalPlayer p, Map<String, Integer> want) {
        int free = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) free++;
        List<ItemCount> out = new ArrayList<>();
        for (var e : want.entrySet()) {
            if (free <= 0) break;
            int stack = stackSize(e.getKey());
            int n = Math.min(e.getValue(), free * stack);
            free -= (n + stack - 1) / stack;
            out.add(new ItemCount(e.getKey(), n));
        }
        return out;
    }

    private static Map<String, Integer> counts(LocalPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) out.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        return out;
    }

    private static int stackSize(String id) {
        Identifier i = Identifier.tryParse(id);
        return i == null ? 64 : BuiltInRegistries.ITEM.getOptional(i).map(it -> it.getDefaultMaxStackSize()).orElse(64);
    }

    private static String key(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static List<ItemCount> asList(Map<String, Integer> m) {
        List<ItemCount> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new ItemCount(k, v)));
        return out;
    }

    private static BlockPos nearest(LocalPlayer p, List<BlockPos> list) {
        BlockPos best = list.get(0);
        for (BlockPos b : list) if (b.distSqr(p.blockPosition()) < best.distSqr(p.blockPosition())) best = b;
        return best;
    }

    /** Chests (one per double chest) and barrels within {@code range} in loaded chunks, lids free. */
    private void find(Minecraft mc, LocalPlayer p, int range) {
        int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4, r = (range >> 4) + 1;
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (!(be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity)) continue;
                    BlockPos bp = be.getBlockPos();
                    if (bp.distSqr(p.blockPosition()) > (double) range * range) continue;
                    var state = mc.level.getBlockState(bp);
                    int size = 27;
                    if (be instanceof ChestBlockEntity) {
                        if (!(state.getBlock() instanceof ChestBlock)) continue; // trapped/ender variants and such
                        if (mc.level.getBlockState(bp.above()).isRedstoneConductor(mc.level, bp.above())) continue;
                        if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                            BlockPos other = bp.relative(ChestBlock.getConnectedDirection(state));
                            if (other.asLong() < bp.asLong()) continue; // the other half stands for both
                            if (mc.level.getBlockState(other.above()).isRedstoneConductor(mc.level, other.above())) continue;
                            size = 54;
                        }
                    }
                    boxes.put(bp.immutable(), new HashMap<>());
                    slots.put(bp.immutable(), size);
                }
            }
    }

    // ---- labels

    /** Boxes still to label, and the one being worked on: its front, what goes in its frame, since when. */
    private final List<BlockPos> toLabel = new ArrayList<>();
    private BlockPos labelBox;
    private Direction labelFace;
    private String labelItem;
    private int labelSince, usedAt, labelled;
    private final InputDriver input = new InputDriver();

    /** Sorted: labels the boxes next (sortLabels, with item frames on hand), else done. */
    private void finish(Minecraft mc, LocalPlayer p, String msg) {
        chat.accept(msg);
        if (!settings.sortLabels.get()) {
            end(null);
            return;
        }
        toLabel.clear();
        for (BlockPos b : boxes.keySet()) if (main(b) != null && front(mc, b) != null && !labelled(mc, b)) toLabel.add(b);
        if (toLabel.isEmpty()) {
            end(null);
            return;
        }
        if (!Hands.has(p, SortProcess::frame)) {
            end(Chat.info("Carry item frames to have the {} boxes labelled", toLabel.size()));
            return;
        }
        labelBox = null;
        labelled = 0;
        phase = Phase.LABEL;
    }

    private static boolean frame(ItemStack s) {
        return s.is(Items.ITEM_FRAME) || s.is(Items.GLOW_ITEM_FRAME);
    }

    /** One tick of labelling: an item frame on the box's front, then one of what it holds most of in the frame. */
    private void label(Minecraft mc, LocalPlayer p) {
        if (labelBox == null) {
            if (toLabel.isEmpty()) {
                end(Chat.ok("Labelled {} boxes", labelled));
                return;
            }
            labelBox = nearest(p, toLabel);
            toLabel.remove(labelBox);
            labelFace = front(mc, labelBox);
            labelItem = main(labelBox);
            labelSince = p.tickCount;
            if (labelFace == null || labelItem == null) labelBox = null;
            return;
        }
        if (p.tickCount - labelSince > LABEL_TICKS) {
            labelBox = null; // can't: on to the next one
            input.release();
            return;
        }
        ItemFrame f = frameOn(mc, labelBox, labelFace);
        if (f != null && !f.getItem().isEmpty()) {
            labelled++;
            labelBox = null;
            input.release();
            return;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(Identifier.tryParse(labelItem)).orElse(Items.AIR);
        if (!Hands.has(p, s -> s.is(item))) {
            // one out of the box for the frame
            containers.start(mc, false, List.of(new ItemCount(labelItem, 1)), labelBox);
            return;
        }
        if (f == null) {
            if (!Hands.hold(p, ClickDriver.get(), SortProcess::frame)) {
                if (!Hands.has(p, SortProcess::frame)) end(Chat.warn("Out of item frames ({} boxes labelled)", labelled));
                return;
            }
            if (p.tickCount - usedAt < 6) return;
            if (Aim.useOn(mc, p, labelBox, labelFace, input, settings)) usedAt = p.tickCount;
            return;
        }
        if (!Hands.hold(p, ClickDriver.get(), s -> s.is(item))) return;
        Vec3 c = f.getBoundingBox().getCenter(), eye = p.getEyePosition();
        double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
        float[] aim = {(float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))};
        Aim.turn(mc, p, input, aim, settings);
        if (p.tickCount - usedAt < 6) return;
        if (mc.hitResult instanceof EntityHitResult h && h.getEntity() == f) {
            mc.gameMode.interact(p, f, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            usedAt = p.tickCount;
        }
    }

    /** What the box holds most of, or null when it is empty. */
    private String main(BlockPos box) {
        String best = null;
        int n = 0;
        for (var e : boxes.get(box).entrySet()) {
            if (e.getValue() <= n) continue;
            n = e.getValue();
            best = e.getKey();
        }
        return best;
    }

    /** The face a label goes on: the box front, with room in front of it; null if there is none. */
    private static Direction front(Minecraft mc, BlockPos box) {
        var s = mc.level.getBlockState(box);
        Direction d = s.hasProperty(ChestBlock.FACING) ? s.getValue(ChestBlock.FACING)
            : s.hasProperty(BarrelBlock.FACING) ? s.getValue(BarrelBlock.FACING) : null;
        if (d == null) return null;
        return mc.level.getBlockState(box.relative(d)).canBeReplaced() ? d : null;
    }

    /** Already has a frame with something in it (on its front or, for a double chest, the other half). */
    private static boolean labelled(Minecraft mc, BlockPos box) {
        Direction d = front(mc, box);
        if (d == null) return true;
        ItemFrame f = frameOn(mc, box, d);
        if (f != null && !f.getItem().isEmpty()) return true;
        var s = mc.level.getBlockState(box);
        if (s.getBlock() instanceof ChestBlock && s.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            ItemFrame o = frameOn(mc, box.relative(ChestBlock.getConnectedDirection(s)), d);
            return o != null && !o.getItem().isEmpty();
        }
        return false;
    }

    private static ItemFrame frameOn(Minecraft mc, BlockPos box, Direction face) {
        BlockPos in = box.relative(face);
        for (ItemFrame f : mc.level.getEntitiesOfClass(ItemFrame.class, new AABB(in))) {
            if (f.getDirection() == face && f.getPos().equals(in)) return f;
        }
        return null;
    }

    private void end(String msg) {
        phase = Phase.IDLE;
        input.release();
        containers.stop(false);
        if (msg != null) chat.accept(msg);
    }
}
