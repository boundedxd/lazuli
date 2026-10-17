package dev.lazuli.mod.control;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.goal.GoalAny;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #mine: finds the blocks in loaded chunks, paths next to the nearest few (the planner picks whichever is cheapest),
 * breaks them with the crosshair actually on them, and optionally walks over the drops. Runs on top of the
 * {@link PathingSession}: it starts quiet trips and takes over the controls only to aim and break.
 * <p>
 * None left (mineSearch): ores are looked for by strip mining at their best level, anything else by exploring, until
 * some come into view. With antiXray, only ores next to air or water count (the server shows the rest as stone, or
 * fakes them); strip mining uncovers real ones as it goes.
 */
public final class MineProcess implements dev.lazuli.mod.process.Process {
    /** How many of the nearest targets one trip may head for. */
    private static final int TRIP_TARGETS = 12;
    /** Targets this close to the eye are tried straight away, without a trip. */
    private static final double DIRECT = 5.0;
    /** Ticks to wait for drops to appear and be picked up after a break. */
    private static final int PICKUP_TICKS = 100;
    /** Trips toward a drop before leaving it (one it can't get to, behind a wall...). */
    private static final int PICKUP_TRIPS = 2;
    /** Walk forward only when facing within this many degrees of the drop (else it circles round it). */
    private static final float FACING = 30;
    /** Ticks breaking may take before the block counts as unbreakable from here. */
    private static final int BREAK_TIMEOUT = 600;

    private enum Phase { IDLE, TRAVEL, BREAK, PICKUP, SEARCH }

    private final PathingSession session;
    private final Settings settings;
    private final BlockView world;
    private final Consumer<String> chat;
    private final InputDriver input = new InputDriver();

    private Set<Block> targets = Set.of();
    private String what = "";
    private int quantity, mined;
    /** The best few targets found by the last {@link #rescan}, not every one in the world. */
    private final Set<Long> known = new HashSet<>();
    private int lastScan;
    private static final int KEEP = 64;
    /** Score penalty (blocks) for a target with no open side: about one block to dig through on the way. */
    private static final double BURIED = 2;
    private static final int RESCAN_TICKS = 100;
    private final Set<Long> blacklist = new HashSet<>();
    private Phase phase = Phase.IDLE;
    private List<Long> tripTargets = List.of();
    /** The trip number of the trip this process asked for (-1: none); its snapshots carry it once the session takes it. */
    private long tripStart = -1;
    private BlockPos breaking;
    private int breakTicks;
    private Vec3 dropAt;
    /** Where broken blocks' drops are still lying: picked up once nothing else is in reach (not one by one). */
    private final List<Drop> drops = new ArrayList<>();

    /** Where a block broke and when (drops show up a tick or two later). */
    private record Drop(Vec3 at, int tick) {}
    private int pickupTicks, pickupTrips, bumps;
    /** Ticks of walking straight into something before the drop is walked to by path instead. */
    private static final int BUMPS = 4;
    private boolean pickupTrip;
    private boolean driving;

    // ---- searching when none are found ----
    private final dev.lazuli.mod.process.TunnelProcess tunnel;
    private final dev.lazuli.mod.process.ExploreProcess explore;
    private final dev.lazuli.mod.process.Trip searchTrip;
    /** Targets that are ores: what anti-xray hides. */
    private Set<Block> hidden = Set.of();
    /** The level to strip-mine at, or null to explore instead; decided the first time it searches. */
    private Integer stripY;
    private dev.lazuli.core.mine.StripPattern strip;
    private boolean searched, descended, legStarted;
    private int leg, legDone, emptyLegs, searchTicks, descents;

