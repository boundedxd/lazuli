package dev.lazuli.mod.process;

import dev.lazuli.core.area.Selection;
import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.schematic.Orient;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #sel clear / #sel fill: breaks every block in the box (top layer first), or places a block in every empty spot
 * (bottom layer first, each against one already there). #build: a schematic, each spot its own block: a wrong block
 * there is broken first, then the right one placed, bottom layer first. Within the layer it works on the spot
 * nearest the player, walking closer when it's out of reach; spots it can't get to are skipped and counted.
 * <p>
 * With buildExactStates a schematic's stairs, slabs, logs, doors... are placed turned the way they're drawn (the face
 * clicked, the half hit and the way the player looks, by {@link Orient}), and ones turned wrong are broken and placed
 * again; off, blocks are matched by kind only.
 */
public final class BuildProcess implements Process {
    private static final int TRIES = 3, ACT_TICKS = 100, PLACE_WAIT = 6;

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private ChestTake take;
    private Selection.Box box;
    /** The block to place (item id), or null to clear. */
    private String block;
    /** #build: the block wanted at each spot (null for #sel), and the schematic's name. */
    private java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> plan;
    /** #build: how to place each spot's block turned the right way (spots missing: any way will do). */
    private final java.util.Map<BlockPos, Orient.Rule> rules = new java.util.HashMap<>();
    private String planName;
    private boolean running;
    /** Spots still to do, by layer in working order. */
    private List<List<BlockPos>> layers;
    private int layer;
    private BlockPos target;
    private int tries, since, usedAt, done, total, refills;
    private final Set<BlockPos> skipped = new HashSet<>();
    /** The skipped spots have had their one more go. */
    private boolean retriedSkips;

