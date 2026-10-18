package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.Brewing;
import dev.lazuli.core.craft.ItemRequest;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #brew: brews potions three bottles at a time at a brewing stand (the nearest, or one it carries or crafts and places),
 * starting from water bottles (filling glass bottles at water nearby, crafting them from glass) or from a potion part of
 * the way there; missing ingredients are fetched with #getitem first (brewGather).
 */
public final class BrewProcess implements Process {
    private static final String STAND = "minecraft:brewing_stand", BOTTLE = "minecraft:glass_bottle",
        POWDER = "minecraft:blaze_powder", WATER = "minecraft:water";
    /** Ticks with nothing brewing before it gives up; ticks filling bottles may take. */
    private static final int STALL_TICKS = 200, FILL_TICKS = 600, USE_EVERY = 6;

    private enum Phase { IDLE, TAKE, GATHER, MAKE, FILL, STATION, BREW, COLLECT, PICKUP }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final CraftProcess crafts;
    private final Station station;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private GetItemProcess getItem;
    /** Taking finished bottles from a remembered chest (chestFinal), and how many came from chests. */
    private ChestTake take;
    private int fromChests;
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private String potion;
    private Brewing.Form form;
    private int count, made, batch, step, phaseStart, usedAt, activeAt, fillTrips, settleAt;
    private Brewing.Route route;
    private boolean loaded;
    /** What #getitem was already sent for this #brew (each thing once: bottles, then the ingredients, then a stand). */
    private final java.util.Set<String> gathered = new java.util.HashSet<>();
    private BlockPos standPos, water;
    /** Water it couldn't get to this time: tried no more. */
    private final Set<BlockPos> badWater = new HashSet<>();

    public BrewProcess(PathingSession session, Settings settings, ClickDriver clicks, CraftProcess crafts, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.crafts = crafts;
        this.station = new Station(session, settings, clicks);
        this.trip = new Trip(session);
    }

    /** Takes finished bottles from remembered chests (chestFinal). */
    public void useChests(ContainerProcess containers, java.util.function.Function<Minecraft, dev.lazuli.core.storage.ChestMemory> chests,
                          java.util.function.Function<Minecraft, String> dimension) {
        this.take = new ChestTake(settings, containers, chests, dimension);
    }