    public MineProcess(PathingSession session, Settings settings, BlockView world, Consumer<String> chat) {
        this.tunnel = new dev.lazuli.mod.process.TunnelProcess(session, settings, m -> {});
        this.explore = new dev.lazuli.mod.process.ExploreProcess(session, m -> {});
        this.searchTrip = new dev.lazuli.mod.process.Trip(session);
        this.session = session;
        this.settings = settings;
        this.world = world;
        this.chat = chat;
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    @Override
    public String status() {
        String doing = switch (phase) {
            case TRAVEL -> "looking for";
            case BREAK -> "breaking";
            case PICKUP -> "picking up";
            case SEARCH -> stripY == null ? "exploring for more" : descended ? "strip mining at y " + stripY : "heading down to y " + stripY;
            case IDLE -> null;
        };
        if (doing == null) return null;
        String names = what.replace("minecraft:", "");
        if (names.length() > 32) names = names.substring(0, 30) + "..";
        return "Mine " + names + (quantity > 0 ? "  " + mined + "/" + quantity : "  " + mined) + "  (" + doing + ")";
    }

    @Override
    public double progress() {
        return phase == Phase.IDLE || quantity <= 0 ? -1 : Math.min(1, (double) mined / quantity);
    }

    /** How the last run ended: true if it was stopped rather than finishing (or running out of blocks to mine). */
    private boolean cancelled;

    public boolean cancelled() {
        return cancelled;
    }

    public int mined() {
        return mined;
    }

    /** What the last run was asked for: 0 means every one it could find. */
    public int quantity() {
        return quantity;
    }

    /** Starts mining the named blocks; {@code quantity} 0 means every one that can be found. */
    public void start(Minecraft mc, int quantity, List<String> names) {
        Set<Block> blocks = new LinkedHashSet<>();
        for (String n : names) {
            Identifier id = Identifier.tryParse(n.contains(":") ? n : "minecraft:" + n);
            Block b = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (b == null || b.defaultBlockState().isAir()) {
                chat.accept(Chat.err("Unknown block: {}", n));
                return;
            }
            blocks.add(b);
            // "diamond_ore" means the deepslate kind too, as players mean it
            if (id.getPath().endsWith("_ore") && !id.getPath().startsWith("deepslate_")) {
                BuiltInRegistries.BLOCK.getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), "deepslate_" + id.getPath()))
                    .ifPresent(blocks::add);
            }
        }
        stop(false);
        cancelled = false;
        targets = Set.copyOf(blocks);
        hidden = blocks.stream().filter(b -> dev.lazuli.core.mine.OreDepth.isOre(BuiltInRegistries.BLOCK.getKey(b).toString()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        searched = descended = legStarted = false;
        stripY = null;
        strip = null;
        leg = legDone = emptyLegs = searchTicks = descents = 0;
        // don't bridge with what it's mining
        java.util.Map<String, Integer> keep = new java.util.HashMap<>();
        for (Block b : blocks) if (b.asItem() != net.minecraft.world.item.Items.AIR) keep.put(BuiltInRegistries.ITEM.getKey(b.asItem()).toString(), Integer.MAX_VALUE);
        Tools.keep("mine", keep);
        what = String.join(", ", names);
        this.quantity = quantity;
        mined = 0;
        known.clear();
        blacklist.clear();
        drops.clear();
        tripStart = -1;
        tripTargets = List.of();
        pickupTrip = false;
        rescan(mc);
        phase = Phase.TRAVEL;
        chat.accept(quantity > 0 ? Chat.ok("Mining {} x{}", what, quantity) : Chat.ok("Mining {}", what));
        if (known.isEmpty()) chat.accept(Chat.warn("None in loaded chunks yet"));
    }

    /** Stops mining (and the trip it started). */
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        cancelled = true;
        boolean wasTravelling = phase == Phase.TRAVEL || phase == Phase.PICKUP;
        stopSearch();
        phase = Phase.IDLE;
        tripStart = -1;
        Tools.keep("mine", null);
        release();
        // a trip still being searched for too, or it'd be walked with nobody mining
        if (wasTravelling && (session.snapshot().state() == PathingSession.State.PATHING || session.planning())) session.stop(true);
        if (announce) chat.accept(Chat.warn("Stopped mining ({} broken)", mined));
    }

    private void finish(String msg) {
        stopSearch();
        phase = Phase.IDLE;
        Tools.keep("mine", null);
        release();
        if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
        chat.accept(msg);
    }

    // ---- block tracking ----

    /**
     * Finds the {@link #KEEP} best targets (nearest, exposed ones first) in loaded chunks, ring by ring outwards from
     * the player, stopping as soon as no farther ring can hold a better one. Common blocks like stone stop after the
     * first chunk or two instead of collecting millions of positions.
     */
    private void rescan(Minecraft mc) {
        lastScan = mc.player.tickCount;
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        // max-heap on score: the worst kept target is on top, ready to be replaced
        java.util.PriorityQueue<double[]> best = new java.util.PriorityQueue<>((a, b) -> Double.compare(b[0], a[0]));
        int r = mc.options.getEffectiveRenderDistance() + 1;
        int pcx = mc.player.blockPosition().getX() >> 4, pcz = mc.player.blockPosition().getZ() >> 4;
        for (int d = 0; d <= r; d++) {
            if (best.size() >= KEEP && (d - 1) * 16 > best.peek()[0]) break;
            for (int cx = pcx - d; cx <= pcx + d; cx++)
                for (int cz = pcz - d; cz <= pcz + d; cz++) {
                    if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != d) continue;
                    LevelChunk c = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (c != null) scan(c, px, py, pz, best);
                }
        }
        known.clear();
        for (double[] e : best) known.add((long) e[1]);
    }

    private void scan(LevelChunk chunk, double px, double py, double pz, java.util.PriorityQueue<double[]> best) {
        boolean antiXray = settings.antiXray.get();
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection sec = sections[i];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(s -> targets.contains(s.getBlock()))) continue;
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        Block blk = sec.getBlockState(x, y, z).getBlock();
                        if (!targets.contains(blk)) continue;
                        int bx = x0 + x, by = y0 + y, bz = z0 + z;
                        // anti-xray: a buried ore may be a fake (or the real ones show as stone): only trust what's seen
                        if (antiXray && hidden.contains(blk) && !exposed(bx, by, bz)) continue;
                        long p = Pos.pack(bx, by, bz);
                        if (blacklist.contains(p)) continue;
                        double score = Math.sqrt(sq(bx + 0.5 - px) + sq(by + 0.5 - py) + sq(bz + 0.5 - pz)) + penalty(bx, by, bz);
                        if (best.size() < KEEP) best.add(new double[]{score, p});
                        else if (score < best.peek()[0]) {
                            best.poll();
                            best.add(new double[]{score, p});
                        }
                    }
        }
    }

    /** Score penalty (blocks) for a target under water: slow to break, the drop floats off. */
    private static final double UNDERWATER = 24;

    /** How much worse than its distance a target is: buried, or only reachable under water. */
    private double penalty(int x, int y, int z) {
        if (!exposed(x, y, z)) return BURIED;
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) if (world.get(x + d[0], y + d[1], z + d[2]) == BlockKind.AIR) return 0;
        return UNDERWATER;
    }

    /** Next to air or water: reachable without digging to it. */
    private boolean exposed(int x, int y, int z) {
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) {
            BlockKind k = world.get(x + d[0], y + d[1], z + d[2]);
            if (k == BlockKind.AIR || k == BlockKind.WATER) return true;
        }
        return false;
    }

    /** Called for every loaded chunk while mining (new targets are picked up by the next rescan). */
    public void onChunkLoad(LevelChunk chunk) {
        explore.onChunkLoad(chunk);
    }

    /** Called whenever a block changes in the client's world. */
    public void onBlockChanged(BlockPos pos, BlockState state) {
        if (phase == Phase.IDLE) return;
        long p = Pos.pack(pos.getX(), pos.getY(), pos.getZ());
        if (!targets.contains(state.getBlock())) known.remove(p);
        // uncovered by digging (anti-xray sends the real block once it's next to air): a target now
        else if (!blacklist.contains(p) && (!settings.antiXray.get() || !hidden.contains(state.getBlock())
            || exposed(pos.getX(), pos.getY(), pos.getZ()))) known.add(p);
    }

    // ---- each tick ----

    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            stop(false);
            return;
        }
        // moving to cancel is handled once for everything in LazuliClient (not while typing in chat)
        if (quantity > 0 && mined >= quantity && phase != Phase.PICKUP) {
            if (!nextDrop(mc, player)) {
                finish(Chat.ok("Done: mined {} {}", mined, what));
                return;
            }
        }
        switch (phase) {
            case BREAK -> breakTick(mc, player);
            case PICKUP -> pickupTick(mc, player);
            case TRAVEL -> travelTick(mc, player);
            case SEARCH -> searchTick(mc, player);
            default -> {}
        }
    }

    private void travelTick(Minecraft mc, LocalPlayer player) {
        release();
        if (player.onGround()) {
            BlockPos direct = reachable(mc, player);
            if (direct != null) {
                beginBreak(direct);
                breakTick(mc, player);
                return;
            }
        }
        PathingSession.Snapshot snap = session.snapshot();
        // nothing more in reach from here: collect what's lying around before walking off
        if (tripStart < 0 && nextDrop(mc, player)) return;
        if (tripStart >= 0) {
            if (underWay(snap)) return;
            // the trip ended without a target coming into sight: don't head for it (or them, if it failed) again
            if (dev.lazuli.mod.process.Guard.interrupted(tripStart)) {
                // cut short to dodge or heal: nothing wrong with the targets
            } else if (snap.trip() == tripStart && snap.state() == PathingSession.State.ARRIVED) blacklist.add(tripTargets.get(0));
            else blacklist.addAll(tripTargets);
            tripStart = -1;
        }
        long left = known.stream().filter(q -> !blacklist.contains(q)).count();
        if (left < KEEP / 4 || player.tickCount - lastScan > RESCAN_TICKS) rescan(mc);
        List<Long> next = nearest(player, TRIP_TARGETS);
        if (next.isEmpty()) {
            if (quantity > 0 && settings.mineSearch.get() && beginSearch(mc, player)) return;
            if (searched) {
                finish(Chat.err("Searched {} minutes; no more {} found ({} mined)", settings.searchMinutes.get(), what, mined));
                return;
            }
            finish(mined > 0 ? Chat.ok("No more {} in loaded chunks ({} mined)", what, mined)
                : Chat.err("No reachable {} in loaded chunks", what));
            return;
        }
        List<Goal> goals = new ArrayList<>();
        for (long p : next) goals.add(new GoalAdjacent(Pos.x(p), Pos.y(p), Pos.z(p)));
        tripTargets = next;
        BlockPos f = player.blockPosition();
        tripStart = session.start(new GoalAny(goals, what), false, Pos.pack(f.getX(), f.getY(), f.getZ()), true);
    }

    /** None left in loaded chunks: starts (or goes on) looking for more; false once it's searched long enough. */
    private boolean beginSearch(Minecraft mc, LocalPlayer player) {
        if (searchTicks > settings.searchMinutes.get() * 1200) return false;
        phase = Phase.SEARCH;
        tripStart = -1;
        if (!searched) {
            searched = true;
            String dim = mc.level.dimension().identifier().getPath();
            for (Block b : targets) {
                Integer y = dev.lazuli.core.mine.OreDepth.bestY(BuiltInRegistries.BLOCK.getKey(b).toString(), dim,
                    mc.level.getMinY(), mc.level.getMaxY());
                if (y != null && (stripY == null || y < stripY)) stripY = y;
            }
            if (stripY != null) {
                strip = new dev.lazuli.core.mine.StripPattern(net.minecraft.core.Direction.fromYRot(RotationDriver.yaw(player)).get2DDataValue(),
                    settings.stripLength.get());
                chat.accept(Chat.info("No {} in sight; strip mining at y {}", what, stripY));
            } else {
                chat.accept(Chat.info("No {} in loaded chunks; exploring", what));
            }
        }
        return true;
    }

    private void stopSearch() {
        if (legStarted) legDone += tunnel.dug();
        legStarted = false;
        tunnel.stop(false);
        explore.stop(false);
        searchTrip.cancel();
    }

    private void searchTick(Minecraft mc, LocalPlayer player) {
        release();
        if (++searchTicks > settings.searchMinutes.get() * 1200) {
            finish(Chat.err("Searched {} minutes without finding {} ({} mined)", settings.searchMinutes.get(), what, mined));
            return;
        }
        // one came into view: mine it (the search goes on from here after)
        if (player.onGround()) {
            BlockPos direct = reachable(mc, player);
            if (direct != null) {
                stopSearch();
                beginBreak(direct);
                return;
            }
        }
        if (searchTicks % 20 == 0) {
            rescan(mc);
            if (!nearest(player, 1).isEmpty()) {
                stopSearch();
                phase = Phase.TRAVEL;
                tripStart = -1;
                return;
            }
        }
        if (stripY == null) {
            if (!explore.active()) explore.start(mc, null);
            explore.tick(mc);
            return;
        }
        if (!descended) {
            if (Math.abs(player.getBlockY() - stripY) <= 2) {
                descended = true;
            } else {
                if (searchTrip.running()) return;
                PathingSession.State how = searchTrip.ended();
                searchTrip.clear();
                if (how == null || how == PathingSession.State.IDLE && ++descents < 3) {
                    searchTrip.go(new dev.lazuli.core.goal.GoalYLevel(stripY), player);
                    return;
                }
                descended = true; // there, or no way down: strip mine from here
            }
        }
        if (tunnel.active()) {
            tunnel.tick(mc);
            return;
        }
        if (legStarted) {
            int d = tunnel.dug();
            legDone += d;
            legStarted = false;
            // the leg's done, or blocked (lava, water, a drop): on to the next one
            if (legDone >= strip.leg(leg).length() || d == 0) {
                emptyLegs = d == 0 ? emptyLegs + 1 : 0;
                if (emptyLegs >= 4) {
                    finish(Chat.err("Can't strip mine here (blocked every way); {} mined", mined));
                    return;
                }
                leg++;
                legDone = 0;
            }
        }
        dev.lazuli.core.mine.StripPattern.Leg l = strip.leg(leg);
        tunnel.start(mc, 2, 1, Math.max(1, l.length() - legDone), net.minecraft.core.Direction.from2DDataValue(l.dir()));
        legStarted = true;
        tunnel.tick(mc);
    }

    /** Our trip is still being searched for, or walked (a later trip, someone else's or a stop, ends it). */
    private boolean underWay(PathingSession.Snapshot snap) {
        return snap.trip() < tripStart || snap.trip() == tripStart && snap.state() == PathingSession.State.PATHING;
    }

    private void beginBreak(BlockPos pos) {
        stopTrip();
        phase = Phase.BREAK;
        breaking = pos;
        breakTicks = 0;
        tripStart = -1;
    }

    /**
     * No trip of its own while it aims and breaks: one still being planned would be followed a tick later, and the
     * path follower wipes the block to break every tick and fights over the keys (it just stood there).
     */
    private void stopTrip() {
        if (session.snapshot().state() == PathingSession.State.PATHING || session.planning()) session.stop(true);
    }

    private void breakTick(Minecraft mc, LocalPlayer player) {
        if (!targets.contains(mc.level.getBlockState(breaking).getBlock())) {
            mined++;
            known.remove(Pos.pack(breaking.getX(), breaking.getY(), breaking.getZ()));
            if (quantity > 0) chat.accept(Chat.info("Mined {}/{}", mined, quantity));
            tripStart = -1;
            if (settings.minePickup.get()) drops.add(new Drop(Vec3.atCenterOf(breaking), player.tickCount));
            phase = Phase.TRAVEL;
            breaking = null;
            release();
            return;
        }
        if (++breakTicks > BREAK_TIMEOUT) {
            blacklist.add(Pos.pack(breaking.getX(), breaking.getY(), breaking.getZ()));
            phase = Phase.TRAVEL;
            release();
            return;
        }
        stopTrip();
        Vec3 eye = player.getEyePosition();
        float[] aim = aimAt(mc, player, eye, breaking);
        if (aim == null) {
            phase = Phase.TRAVEL; // went out of sight (or reach): walk to it instead
            release();
            return;
        }
        float maxTurn = settings.rotationSpeed.get().floatValue();
        float yaw0 = RotationDriver.yaw(player), pitch0 = RotationDriver.pitch(player);
        BlockHitResult hit = crosshair(mc, player, eye, yaw0, pitch0);
        if (hit != null && hit.getBlockPos().equals(breaking)) {
            Tools.equipBestTool(mc, player, mc.level.getBlockState(breaking), settings);
            Interactor.mineAt(breaking, hit.getDirection());
        }
        float yaw = InputPlanner.approach(yaw0, aim[0], maxTurn);
        float pitch = pitch0 + Math.max(-maxTurn, Math.min(maxTurn, aim[1] - pitch0));
        input.apply(mc, new Controls(false, false, false, false, aim[0], aim[1], 0, false), yaw, pitch, settings.freeLook.get());
        driving = true;
    }

    private void pickupTick(Minecraft mc, LocalPlayer player) {
        release();
        pickupTicks++;
        List<ItemEntity> items = mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(dropAt, dropAt).inflate(4),
            e -> e.isAlive());
        if (items.isEmpty() && pickupTicks > 4 || pickupTicks > PICKUP_TICKS) {
            if (pickupTrip && session.snapshot().state() == PathingSession.State.PATHING) session.stop(true);
            drops.removeIf(d -> d.at().equals(dropAt));
            phase = Phase.TRAVEL;
            tripStart = -1;
            return;
        }
        if (items.isEmpty()) return; // drops take a tick or two to show up
        ItemEntity item = items.stream().filter(e -> !inPickupRange(player, e))
            .min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (item == null) {
            // all within the game's pickup range: they come in while standing here, so mine the next one meanwhile
            if (reachable(mc, player) != null) {
                phase = Phase.TRAVEL;
                tripStart = -1;
            }
            return;
        }
        PathingSession.Snapshot snap = session.snapshot();
        // close by on the same level with nothing in the way: walk straight onto it (a "near" goal counts as arrived a
        // block short of it)
        double dx = item.getX() - player.getX(), dz = item.getZ() - player.getZ();
        if (player.onGround() && player.horizontalCollision) bumps++;
        if (player.onGround() && bumps < BUMPS && dx * dx + dz * dz < 9 && walkable(mc, player, item)) {
            if (pickupTrip && snap.state() == PathingSession.State.PATHING) session.stop(true);
            pickupTrip = false;
            float yaw0 = RotationDriver.yaw(player), pitch0 = RotationDriver.pitch(player);
            float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float yaw = InputPlanner.approach(yaw0, want, settings.rotationSpeed.get().floatValue());
            // turn first, then walk: walking while still turning circles round it
            boolean facing = Math.abs(net.minecraft.util.Mth.wrapDegrees(want - yaw)) < FACING;
            input.apply(mc, new Controls(facing, false, false, false, want, pitch0), yaw, pitch0, settings.freeLook.get());
            driving = true;
            return;
        }
        if (pickupTrip) {
            if (underWay(snap)) return;
            if (dev.lazuli.mod.process.Guard.interrupted(tripStart)) {
                tripStart = -1; // cut short to dodge or heal: go for it again
                pickupTrips--;
            } else if (snap.trip() != tripStart || snap.state() != PathingSession.State.ARRIVED || pickupTrips >= PICKUP_TRIPS) {
                drops.removeIf(d -> d.at().equals(dropAt));
                phase = Phase.TRAVEL; // can't get to it: leave it
                tripStart = -1;
                return;
            }
        }
        // a first trip, or arrived and the item rolled on: go (again) to where it is now
        BlockPos ip = item.blockPosition(), f = player.blockPosition();
        // onto its block (the nearest safe spot if that can't be stood in); the last bit is walked straight, above
        tripStart = session.start(new GoalBlock(ip.getX(), ip.getY(), ip.getZ()), true, Pos.pack(f.getX(), f.getY(), f.getZ()), true);
        pickupTrip = true;
        pickupTrips++;
    }

    /**
     * Goes for the nearest drop still lying where a block was broken (forgetting ones that are gone or far behind).
     * @return whether it went into picking up
     */
    private boolean nextDrop(Minecraft mc, LocalPlayer player) {
        if (!settings.minePickup.get()) {
            drops.clear();
            return false;
        }
        drops.removeIf(d -> d.at().distanceToSqr(player.position()) > 24 * 24 || player.tickCount - d.tick() > 4
            && mc.level.getEntitiesOfClass(ItemEntity.class, new AABB(d.at(), d.at()).inflate(2), e -> e.isAlive()).isEmpty());
        if (drops.isEmpty()) return false;
        dropAt = drops.stream().map(Drop::at).min(Comparator.comparingDouble(d -> d.distanceToSqr(player.position()))).get();
        phase = Phase.PICKUP;
        pickupTicks = pickupTrips = bumps = 0;
        pickupTrip = false;
        tripStart = -1;
        return true;
    }

    /** The game picks up drops touching the player's box grown by 1 sideways and 0.5 up and down. */
    private static boolean inPickupRange(LocalPlayer player, ItemEntity item) {
        return player.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(item.getBoundingBox());
    }

    /** How close (horizontally) the body has to get for the game to pick a drop up: its reach is 1 block out. */
    private static final double PICKUP_REACH = 0.9;

    /**
     * The whole body can walk straight over to the drop: the player's box, swept along the line, never hits a block
     * (a step of a slab is fine) or walks off more than a block's drop, and the drop is no higher than a step.
     */
    private static boolean walkable(Minecraft mc, LocalPlayer player, ItemEntity item) {
        double rise = item.getY() - player.getY();
        if (rise > 0.6 || rise < -1.2) return false;
        double dx = item.getX() - player.getX(), dz = item.getZ() - player.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        double go = len - PICKUP_REACH;
        if (go <= 0) return rise > -0.5; // already in reach sideways; below that it has to be walked down to
        AABB box = player.getBoundingBox();
        double lift = 0;
        for (double t = 0.1; ; t += 0.1) {
            double f = Math.min(t, go) / len;
            AABB b = box.move(dx * f, lift, dz * f);
            if (!mc.level.noCollision(player, b)) {
                if (lift > 0 || !mc.level.noCollision(player, b.move(0, 0.6, 0))) return false;
                lift = 0.6; // stepped up a slab
                b = b.move(0, lift, 0);
            }
            if (mc.level.noCollision(player, b.expandTowards(0, -1.25, 0))) return false; // walks off a drop
            if (t >= go) return true;
        }
    }

    // ---- choosing and aiming ----

    /** The nearest targets not ruled out, safe to open up (no lava next to them). */
    private List<Long> nearest(LocalPlayer player, int n) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        return known.stream().filter(p -> !blacklist.contains(p) && safe(p))
            .sorted(Comparator.comparingDouble(p -> Math.sqrt(sq(Pos.x(p) + 0.5 - px) + sq(Pos.y(p) + 0.5 - py) + sq(Pos.z(p) + 0.5 - pz))
                + penalty(Pos.x(p), Pos.y(p), Pos.z(p))))
            .limit(n).toList();
    }

    private boolean safe(long p) {
        int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
        int[][] around = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : around) if (world.get(x + d[0], y + d[1], z + d[2]) == BlockKind.DANGER) return false;
        return true;
    }

    /**
     * A target the eye can see within reach right now, nearest first: any of them in the world around, not just the
     * cached best few, so the next block next to the last one is mined at once instead of after a trip.
     */
    private BlockPos reachable(Minecraft mc, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        int r = (int) Math.ceil(DIRECT);
        BlockPos e = BlockPos.containing(eye), feet = player.blockPosition();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        java.util.List<double[]> found = new java.util.ArrayList<>();
        for (int dy = -r; dy <= r; dy++)
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    m.set(e.getX() + dx, e.getY() + dy, e.getZ() + dz);
                    double d = eye.distanceToSqr(m.getX() + 0.5, m.getY() + 0.5, m.getZ() + 0.5);
                    if (d > DIRECT * DIRECT || !targets.contains(mc.level.getBlockState(m).getBlock())) continue;
                    long p = Pos.pack(m.getX(), m.getY(), m.getZ());
                    if (blacklist.contains(p) || !safe(p)) continue;
                    // the block underfoot only with solid ground right below it (a one-block drop, not a cave)
                    if (m.getX() == feet.getX() && m.getZ() == feet.getZ() && m.getY() == feet.getY() - 1
                        && world.get(m.getX(), m.getY() - 1, m.getZ()) != BlockKind.SOLID) continue;
                    found.add(new double[]{d, p});
                }
        found.sort(Comparator.comparingDouble(f -> f[0]));
        for (double[] f : found) {
            long p = (long) f[1];
            BlockPos bp = new BlockPos(Pos.x(p), Pos.y(p), Pos.z(p));
            if (aimAt(mc, player, eye, bp) != null) {
                known.add(p);
                return bp;
            }
        }
        return null;
    }

    /** Yaw and pitch putting the crosshair on a visible part of the block within reach, or null. */
    private static float[] aimAt(Minecraft mc, LocalPlayer player, Vec3 eye, BlockPos bp) {
        double[][] pts = {{0.5, 0.5, 0.5}, {0.5, 0.95, 0.5}, {0.5, 0.05, 0.5}, {0.05, 0.5, 0.5}, {0.95, 0.5, 0.5},
            {0.5, 0.5, 0.05}, {0.5, 0.5, 0.95}};
        double reach = player.blockInteractionRange();
        for (double[] q : pts) {
            Vec3 t = new Vec3(bp.getX() + q[0], bp.getY() + q[1], bp.getZ() + q[2]);
            if (t.distanceTo(eye) > reach) continue;
            double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            BlockHitResult h = crosshair(mc, player, eye, yaw, pitch);
            if (h != null && h.getBlockPos().equals(bp)) return new float[]{yaw, pitch};
        }
        return null;
    }

    private static BlockHitResult crosshair(Minecraft mc, LocalPlayer player, Vec3 eye, float yaw, float pitch) {
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(player.blockInteractionRange())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    private void release() {
        if (driving) input.release();
        driving = false;
    }

    private static double sq(double d) {
        return d * d;
    }
}