    public BuildProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.trip = new Trip(session);
    }

    /** Refills blocks to place from remembered chests (chestIngredients). */
    public void useChests(ContainerProcess containers, java.util.function.Function<Minecraft, dev.lazuli.core.storage.ChestMemory> chests,
                          java.util.function.Function<Minecraft, String> dimension) {
        take = new ChestTake(settings, containers, chests, dimension);
    }

    @Override
    public boolean active() {
        return running;
    }

    @Override
    public String status() {
        if (!running) return null;
        return (plan != null ? "Build " + planName : block == null ? "Clear" : "Fill " + name(block)) + "  " + done + "/" + total
            + (take != null && take.active() ? "  (getting more)" : "");
    }

    @Override
    public double progress() {
        return running && total > 0 ? (double) done / total : -1;
    }

    /** @param block what to fill with (an item id), or null to clear the box */
    public void start(Minecraft mc, Selection.Box box, String block) {
        stop(false);
        LocalPlayer p = mc.player;
        if (block != null && !(net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(block))
            .orElse(null) instanceof net.minecraft.world.item.BlockItem)) {
            chat.accept(Chat.err("Not a block: {}", block));
            return;
        }
        this.box = box;
        this.block = block;
        this.plan = null;
        layers = new ArrayList<>();
        int lastY = Integer.MIN_VALUE;
        for (int[] q : box.order(block == null, p.getBlockX(), p.getBlockY(), p.getBlockZ())) {
            if (q[1] != lastY) {
                layers.add(new ArrayList<>());
                lastY = q[1];
            }
            layers.get(layers.size() - 1).add(new BlockPos(q[0], q[1], q[2]));
        }
        layer = 0;
        target = null;
        done = refills = 0;
        skipped.clear();
        retriedSkips = false;
        total = 0;
        for (List<BlockPos> l : layers) for (BlockPos b : l) if (todo(mc, b)) total++;
        if (total == 0) {
            chat.accept(Chat.ok(block == null ? "Nothing to clear there" : "Nothing to fill there"));
            return;
        }
        running = true;
        trip.clear();
        chat.accept(Chat.ok("{} {} blocks", block == null ? "Clearing" : "Filling with " + name(block) + ":", total));
    }

    /**
     * #build: places a schematic with its lowest corner at {@code origin}; blocks the game doesn't know (or that have no
     * item) are left out and counted.
     */
    public void startSchematic(Minecraft mc, String name, dev.lazuli.core.schematic.Schematic s, BlockPos origin) {
        stop(false);
        LocalPlayer p = mc.player;
        plan = new java.util.HashMap<>();
        rules.clear();
        java.util.Map<String, net.minecraft.world.level.block.state.BlockState> parsed = new java.util.HashMap<>();
        java.util.Map<net.minecraft.world.level.block.state.BlockState, java.util.Optional<Orient.Rule>> ruleOf = new java.util.HashMap<>();
        int unknown = 0;
        for (var b : s.blocks()) {
            var st = parsed.computeIfAbsent(b.state(), BuildProcess::parse);
            if (st == null || st.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
                unknown++;
                continue;
            }
            BlockPos at = origin.offset(b.x(), b.y(), b.z());
            plan.put(at, st);
            ruleOf.computeIfAbsent(st, x -> java.util.Optional.ofNullable(
                Orient.rule(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(x.getBlock()).toString(), props(x))))
                .ifPresent(rule -> rules.put(at, rule));
        }
        planName = name;
        block = null;
        box = new Selection.Box(origin.getX(), origin.getY(), origin.getZ(), origin.getX() + Math.max(0, s.sizeX() - 1),
            origin.getY() + Math.max(0, s.sizeY() - 1), origin.getZ() + Math.max(0, s.sizeZ() - 1));
        // bottom layer first; each layer gets ordered by distance as it's worked on
        java.util.TreeMap<Integer, List<BlockPos>> byY = new java.util.TreeMap<>();
        for (BlockPos b : plan.keySet()) byY.computeIfAbsent(b.getY(), k -> new ArrayList<>()).add(b);
        layers = new ArrayList<>(byY.values());
        layer = 0;
        target = null;
        done = refills = 0;
        skipped.clear();
        retriedSkips = false;
        total = 0;
        for (List<BlockPos> l : layers) for (BlockPos b : l) if (todo(mc, b)) total++;
        if (total == 0) {
            chat.accept(Chat.ok("{} is already built there", name));
            plan = null;
            return;
        }
        running = true;
        trip.clear();
        chat.accept(Chat.ok("Building {} at {}: {} blocks to place or fix{}", name, origin.toShortString(), total,
            unknown > 0 ? " (" + unknown + " left out: unknown blocks, or ones with no item)" : ""));
    }

    /** "minecraft:oak_stairs[facing=east]" into a block state, or null if the game doesn't know it. */
    private static net.minecraft.world.level.block.state.BlockState parse(String state) {
        try {
            return net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK, state, false).blockState();
        } catch (Exception e) {
            return null;
        }
    }

    /** A block state's properties by name ("facing" -> "east"). */
    private static java.util.Map<String, String> props(net.minecraft.world.level.block.state.BlockState s) {
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (var p : s.getProperties()) m.put(p.getName(), value(s, p));
        return m;
    }

    private static <T extends Comparable<T>> String value(net.minecraft.world.level.block.state.BlockState s,
                                                           net.minecraft.world.level.block.state.properties.Property<T> p) {
        return p.getName(s.getValue(p));
    }

    /** The placing rule for {@code b}, if buildExactStates is on and its block has one. */
    private Orient.Rule rule(BlockPos b) {
        return plan != null && settings.buildExactStates.get() ? rules.get(b) : null;
    }

    /** The schematic's block is at {@code b} but turned the wrong way. */
    private boolean turnedWrong(Minecraft mc, BlockPos b) {
        Orient.Rule rule = rule(b);
        if (rule == null) return false;
        var s = mc.level.getBlockState(b);
        return s.getBlock() == plan.get(b).getBlock() && !rule.fits(props(s));
    }

    /** A single slab where a double one is wanted: finished by placing the other half, not by breaking it. */
    private boolean placeOnto(Minecraft mc, BlockPos b) {
        Orient.Rule rule = rule(b);
        return rule != null && rule.placeOnto(props(mc.level.getBlockState(b)));
    }

    /** The item placed at {@code b}: the schematic's block there, or the fill block. */
    private String itemFor(BlockPos b) {
        if (plan == null) return block;
        var st = plan.get(b);
        return st == null ? null : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getBlock().asItem()).toString();
    }

    /** Something has to be broken at {@code b} (clearing, or a wrong block where the schematic wants another). */
    private boolean breaking(Minecraft mc, BlockPos b) {
        if (plan == null) return block == null || plant(mc, b);
        var s = mc.level.getBlockState(b);
        var want = plan.get(b);
        if (want == null || s.canBeReplaced()) return false;
        return s.getBlock() != want.getBlock() || turnedWrong(mc, b) && !placeOnto(mc, b);
    }

    @Override
    public void stop(boolean announce) {
        if (!running) return;
        if (take != null) take.stop();
        end(announce ? Chat.warn("Stopped") : null);
    }

    /** Still something to do at {@code b}: a block to break, or room for one. */
    private boolean todo(Minecraft mc, BlockPos b) {
        var s = mc.level.getBlockState(b);
        if (plan != null) {
            var want = plan.get(b);
            if (want == null) return false;
            if (s.getBlock() == want.getBlock()) return turnedWrong(mc, b) && (placeOnto(mc, b) || s.getDestroySpeed(mc.level, b) >= 0);
            return s.canBeReplaced() || s.getDestroySpeed(mc.level, b) >= 0;
        }
        if (block == null) {
            if (s.isAir() || !s.getFluidState().isEmpty() && s.getCollisionShape(mc.level, b).isEmpty()) return false;
            return s.getDestroySpeed(mc.level, b) >= 0;
        }
        return s.canBeReplaced() || plant(mc, b);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (p == null) {
            stop(false);
            return;
        }
        if (take != null && take.active()) {
            if (take.tick(mc, p) == ChestTake.Result.RUNNING) return;
            if (take.got(p) == 0) {
                String item = target == null ? block : itemFor(target);
                if (plan != null && settings.buildSkipMissing.get() && target != null) {
                    skip(p); // none in the chests either: build the rest
                } else {
                    end(Chat.err("Out of {} ({} of {} done)", name(item), done, total));
                    return;
                }
            }
        }
        if (target != null && !todo(mc, target)) {
            done++;
            target = null;
            input.release();
        }
        if (target == null && !pick(mc, p)) return;
        // working on target
        if (p.tickCount - since > ACT_TICKS && !trip.running()) {
            skip(p);
            return;
        }
        if (breaking(mc, target)) clear(mc, p);
        else fill(mc, p);
    }

    /** The next spot: in the first layer with work left, the nearest to the player. False once it's finished. */
    private boolean pick(Minecraft mc, LocalPlayer p) {
        while (layer < layers.size()) {
            List<BlockPos> l = layers.get(layer);
            BlockPos best = null, deferred = null;
            double bestD = Double.MAX_VALUE;
            for (var it = l.iterator(); it.hasNext(); ) {
                BlockPos b = it.next();
                if (skipped.contains(b)) continue;
                if (!todo(mc, b)) {
                    it.remove();
                    continue;
                }
                // filling: not where the player stands, and only against something
                if (!breaking(mc, b) && (occupied(p, b) || against(mc, b) == null)) {
                    if (deferred == null) deferred = b;
                    continue;
                }
                double d = b.distToCenterSqr(p.getEyePosition());
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
            if (best == null && deferred != null) {
                // only the spot under the player (or ones with nothing to place against) are left in this layer
                if (occupied(p, deferred)) {
                    if (!trip.running()) {
                        BlockPos out = outside(mc, deferred);
                        if (out == null || tries++ > TRIES) {
                            skipped.add(deferred);
                            tries = 0;
                        } else {
                            trip.go(new GoalBlock(out.getX(), out.getY(), out.getZ()), p);
                        }
                    }
                    return false;
                }
                skipped.add(deferred); // nothing to place it against, ever: the layer below wasn't there
                continue;
            }
            if (best != null) {
                target = best;
                tries = 0;
                since = p.tickCount;
                return true;
            }
            layer++;
            if (layer >= layers.size() && !skipped.isEmpty() && !retriedSkips && settings.retrySkippedSeconds.get() > 0) {
                // everything else is done: the spots it gave up on get one more go (a chest refilled, a gap closed)
                retriedSkips = true;
                chat.accept(Chat.info("Trying the {} skipped spots once more", skipped.size()));
                skipped.clear();
                layer = 0;
                tries = 0;
            }
        }
        end(skipped.isEmpty() ? Chat.ok("{} done: {} blocks", plan != null ? planName : block == null ? "Clearing" : "Filling", done)
            : Chat.warn("Done: {} blocks, {} skipped (couldn't get to them)", done, skipped.size()));
        return false;
    }

    private void clear(Minecraft mc, LocalPlayer p) {
        Aim.Result r = Aim.breakTick(mc, p, target, input, settings);
        if (r == Aim.Result.BLOCKED) approach(p);
        else if (trip.running()) trip.cancel();
    }

    private void fill(Minecraft mc, LocalPlayer p) {
        if (!hold(mc, p)) return;
        if (occupied(p, target)) {
            target = null; // walked into it: another spot first
            return;
        }
        List<BlockPos> ns = neighbours(mc, target);
        if (ns.isEmpty()) {
            target = null;
            return;
        }
        Orient.Rule rule = rule(target);
        Aim.Check check = rule == null ? null : (yaw, pitch, hit) -> rule.ok(Orient.Dir.valueOf(hit.getDirection().name()),
            hit.getLocation().y - hit.getBlockPos().getY(), yaw, pitch);
        // the first neighbour that can be clicked from here (the right way, when it matters)
        BlockPos a = null;
        Direction face = null;
        for (BlockPos n : ns) {
            Direction f = Direction.getApproximateNearest(target.getX() - n.getX(), target.getY() - n.getY(), target.getZ() - n.getZ());
            if (Aim.at(mc, p, p.getEyePosition(), n, f, check) != null) {
                a = n;
                face = f;
                break;
            }
        }
        if (a == null) {
            Orient.Dir side = rule == null ? null : rule.standSide();
            approach(p, side == null ? target : target.relative(Direction.valueOf(side.name()), 2));
            return;
        }
        if (trip.running()) trip.cancel();
        if (p.tickCount - usedAt < PLACE_WAIT) return;
        if (Aim.useOn(mc, p, a, face, check, input, settings)) usedAt = p.tickCount;
    }

    /** The block in hand, swapped in (or refilled from a chest); false while that's happening. */
    private boolean hold(Minecraft mc, LocalPlayer p) {
        String item = itemFor(target);
        var inv = p.getInventory();
        if (Inventories.key(inv.getItem(inv.getSelectedSlot())).equals(item)) return true;
        int slot = -1;
        for (int i = 0; i < 36; i++) if (Inventories.key(inv.getItem(i)).equals(item)) slot = slot < 0 || i < 9 && slot >= 9 ? i : slot;
        if (slot < 0) {
            input.release();
            if (take != null && settings.chestIngredients.get() && refills++ < 20
                && take.start(mc, p, item, Math.min(64 * 9, needed(mc, item)), pl -> held(pl, item))) {
                chat.accept(Chat.info("Out of {}: getting more", name(item)));
                return false;
            }
            if (plan != null && settings.buildSkipMissing.get()) {
                skip(p); // build the rest; this one's left out
                return false;
            }
            end(Chat.err("Out of {} ({} of {} done)", name(item), done, total));
            return false;
        }
        if (slot < 9) inv.setSelectedSlot(slot);
        else if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, inv.getSelectedSlot())));
        return false;
    }

    private int held(LocalPlayer p, String item) {
        int n = 0;
        for (int i = 0; i < 36; i++) if (Inventories.key(p.getInventory().getItem(i)).equals(item)) n += p.getInventory().getItem(i).getCount();
        return n;
    }

    /** How many more of {@code item} the rest of the job places. */
    private int needed(Minecraft mc, String item) {
        if (plan == null) return total - done;
        int n = 0;
        for (int l = layer; l < layers.size(); l++)
            for (BlockPos b : layers.get(l)) if (!breaking(mc, b) && item.equals(itemFor(b)) && todo(mc, b)) n++;
        return Math.max(1, n);
    }

    /** #build materials: what the schematic takes against what's in the inventory. */
    public static List<String> materials(Minecraft mc, dev.lazuli.core.schematic.Schematic s) {
        java.util.Map<String, Integer> items = new java.util.TreeMap<>();
        for (var e : s.materials().entrySet()) {
            var st = parse(e.getKey());
            if (st == null) continue;
            String item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getBlock().asItem()).toString();
            if (!item.equals("minecraft:air")) items.merge(item, e.getValue(), Integer::sum);
        }
        List<String> out = new ArrayList<>();
        var have = Inventories.plainCounts(mc.player);
        items.entrySet().stream().sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed()).forEach(e -> {
            int h = have.getOrDefault(e.getKey(), 0);
            out.add((h >= e.getValue() ? Chat.GREEN : Chat.RED) + e.getValue() + " " + name(e.getKey()) + Chat.GRAY + " (have " + h + ")");
        });
        return out;
    }

    /** Out of reach or sight: walk closer (a few times, then skip it). */
    private void approach(LocalPlayer p) {
        approach(p, target);
    }

    /** {@link #approach} toward {@code near} (a spot beside the target, to look at it the way a block's facing needs). */
    private void approach(LocalPlayer p, BlockPos near) {
        if (trip.running()) return;
        if (tries++ >= TRIES) {
            skip(p);
            return;
        }
        trip.go(new GoalNear(near.getX(), near.getY(), near.getZ(), near.equals(target) ? 2 : 1), p, true);
    }

    private void skip(LocalPlayer p) {
        skipped.add(target);
        target = null;
        trip.cancel();
        input.release();
    }

    /** A solid neighbour to place {@code b} against (below first), not one that opens when clicked; or null. */
    private BlockPos against(Minecraft mc, BlockPos b) {
        List<BlockPos> n = neighbours(mc, b);
        return n.isEmpty() ? null : n.get(0);
    }

    /** Every solid neighbour {@code b} can be placed against (below first), on a face that can give the state wanted. */
    private List<BlockPos> neighbours(Minecraft mc, BlockPos b) {
        Orient.Rule rule = rule(b);
        List<BlockPos> out = new ArrayList<>();
        for (Direction d : Direction.values()) { // DOWN first
            BlockPos n = b.relative(d);
            // the face of n that gets clicked points back at b
            if (solid(mc, n) && (rule == null || rule.canClick(Orient.Dir.valueOf(d.getOpposite().name())))) out.add(n);
        }
        return out;
    }

    /** A flower, leaf litter or the like: nothing can go there until it's broken (at once). */
    static boolean plant(Minecraft mc, BlockPos b) {
        var s = mc.level.getBlockState(b);
        return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty() && s.getCollisionShape(mc.level, b).isEmpty()
            && s.getDestroySpeed(mc.level, b) >= 0 && s.getDestroySpeed(mc.level, b) <= 0.2f;
    }

    private static boolean solid(Minecraft mc, BlockPos n) {
        var s = mc.level.getBlockState(n);
        return !s.canBeReplaced() && !s.getCollisionShape(mc.level, n).isEmpty()
            && !(s.getBlock() instanceof net.minecraft.world.level.block.EntityBlock)
            && s.getMenuProvider(mc.level, n) == null;
    }

    private static boolean occupied(LocalPlayer p, BlockPos b) {
        return p.getBoundingBox().intersects(new AABB(b));
    }

    /** A spot to stand on near {@code b} that's out of the box, or null. */
    private BlockPos outside(Minecraft mc, BlockPos b) {
        for (int r = 1; r <= 4; r++)
            for (Direction d : Direction.Plane.HORIZONTAL)
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos s = b.relative(d, r).above(dy);
                    if (box.contains(s.getX(), s.getY(), s.getZ())) continue;
                    if (mc.level.getBlockState(s.below()).canBeReplaced()) continue;
                    if (!mc.level.getBlockState(s).getCollisionShape(mc.level, s).isEmpty()) continue;
                    if (!mc.level.getBlockState(s.above()).getCollisionShape(mc.level, s.above()).isEmpty()) continue;
                    return s;
                }
        return null;
    }

    private void end(String msg) {
        running = false;
        plan = null;
        rules.clear();
        target = null;
        trip.cancel();
        input.release();
        if (msg != null) chat.accept(msg);
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