    /** Fetches missing ingredients with #getitem (brewGather). */
    public void useGetItem(GetItemProcess getItem) {
        this.getItem = getItem;
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Outcome outcome() {
        return outcome;
    }

    @Override
    public String status() {
        if (phase == Phase.IDLE) return null;
        String doing = switch (phase) {
            case TAKE -> "taking some from a chest";
            case GATHER -> "getting ingredients";
            case MAKE -> "crafting";
            case FILL -> "filling bottles";
            case STATION -> "going to the brewing stand";
            case BREW, COLLECT -> "brewing " + Math.min(step, route.ingredients().size()) + "/" + route.ingredients().size();
            case PICKUP -> "picking up the stand";
            case IDLE -> "";
        };
        return "Brew " + count + " " + name(potion) + "  " + made + "/" + count + "  (" + doing + ")";
    }

    @Override
    public double progress() {
        return phase == Phase.IDLE ? -1 : (double) made / count;
    }

    /** The brewing stand the next start uses (null: the nearest, or one it places). */
    private BlockPos nextAt, fixedAt;

    public void station(BlockPos at) {
        nextAt = at;
    }

    /** Brews {@code count} bottles of {@code potion} (fire_resistance, long_swiftness, strong_healing...). */
    public void start(Minecraft mc, String potion, Brewing.Form form, int count) {
        fixedAt = nextAt;
        nextAt = null;
        stop(false);
        outcome = Outcome.NONE;
        if (!Brewing.known(potion) || Brewing.id(potion).equals(WATER)) {
            fail("Not a potion that can be brewed: " + potion);
            return;
        }
        this.potion = Brewing.id(potion);
        this.form = form;
        this.count = Math.max(1, count);
        made = fromChests = 0;
        gathered.clear();
        standPos = null;
        chat.accept(Chat.ok("Brewing {} {}{}", this.count, name(this.potion), form == Brewing.Form.DRINK ? "" : " (" + form.name().toLowerCase() + ")"));
        if (!takeFinished(mc)) plan(mc);
    }

    /** The bottle item for the form asked for. */
    private String bottleItem() {
        return switch (form) {
            case DRINK -> "minecraft:potion";
            case SPLASH -> "minecraft:splash_potion";
            case LINGERING -> "minecraft:lingering_potion";
        };
    }

    /** Starts taking finished bottles from a remembered chest (chestFinal); false if off or none known. */
    private boolean takeFinished(Minecraft mc) {
        if (take == null || !ChestTake.allowed(settings, ChestTake.Use.FINAL) || count - made <= 0) return false;
        String item = bottleItem();
        String key = dev.lazuli.core.inventory.ItemKeys.potion(item, potion);
        if (!take.start(mc, mc.player, key, count - made, pl -> held(pl, item, potion))) return false;
        enter(Phase.TAKE, mc.player);
        return true;
    }

    /** Bottles of {@code potion} as {@code item} in the main inventory and hotbar. */
    private static int held(LocalPlayer p, String item, String potion) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && Inventories.id(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).toString()).equals(item)
                && potion.equals(potion(s))) n += s.getCount();
        }
        return n;
    }

    /** Works out the next thing to do from the inventory: get ingredients, make bottles or a stand, fill, or brew. */
    private void plan(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        int left = count - made;
        if (left <= 0) {
            done(p);
            return;
        }
        batch = Math.min(3, left);
        Map<String, Integer> bottles = potions(p);
        Set<String> have = new HashSet<>();
        // (bottles of the potion itself only count when they're to be made splash or lingering)
        for (var e : bottles.entrySet()) {
            if (e.getValue() >= batch && (form != Brewing.Form.DRINK || !e.getKey().equals(potion))) have.add(e.getKey());
        }
        have.add(WATER);
        route = Brewing.route(potion, form, have);
        if (route == null) {
            fail("No way to brew " + name(potion));
            return;
        }
        Map<String, Integer> inv = Inventories.plainCounts(p);
        // the bottles to start from
        if (route.from().equals(WATER) && bottles.getOrDefault(WATER, 0) < batch) {
            int need = batch - bottles.getOrDefault(WATER, 0);
            int glass = inv.getOrDefault(BOTTLE, 0);
            if (glass >= need) {
                enter(Phase.FILL, p);
                water = null;
                fillTrips = 0;
                badWater.clear();
                return;
            }
            if (crafts.canCraft(p, BOTTLE)) {
                enter(Phase.MAKE, p);
                crafts.start(mc, List.of(new ItemCount(BOTTLE, need - glass)));
                return;
            }
            if (!gather(mc, List.of(new ItemCount(BOTTLE, need)))) fail("Needs " + need + " glass bottles (or water bottles)");
            return;
        }
        // what every batch still to brew puts in, and fuel
        int batches = (left + 2) / 3;
        Map<String, Integer> want = new HashMap<>();
        for (String i : route.ingredients()) want.merge(i, batches, Integer::sum);
        if (inv.getOrDefault(POWDER, 0) == 0) want.merge(POWDER, 1, Integer::sum);
        List<ItemCount> missing = new ArrayList<>();
        for (var e : want.entrySet()) {
            int short_ = e.getValue() - inv.getOrDefault(e.getKey(), 0);
            if (short_ > 0) missing.add(new ItemCount(e.getKey(), short_));
        }
        if (!missing.isEmpty()) {
            if (!gather(mc, missing.stream().map(m -> new ItemCount(m.item(), m.count() + inv.getOrDefault(m.item(), 0))).toList())) {
                fail("Needs " + ItemCount.describe(missing.stream().map(m -> new ItemCount(name(m.item()), m.count())).toList()));
            }
            return;
        }
        // a stand: one near, one carried, or one made
        if (standPos == null && fixedAt == null && Station.nearest(mc, p, Blocks.BREWING_STAND, settings.stationRange.get()) == null
            && inv.getOrDefault(STAND, 0) == 0) {
            if (!mayPlace()) {
                fail("No brewing stand within " + settings.stationRange.get() + " blocks, and placing one is off");
                return;
            }
            if (crafts.canCraft(p, STAND)) {
                enter(Phase.MAKE, p);
                chat.accept(Chat.info("Making a brewing stand first"));
                crafts.start(mc, List.of(new ItemCount(STAND, 1)));
                return;
            }
            if (!gather(mc, List.of(new ItemCount(STAND, 1)))) fail("No brewing stand near, and one can't be made (blaze rod, 3 cobblestone)");
            return;
        }
        enter(Phase.STATION, p);
        if (fixedAt != null) station.exact();
        station.start(p, Blocks.BREWING_STAND, STAND, "brewing stand", m -> m instanceof BrewingStandMenu,
            fixedAt != null ? fixedAt : standPos, mayPlace() && fixedAt == null);
    }

    /** Gets items with #getitem (each item once per #brew); false if that's off or they were all tried already. */
    private boolean gather(Minecraft mc, List<ItemCount> items) {
        if (getItem == null || !settings.brewGather.get()) return false;
        if (items.stream().allMatch(i -> gathered.contains(i.item()))) return false; // tried all of these already
        items.forEach(i -> gathered.add(i.item()));
        enter(Phase.GATHER, mc.player);
        chat.accept(Chat.info("Getting {} first", ItemCount.describe(items.stream().map(i -> new ItemCount(name(i.item()), i.count())).toList())));
        getItem.start(mc, new ItemRequest(items, null, false), true);
        return true;
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.MAKE) crafts.stop(false);
        if (phase == Phase.GATHER) getItem.stop(false);
        if (phase == Phase.TAKE) take.stop();
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null) {
            stop(false);
            return;
        }
        switch (phase) {
            case TAKE -> {
                switch (take.tick(mc, p)) {
                    case RUNNING -> {}
                    case CANCELLED -> end(Outcome.CANCELLED, null);
                    case DONE -> {
                        int got = Math.min(take.got(p), count - made);
                        made += got;
                        fromChests += got;
                        // got some: another chest may have the rest
                        if (got == 0 || !takeFinished(mc)) plan(mc);
                    }
                }
            }
            case GATHER -> {
                if (getItem.active()) return;
                if (getItem.outcome() == GetItemProcess.Outcome.DONE) plan(mc);
                else fail("Couldn't get what brewing needs");
            }
            case MAKE -> {
                switch (crafts.outcome()) {
                    case DONE -> plan(mc);
                    case FAILED, CANCELLED -> fail("Couldn't craft what brewing needs");
                    case NONE -> {}
                }
            }
            case FILL -> fill(mc, p);
            case STATION -> {
                switch (station.tick(mc, p)) {
                    case OPEN -> {
                        standPos = station.pos();
                        loaded = false;
                        step = 0;
                        activeAt = p.tickCount;
                        enter(Phase.BREW, p);
                    }
                    case FAILED -> fail(station.error());
                    case WORKING -> {}
                }
            }
            case BREW -> brew(mc, p);
            case COLLECT -> {
                if (clicks.busy()) return;
                made += batch;
                if (made < count) {
                    plan(mc);
                } else if (station.startPickUp(p)) {
                    enter(Phase.PICKUP, p);
                } else {
                    done(p);
                }
            }
            case PICKUP -> {
                if (station.pickUp(mc, p)) done(p);
            }
            default -> {}
        }
    }

    // ---- filling glass bottles at water ----

    private void fill(Minecraft mc, LocalPlayer p) {
        if (potions(p).getOrDefault(WATER, 0) >= batch) {
            input.release();
            trip.cancel();
            plan(mc);
            return;
        }
        if (p.tickCount - phaseStart > FILL_TICKS) {
            fail("Couldn't fill the bottles");
            return;
        }
        if (!Hands.has(p, s -> s.is(Items.GLASS_BOTTLE))) {
            plan(mc); // used up some other way: plan again
            return;
        }
        if (water == null || !source(mc, water)) {
            water = surfaceWater(mc, p);
            fillTrips = 0;
            if (water == null) {
                fail("No open water within " + settings.stationRange.get() + " blocks to fill bottles at");
                return;
            }
        }
        float[] aim = aimAtWater(mc, p, water);
        if (aim == null) {
            if (trip.running()) return;
            if (++fillTrips > 2) {
                // that one's out of reach: the next nearest, until none are left
                badWater.add(water);
                water = null;
                if (badWater.size() >= 4) fail("Can't get to any water nearby to fill bottles");
                return;
            }
            trip.go(new GoalNear(water.getX(), water.getY() + 1, water.getZ(), 2), p, true);
            return;
        }
        trip.cancel();
        if (!Hands.hold(p, clicks, s -> s.is(Items.GLASS_BOTTLE))) return;
        Aim.turn(mc, p, input, aim, settings);
        BlockHitResult h = waterHit(mc, p, RotationDriver.yaw(p), RotationDriver.pitch(p));
        if (h != null && h.getBlockPos().equals(water) && p.tickCount - usedAt >= USE_EVERY) {
            usedAt = p.tickCount;
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
    }

    /**
     * The nearest water source with open air right above it (a lake or river, not a cave pool under the ground),
     * water at about the player's level first: climbing or digging to it is the slow part.
     */
    private BlockPos surfaceWater(Minecraft mc, LocalPlayer p) {
        int r = settings.stationRange.get();
        BlockPos feet = p.blockPosition();
        return dev.lazuli.mod.world.BlockSearch.best(mc.level, feet.getX() - r, feet.getY() - r / 2, feet.getZ() - r,
            feet.getX() + r, feet.getY() + r / 2, feet.getZ() + r,
            st -> st.getFluidState().is(Fluids.WATER) && st.getFluidState().isSource(), at -> {
                if (!mc.level.getBlockState(at.above()).isAir() || badWater.contains(at)) return Double.MAX_VALUE;
                int dy = at.getY() - feet.getY();
                return at.distSqr(feet) + 8.0 * dy * dy;
            });
    }

    private static boolean source(Minecraft mc, BlockPos at) {
        var f = mc.level.getBlockState(at).getFluidState();
        return f.is(Fluids.WATER) && f.isSource();
    }

    /** Yaw and pitch at the water's surface, seen and within reach, or null. */
    private static float[] aimAtWater(Minecraft mc, LocalPlayer p, BlockPos w) {
        Vec3 eye = p.getEyePosition();
        double[][] pts = {{0.5, 0.5}, {0.2, 0.2}, {0.8, 0.8}, {0.2, 0.8}, {0.8, 0.2}};
        for (double[] q : pts) {
            Vec3 t = new Vec3(w.getX() + q[0], w.getY() + 0.85, w.getZ() + q[1]);
            if (t.distanceTo(eye) > p.blockInteractionRange()) continue;
            double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
            BlockHitResult h = waterHit(mc, p, yaw, pitch);
            if (h != null && h.getBlockPos().equals(w)) return new float[]{yaw, pitch};
        }
        return null;
    }

    /** What a glass bottle would fill from along yaw/pitch (blocks and water sources, as the game checks). */
    private static BlockHitResult waterHit(Minecraft mc, LocalPlayer p, float yaw, float pitch) {
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(Vec3.directionFromRotation(pitch, yaw).scale(p.blockInteractionRange()));
        HitResult h = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    // ---- at the stand ----

    private void brew(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof BrewingStandMenu bm)) {
            fail("The brewing stand closed");
            return;
        }
        if (clicks.busy()) return;
        int[] mine = Inventories.slots(bm, p, true);
        Menu sim = Inventories.read(bm, p);
        List<Click> plan = new ArrayList<>();
        try {
            if (!loaded) {
                // whatever was left in it comes out first (an earlier run stopped halfway, most likely)
                for (int s = 0; s < 4; s++) if (!bm.slots.get(s).getItem().isEmpty()) add(sim, plan, List.of(Click.shift(s)));
                String key = bottleKey(p, route.from());
                if (key == null) {
                    fail("The " + name(route.from()) + " bottles are gone");
                    return;
                }
                for (int s = 0; s < batch; s++) add(sim, plan, Moves.put(sim, mine, s, key, 1, Moves.Quick.EXACT));
                loaded = true;
            }
            if (bm.getFuel() == 0 && bm.slots.get(4).getItem().isEmpty()) {
                if (sim.count(mine, k -> k.equals(POWDER)) == 0) {
                    fail("Out of blaze powder to fuel the stand");
                    return;
                }
                add(sim, plan, Moves.put(sim, mine, 4, POWDER, 1, Moves.Quick.EXACT));
            }
            // the stand takes a moment to show an ingredient put in as brewing
            boolean idle = p.tickCount >= settleAt && bm.slots.get(3).getItem().isEmpty() && bm.getBrewingTicks() == 0;
            if (idle && plan.isEmpty()) {
                if (step >= route.ingredients().size()) {
                    for (int s = 0; s < batch; s++) add(sim, plan, List.of(Click.shift(s)));
                    clicks.play(bm.containerId, plan);
                    phase = Phase.COLLECT;
                    return;
                }
                add(sim, plan, Moves.put(sim, mine, 3, route.ingredients().get(step), 1, Moves.Quick.EXACT));
                step++;
                settleAt = p.tickCount + 10;
            }
        } catch (IllegalStateException e) {
            fail(e.getMessage() + " left in the inventory");
            return;
        }
        if (!plan.isEmpty() || bm.getBrewingTicks() > 0) activeAt = p.tickCount;
        if (!plan.isEmpty()) clicks.play(bm.containerId, plan);
        if (p.tickCount - activeAt > STALL_TICKS) fail("The brewing stand isn't brewing (wrong bottles in it?)");
    }

    /** The item key of a drinkable bottle of {@code potion} in the inventory, or null. */
    private static String bottleKey(LocalPlayer p, String potion) {
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.POTION) && potion.equals(potion(s))) return Inventories.key(s);
        }
        return null;
    }

    /** Drinkable bottles in the inventory by potion id (water bottles are minecraft:water). */
    private static Map<String, Integer> potions(LocalPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            String id = s.is(Items.POTION) ? potion(s) : null;
            if (id != null) out.merge(id, s.getCount(), Integer::sum);
        }
        return out;
    }

    /** A bottle's potion id, or null for none (or a custom one). */
    public static String potion(ItemStack s) {
        PotionContents pc = s.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return null;
        return pc.potion().flatMap(Holder::unwrapKey).map(k -> k.identifier().toString()).orElse(null);
    }

    private static void add(Menu sim, List<Click> plan, List<Click> clicks) {
        sim.applyAll(clicks);
        plan.addAll(clicks);
    }

    private boolean mayPlace() {
        return settings.brewPlaceStand.get() && settings.allowPlace.get();
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private void done(LocalPlayer p) {
        end(Outcome.DONE, fromChests == 0 ? Chat.ok("Brewed {} {}", made, name(potion))
            : Chat.ok("Got {} {} ({} from chests)", made, name(potion), fromChests));
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err(msg));
    }

    private void end(Outcome how, String msg) {
        boolean was = phase != Phase.IDLE;
        phase = Phase.IDLE;
        outcome = how;
        station.cancel();
        trip.cancel();
        input.release();
        if (was) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && p.containerMenu instanceof BrewingStandMenu) p.closeContainer();
        }
        if (msg != null) chat.accept(msg);
    }
}
