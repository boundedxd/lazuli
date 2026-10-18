package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.craft.Drops;
import dev.lazuli.core.craft.ItemPlanner;
import dev.lazuli.core.craft.ItemRequest;
import dev.lazuli.core.craft.Recipes;
import dev.lazuli.core.goal.GoalNear;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.storage.ChestMemory;
import dev.lazuli.core.trade.Offer;
import dev.lazuli.core.trade.TradeMemory;
import dev.lazuli.core.trade.TradePlanner;
import dev.lazuli.core.trade.Trader;
import dev.lazuli.core.trade.Want;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.MineProcess;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.craft.RecipeData;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import dev.lazuli.mod.inventory.TradeWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * #trade: gets items (enchanted books too) from villagers. Looks at the villagers around whose trades aren't known
 * yet, plans with {@link TradePlanner} from the trades seen, then does the first step: get things to pay with
 * (#getitem; optionally from the chests around the villagers that buy them), or walk to a villager, open its screen,
 * pick the trade and take the result as many times as planned. Plans again after each step, like #getitem.
 */
public final class TradeProcess implements Process {
    private static final int MAX_STEPS = 60, GO_TICKS = 20 * 90, OPEN_TICKS = 100, TRIES = 3;
    /** Chests looked in around a villager that buys what's needed (blocks). */
    private static final int LOOT_RADIUS = 16, LOOT_MAX = 8;

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private enum Phase { IDLE, PLAN, GATHER, LOOT, GO, OPEN, TRADE, RESTOCK }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private final GetItemProcess gather;
    private final ContainerProcess looker;
    private final Function<Minecraft, TradeMemory> memory;
    private final Function<Minecraft, ChestMemory> chests;
    private final Function<Minecraft, String> dimension;
    private final SmeltProcess smelts;
    private TradeWatcher watcher;

    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private List<Want> wants = List.of();
    /** Villagers to look at before planning (whose trades aren't known); only looking: no wants. */
    private final Deque<String> toScan = new ArrayDeque<>();
    private boolean scanOnly, scannedAll, looted;
    private final Deque<BlockPos> toLoot = new ArrayDeque<>();
    /** The step under way: a trade with {@code target}, or just a look at it (null step). */
    private TradePlanner.Step step;
    private String target;
    private BlockPos lastSeen;
    private int phaseStart, steps, tries, reselects, gotBefore, lastSelect;
    private boolean clicked;
    /** #trade sell: what, and how many to keep (the rest goes); null when buying. */
    private String sellItem;
    private int sellKeep, sellHad;
    /** When it started waiting for a restock (-1: not waiting). */
    private int restockSince = -1;
    private static final int RESTOCK_LOOK = 20 * 60;
    /** Villagers that wouldn't open (busy, asleep, unreachable): skipped this run. */
    private final Set<String> skip = new HashSet<>();

    public TradeProcess(PathingSession session, Settings settings, ClickDriver clicks, MineProcess mine, CraftProcess crafts,
                        SmeltProcess smelts, ContainerProcess containers, Function<Minecraft, TradeMemory> memory,
                        Function<Minecraft, ChestMemory> chests, Function<Minecraft, String> dimension, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.trip = new Trip(session);
        this.memory = memory;
        this.chests = chests;
        this.dimension = dimension;
        this.smelts = smelts;
        this.gather = new GetItemProcess(session, settings, mine, crafts, smelts, chat);
        this.gather.useChests(containers, chests, dimension);
        this.looker = new ContainerProcess(session, settings, clicks, m -> {});
    }

    /** Where it learns which villager a screen belongs to. */
    public void watch(TradeWatcher watcher) {
        this.watcher = watcher;
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
        String what = scanOnly ? "Looking at villagers' trades" : sellItem != null ? "Sell " + name(sellItem) : "Trade for " + describe(wants);
        return switch (phase) {
            case RESTOCK -> what + "  waiting for a restock";
            case GATHER -> what + "  getting " + step.item().count() + " " + name(step.item().item());
            case LOOT -> what + "  looking in chests";
            case GO, OPEN, TRADE -> what + (step == null ? "  looking at a villager (" + toScan.size() + " more)"
                : "  " + step.offer().describe() + " x" + step.times());
            default -> what;
        };
    }

    /** Looks at the villagers around whose trades aren't known (#trade scan). */
    public void scan(Minecraft mc) {
        begin(mc, List.of(), true);
    }

    public void start(Minecraft mc, List<Want> wants) {
        sellItem = null;
        begin(mc, wants, false);
    }

    /** Sells {@code count} of {@code item} (negative: all of it) to the best-paying villagers around. */
    public void sell(Minecraft mc, String item, int count) {
        if (item.equals(TradePlanner.EMERALD)) {
            // "sell for emeralds": whatever on hand the villagers take (count: emeralds to make; all: no limit)
            sellItem = item;
            sellHad = Inventories.plainCounts(mc.player).getOrDefault(item, 0);
            sellKeep = Math.max(0, count);
            begin(mc, List.of(), false);
            return;
        }
        sellItem = item;
        sellHad = Inventories.plainCounts(mc.player).getOrDefault(item, 0);
        sellKeep = count < 0 ? 0 : Math.max(0, sellHad - count);
        if (sellHad <= sellKeep) {
            stop(false);
            outcome = Outcome.FAILED;
            chat.accept(Chat.err("No {} to sell", name(item)));
            return;
        }
        begin(mc, List.of(), false);
    }

    private void begin(Minecraft mc, List<Want> wants, boolean scanOnly) {
        stop(false);
        outcome = Outcome.NONE;
        roomThrows = 0;
        this.wants = List.copyOf(wants);
        this.scanOnly = scanOnly;
        scannedAll = looted = false;
        restockSince = -1;
        steps = 0;
        skip.clear();
        refreshed.clear();
        toScan.clear();
        toLoot.clear();
        RecipeData.get();
        RecipeData.drops();
        // trades already known come first: the villagers not looked at yet only when those can't do it (see plan)
        if (scanOnly) queueUnknown(mc, v -> true, Integer.MAX_VALUE);
        if (scanOnly && toScan.isEmpty()) {
            end(Outcome.DONE, Chat.ok("Every villager around has been looked at ({} known)", memory.apply(mc).size()));
            return;
        }
        chat.accept(scanOnly ? Chat.ok("Looking at {} villagers' trades", toScan.size())
            : sellingAny() ? Chat.ok("Selling what's on hand for {}", sellKeep > 0 ? sellKeep + " emeralds" : "emeralds")
            : sellItem != null ? Chat.ok("Selling {} {}", sellHad - sellKeep, name(sellItem)) : Chat.ok("Trading for {}", describe(wants)));
        next(mc);
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (gather.active()) gather.stop(false);
        if (looker.active()) looker.stop(false);
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null) return;
        switch (phase) {
            case PLAN -> plan(mc, p);
            case GATHER -> {
                gather.tick(mc);
                if (gather.active()) return;
                switch (gather.outcome()) {
                    case DONE -> enter(Phase.PLAN, p);
                    case CANCELLED -> end(Outcome.CANCELLED, null);
                    default -> end(Outcome.FAILED, Chat.err("Couldn't get {} {} to trade with", step.item().count(), name(step.item().item())));
                }
            }
            case LOOT -> loot(mc, p);
            case GO -> go(mc, p);
            case OPEN -> open(mc, p);
            case TRADE -> trade(mc, p);
            case RESTOCK -> restock(mc, p);
            default -> {}
        }
    }

    // ---- planning ----

    /** Looks at the next unknown villager, or plans. */
    private void next(Minecraft mc) {
        LocalPlayer p = mc.player;
        step = null;
        if (!toScan.isEmpty()) {
            target = toScan.poll();
            lastSeen = null;
            tries = 0;
            enter(Phase.GO, p);
            return;
        }
        if (scanOnly) {
            end(Outcome.DONE, Chat.ok("Looked at the villagers around ({} known)", memory.apply(mc).size()));
            return;
        }
        enter(Phase.PLAN, p);
    }

    private void plan(Minecraft mc, LocalPlayer p) {
        boolean merchant = p.containerMenu instanceof MerchantMenu;
        if (p.containerMenu != p.inventoryMenu && !(merchant && settings.tradeStayOpen.get())) return; // a screen still closing
        Recipes recipes = RecipeData.get().getNow(null);
        Drops drops = RecipeData.drops().getNow(null);
        if (recipes == null || drops == null) return;
        if (++steps > MAX_STEPS) {
            end(Outcome.FAILED, Chat.err("Gave up after {} steps", MAX_STEPS));
            return;
        }
        if (sellingAny()) {
            sellAnyPlan(mc, p);
            return;
        }
        if (sellItem != null) {
            sellPlan(mc, p);
            return;
        }
        boolean wait = settings.tradeRestockMinutes.get() > 0;
        TradePlanner.Plan plan = plan(mc, p, wants, recipes, drops, wait);
        if (plan.ok() && plan.steps().isEmpty() && plan.soldOut()) {
            // bought what there was: wait for the villagers to restock
            if (restockSince < 0) {
                restockSince = p.tickCount;
                chat.accept(Chat.info("Sold out; waiting up to {} min for a restock", settings.tradeRestockMinutes.get()));
            }
            steps = 0;
            if (merchant) p.closeContainer();
            enter(Phase.RESTOCK, p);
            return;
        }
        if (!plan.ok()) {
            // maybe one not looked at yet buys or sells it: look at every villager around once, then plan again
            if (scanMore(mc)) return;
            end(Outcome.FAILED, Chat.err("Can't trade for {}: {}", describe(wants), plan.problem()));
            return;
        }
        if (plan.steps().isEmpty()) {
            end(Outcome.DONE, Chat.ok("Got {}", describe(wants)));
            return;
        }
        step = plan.steps().get(0);
        if (step.kind() == TradePlanner.Step.Kind.GATHER) {
            ItemCount it = step.item();
            if (merchant) p.closeContainer();
            if (settings.tradeLootChests.get() && !looted && queueLoot(mc, p, it.item(), plan)) {
                looted = true;
                chat.accept(Chat.info("Looking in {} chests near the villagers for {}", toLoot.size(), name(it.item())));
                enter(Phase.LOOT, p);
                return;
            }
            chat.accept(Chat.info("Getting {} {} to trade", it.count(), name(it.item())));
            int have = Inventories.plainCounts(p).getOrDefault(it.item(), 0);
            gather.start(mc, ItemRequest.of(it.item(), have + it.count()));
            enter(Phase.GATHER, p);
            return;
        }
        goTrade(mc, p);
    }

    /** Off to the step's villager; with its screen open already (tradeStayOpen), straight on trading. */
    private void goTrade(Minecraft mc, LocalPlayer p) {
        chat.accept(Chat.info("Trading {} x{} with a {}", step.offer().describe(), step.times(), step.trader().profession()));
        target = step.trader().id();
        lastSeen = new BlockPos(step.trader().x(), step.trader().y(), step.trader().z());
        tries = 0;
        if (p.containerMenu instanceof MerchantMenu) {
            if (watcher != null && watcher.trading() != null && watcher.trading().getUUID().toString().equals(target)) {
                startTrading(p);
                return;
            }
            p.closeContainer();
        }
        enter(Phase.GO, p);
    }

    /** If tradeScanUnknown: queues every villager around not looked at yet (once a run); true if there are some. */
    /** Villagers looked at again this run because their buying trades were sold out when last seen. */
    private final java.util.Set<String> refreshed = new java.util.HashSet<>();

    /**
     * Nothing to sell to: villagers known to buy it whose trades were sold out when last seen may have restocked since
     * (the memory only knows the last look), so they're looked at again before looking at strangers.
     */
    private boolean refreshSoldOut(Minecraft mc, LocalPlayer p, String item) {
        for (Trader t : traders(mc, p)) {
            if (refreshed.contains(t.id())) continue;
            boolean stale = t.offers().stream().anyMatch(o -> o.left() <= 0 && o.costB() == null && o.result().item().equals(TradePlanner.EMERALD)
                && (item == null || o.costA().item().equals(item)));
            if (stale) {
                refreshed.add(t.id());
                toScan.add(t.id());
            }
        }
        if (toScan.isEmpty()) return false;
        if (p.containerMenu instanceof MerchantMenu) p.closeContainer();
        chat.accept(Chat.info("Checking whether {} villager(s) restocked", toScan.size()));
        next(mc);
        return true;
    }

    private boolean scanMore(Minecraft mc) {
        if (scannedAll || !settings.tradeScanUnknown.get()) return false;
        scannedAll = true;
        queueUnknown(mc, v -> true, Integer.MAX_VALUE);
        if (toScan.isEmpty()) return false;
        if (mc.player.containerMenu instanceof MerchantMenu) mc.player.closeContainer();
        chat.accept(Chat.info("Looking at {} more villagers' trades", toScan.size()));
        next(mc);
        return true;
    }

    private boolean sellingAny() {
        return TradePlanner.EMERALD.equals(sellItem);
    }

    /** #trade sell emerald: whatever on hand the villagers around buy, until done or out. */
    private void sellAnyPlan(Minecraft mc, LocalPlayer p) {
        int made = Inventories.plainCounts(p).getOrDefault(TradePlanner.EMERALD, 0) - sellHad;
        if (sellKeep > 0 && made >= sellKeep) {
            end(Outcome.DONE, Chat.ok("Made {} emeralds", made));
            return;
        }
        BlockPos f = p.blockPosition();
        TradePlanner.Plan plan = TradePlanner.sellAny(Inventories.plainCounts(p), traders(mc, p), f.getX(), f.getY(), f.getZ(),
            this::maySell, sellKeep > 0 ? sellKeep - made : 0);
        if (!plan.ok()) {
            if (refreshSoldOut(mc, p, null) || scanMore(mc)) return;
            if (made > 0) end(Outcome.DONE, Chat.ok("Made {} emeralds (nothing more on hand that they buy)", made));
            else end(Outcome.FAILED, Chat.err("Can't sell for emeralds: {}", plan.problem()));
            return;
        }
        step = plan.steps().get(0);
        goTrade(mc, p);
    }

    /** May be sold for emeralds: not in tradeNeverSell, and in tradeSellOnly when that's set. */
    private boolean maySell(String item) {
        if (list(settings.tradeNeverSell.get()).contains(item)) return false;
        List<String> only = list(settings.tradeSellOnly.get());
        return only.isEmpty() || only.contains(item);
    }

    private static List<String> list(String csv) {
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            s = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!s.isEmpty()) out.add(s.contains(":") ? s : "minecraft:" + s);
        }
        return out;
    }

    /**
     * What the planner may use of a villager: no buying trades dearer than tradeMaxEmeralds, and no selling (for
     * emeralds) what mustn't be sold.
     */
    private Trader allowed(Trader t) {
        int max = settings.tradeMaxEmeralds.get();
        List<Offer> ok = new ArrayList<>();
        for (Offer o : t.offers()) {
            boolean buying = o.costA().item().equals(TradePlanner.EMERALD);
            if (buying && o.costA().count() > max) continue;
            if (!buying && o.result().item().equals(TradePlanner.EMERALD) && !maySell(o.costA().item())) continue;
            ok.add(o);
        }
        return new Trader(t.id(), t.profession(), t.dim(), t.x(), t.y(), t.z(), ok);
    }

    /** Waiting for a restock: every minute, look at the villagers that sell what's wanted again, then plan. */
    private void restock(Minecraft mc, LocalPlayer p) {
        if (p.tickCount - restockSince > settings.tradeRestockMinutes.get() * 60 * 20) {
            end(Outcome.FAILED, Chat.err("Still sold out after {} min", settings.tradeRestockMinutes.get()));
            return;
        }
        if (p.tickCount - phaseStart < RESTOCK_LOOK) return;
        BlockPos f = p.blockPosition();
        for (Trader t : memory.apply(mc).near(dimension.apply(mc), f.getX(), f.getY(), f.getZ())) {
            if (skip.contains(t.id()) || toScan.contains(t.id())) continue;
            if (t.offers().stream().anyMatch(o -> wants.stream().anyMatch(o::gives))) toScan.add(t.id());
        }
        next(mc);
    }

    /** #trade sell: the best-paying buyer for what's left to sell, or done. */
    private void sellPlan(Minecraft mc, LocalPlayer p) {
        int have = Inventories.plainCounts(p).getOrDefault(sellItem, 0), sold = sellHad - have;
        if (have <= sellKeep) {
            end(Outcome.DONE, Chat.ok("Sold {} {}", sold, name(sellItem)));
            return;
        }
        BlockPos f = p.blockPosition();
        TradePlanner.Plan plan = TradePlanner.sell(sellItem, have - sellKeep, Inventories.plainCounts(p), traders(mc, p),
            f.getX(), f.getY(), f.getZ());
        if (!plan.ok()) {
            if (refreshSoldOut(mc, p, sellItem) || scanMore(mc)) return;
            if (sold > 0) end(Outcome.DONE, Chat.ok("Sold {} {} ({})", sold, name(sellItem), plan.problem()));
            else end(Outcome.FAILED, Chat.err("Can't sell {}: {}", name(sellItem), plan.problem()));
            return;
        }
        step = plan.steps().get(0);
        goTrade(mc, p);
    }

    /** The known villagers in range (not skipped this run). */
    private List<Trader> traders(Minecraft mc, LocalPlayer p) {
        BlockPos f = p.blockPosition();
        int range = settings.tradeRange.get();
        return memory.apply(mc).near(dimension.apply(mc), f.getX(), f.getY(), f.getZ()).stream()
            .filter(t -> t.distSq(f.getX(), f.getY(), f.getZ()) <= (double) range * range && !skip.contains(t.id())).toList();
    }

    /** A plan for these wants from the trades known around (also what #getitem asks to compare with making them). */
    TradePlanner.Plan plan(Minecraft mc, LocalPlayer p, List<Want> wants, Recipes recipes, Drops drops) {
        return plan(mc, p, wants, recipes, drops, false);
    }

    private TradePlanner.Plan plan(Minecraft mc, LocalPlayer p, List<Want> wants, Recipes recipes, Drops drops, boolean partial) {
        BlockPos f = p.blockPosition();
        int range = settings.tradeRange.get();
        List<Trader> traders = memory.apply(mc).near(dimension.apply(mc), f.getX(), f.getY(), f.getZ()).stream()
            .filter(t -> t.distSq(f.getX(), f.getY(), f.getZ()) <= (double) range * range && !skip.contains(t.id())).map(this::allowed).toList();
        return TradePlanner.plan(wants, w -> TradeWatcher.count(p, w), Inventories.plainCounts(p), traders,
            f.getX(), f.getY(), f.getZ(), settings.tradeForEmeralds.get(), gatherCost(mc, p, recipes, drops), partial);
    }

    /** Rough work a trade plan takes: getting what it pays with, and each trade (walking there included). */
    double cost(LocalPlayer p, TradePlanner.Plan plan, ToDoubleFunction<String> gatherCost) {
        double c = 0;
        BlockPos f = p.blockPosition();
        for (TradePlanner.Step s : plan.steps()) {
            if (s.kind() == TradePlanner.Step.Kind.GATHER) c += s.item().count() * gatherCost.applyAsDouble(s.item().item());
            else c += s.times() * 0.5 + Math.sqrt(s.trader().distSq(f.getX(), f.getY(), f.getZ())) * 0.05;
        }
        return c;
    }

    /** Rough work to get one of an item: next to nothing in a remembered chest, else what #getitem would do. */
    ToDoubleFunction<String> gatherCost(Minecraft mc, LocalPlayer p, Recipes recipes, Drops drops) {
        ItemPlanner planner = new ItemPlanner(recipes, drops, GetItemProcess::tier, SmeltProcess.burn(mc), smelts.fuels(),
            new ItemPlanner.Rules(settings.getItemMine.get(), settings.getItemSmelt.get(),
                settings.craftPlaceTable.get() && settings.allowPlace.get(), settings.smeltPlaceFurnace.get() && settings.allowPlace.get()));
        Map<String, Integer> have = Inventories.plainCounts(p);
        Map<String, Integer> stored = ChestTake.stored(settings, chests.apply(mc), dimension.apply(mc), p.blockPosition(), ChestTake.Use.INGREDIENT);
        int pick = GetItemProcess.pickTier(p);
        Map<String, Double> memo = new java.util.HashMap<>();
        return item -> memo.computeIfAbsent(item, i -> stored.getOrDefault(i, 0) > 0 ? 0.05 : planner.cost(i, have, pick));
    }

    /** Villagers in range whose trades aren't known yet (and {@code which} takes), nearest first, at most {@code max}. */
    private void queueUnknown(Minecraft mc, java.util.function.Predicate<AbstractVillager> which, int max) {
        LocalPlayer p = mc.player;
        TradeMemory mem = memory.apply(mc);
        int range = settings.tradeRange.get();
        List<AbstractVillager> found = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof AbstractVillager v) || !TradeWatcher.trades(v) || !which.test(v)) continue;
            String id = v.getUUID().toString();
            if (mem.get(id) != null || skip.contains(id) || toScan.contains(id)) continue;
            if (v.distanceToSqr(p) > (double) range * range) continue;
            found.add(v);
        }
        found.sort(java.util.Comparator.comparingDouble(v -> v.distanceToSqr(p)));
        for (int i = 0; i < Math.min(max, found.size()); i++) toScan.add(found.get(i).getUUID().toString());
    }

    /** Chests near the villagers this plan sells {@code item} to, not opened before. */
    private boolean queueLoot(Minecraft mc, LocalPlayer p, String item, TradePlanner.Plan plan) {
        toLoot.clear();
        ChestMemory mem = chests.apply(mc);
        String dim = dimension.apply(mc);
        for (TradePlanner.Step s : plan.steps()) {
            if (s.kind() != TradePlanner.Step.Kind.TRADE || !s.offer().costA().item().equals(item)) continue;
            Trader t = s.trader();
            for (int cx = (t.x() - LOOT_RADIUS) >> 4; cx <= (t.x() + LOOT_RADIUS) >> 4; cx++) {
                for (int cz = (t.z() - LOOT_RADIUS) >> 4; cz <= (t.z() + LOOT_RADIUS) >> 4; cz++) {
                    if (!(mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (!(be instanceof ChestBlockEntity) && !(be instanceof BarrelBlockEntity)) continue;
                        BlockPos bp = be.getBlockPos();
                        if (Math.abs(bp.getX() - t.x()) > LOOT_RADIUS || Math.abs(bp.getZ() - t.z()) > LOOT_RADIUS
                            || Math.abs(bp.getY() - t.y()) > 8) continue;
                        if (mem.known(dim, bp.getX(), bp.getY(), bp.getZ()) || toLoot.contains(bp)) continue;
                        if (toLoot.size() < LOOT_MAX) toLoot.add(bp);
                    }
                }
            }
        }
        return !toLoot.isEmpty();
    }

    private void loot(Minecraft mc, LocalPlayer p) {
        looker.tick(mc);
        if (looker.active()) return;
        if (toLoot.isEmpty() || !settings.chestMemory.get()) {
            enter(Phase.PLAN, p); // what turned up is in the chest memory now: #getitem takes it from there
            return;
        }
        // opening it is enough: the chest memory records what's inside (and it takes some, if it's there)
        looker.start(mc, false, List.of(new ItemCount(step.item().item(), step.item().count())), toLoot.poll());
    }

    // ---- walking up to a villager and trading ----

    private void go(Minecraft mc, LocalPlayer p) {
        AbstractVillager v = find(mc, target);
        if (v != null) {
            lastSeen = v.blockPosition();
            memory.apply(mc).moved(target, lastSeen.getX(), lastSeen.getY(), lastSeen.getZ());
            if (p.isWithinEntityInteractionRange(v, -0.5) && p.hasLineOfSight(v)) {
                trip.cancel();
                enter(Phase.OPEN, p);
                return;
            }
        }
        if (p.tickCount - phaseStart > GO_TICKS || lastSeen == null) {
            giveUpOn(mc, "couldn't get to it");
            return;
        }
        // (again) when the villager wandered off from where the trip goes
        boolean moved = v != null && trip.running() && goal != null && goal.distSqr(lastSeen) > 9;
        if (trip.running() && !moved) return;
        PathingSession.State how = trip.ended();
        trip.clear();
        if (how == PathingSession.State.IDLE && goal != null && !moved) {
            end(Outcome.CANCELLED, null); // stopped from outside
            return;
        }
        if (v == null && how == PathingSession.State.ARRIVED) {
            giveUpOn(mc, "it's not there any more");
            return;
        }
        if (how == PathingSession.State.FAILED && ++tries > TRIES) {
            giveUpOn(mc, "no way to it");
            return;
        }
        goal = lastSeen;
        trip.go(new GoalNear(goal.getX(), goal.getY(), goal.getZ(), 2), p, true);
    }

    private BlockPos goal;

    private void open(Minecraft mc, LocalPlayer p) {
        if (p.containerMenu instanceof MerchantMenu) {
            startTrading(p);
            return;
        }
        AbstractVillager v = find(mc, target);
        if (v == null || !p.isWithinEntityInteractionRange(v, 0)) {
            enter(Phase.GO, p);
            return;
        }
        if (p.tickCount - phaseStart > OPEN_TICKS) {
            giveUpOn(mc, "it wouldn't trade (busy, or asleep?)");
            return;
        }
        // look at it, then right-click it, as a player would
        Vec3 at = v.getEyePosition().add(0, -0.4, 0);
        double dx = at.x - p.getX(), dy = at.y - p.getEyeY(), dz = at.z - p.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        float cy = RotationDriver.yaw(p), cp = RotationDriver.pitch(p);
        float turn = settings.rotationSpeed.get().floatValue();
        float ny = InputPlanner.approach(cy, yaw, turn), np = cp + Math.max(-turn, Math.min(turn, pitch - cp));
        input.apply(mc, new Controls(false, false, false, false, yaw, pitch), ny, np, settings.freeLook.get());
        boolean onIt = Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - ny)) < 8 && Math.abs(pitch - np) < 8;
        if (onIt && (!clicked || (p.tickCount - phaseStart) % 20 == 0)) {
            mc.gameMode.interact(p, v, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            clicked = true;
        }
    }

    /** Quick-moves of the trade's result in a row that left it in the slot. */
    private int shifts;

    private void startTrading(LocalPlayer p) {
        shifts = 0;
        input.release();
        reselects = 0;
        lastSelect = -100;
        gotBefore = -1;
        enter(Phase.TRADE, p);
    }

    private void trade(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof MerchantMenu menu)) {
            // closed (by the villager, or by hand)
            if (step == null) next(mc);
            else enter(Phase.PLAN, p);
            return;
        }
        MerchantOffers offers = menu.getOffers();
        if (offers.isEmpty()) {
            if (p.tickCount - phaseStart > 60) close(mc, p);
            return; // they come a moment after the screen
        }
        if (watcher != null && watcher.trading() != null) {
            memory.apply(mc).record(watcherTrader(mc, offers));
        }
        if (step == null) { // only looking
            close(mc, p);
            return;
        }
        int index = index(offers, step.offer());
        if (index < 0) {
            close(mc, p);
            return; // not offered any more (sold out, or changed): plan again with what was just seen
        }
        Offer o = TradeWatcher.offer(index, offers.get(index));
        Want made = o.enchant() != null ? new Want(o.result().item(), o.enchant(), o.level(), 1) : new Want(o.result().item(), null, 0, 1);
        int got = TradeWatcher.count(p, made) + (TradeWatcher.matches(menu.getCarried(), made) ? menu.getCarried().getCount() : 0);
        if (gotBefore < 0) gotBefore = got;
        int done = (got - gotBefore) / Math.max(1, o.result().count());
        if (clicks.busy()) return;
        if (done >= step.times()) {
            if (settings.tradeStayOpen.get()) enter(Phase.PLAN, p); // the next trade may be with this villager too
            else close(mc, p);
            return;
        }
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            int slot = room(menu, carried);
            if (slot < 0) {
                if (throwJunk(mc, p)) return; // the inventory's full: a stack of junk goes, then on
                close(mc, p);
                end(Outcome.FAILED, Chat.err("Inventory full"));
                return;
            }
            clicks.play(menu.containerId, List.of(Click.left(slot)));
            return;
        }
        ItemStack result = menu.getSlot(2).getItem();
        if (!result.isEmpty()) {
            reselects = 0;
            // one quick-move click sends the result into the inventory (no picking up and putting down); it stays
            // there when the inventory has no room for it
            if (++shifts > 4) {
                shifts = 0;
                if (throwJunk(mc, p)) return;
                close(mc, p);
                end(Outcome.FAILED, Chat.err("Inventory full"));
                return;
            }
            clicks.play(menu.containerId, List.of(Click.shift(2)));
            return;
        }
        shifts = 0;
        // pick the trade: the game moves what it costs into the payment slots
        if (p.tickCount - lastSelect < 5) return;
        if (++reselects > 5) {
            close(mc, p); // out of what it costs, or it sold out: plan again
            return;
        }
        lastSelect = p.tickCount;
        menu.setSelectionHint(index);
        menu.tryMoveItems(index);
        mc.getConnection().send(new ServerboundSelectTradePacket(index));
    }

    private Trader watcherTrader(Minecraft mc, MerchantOffers offers) {
        AbstractVillager v = watcher.trading();
        List<Offer> out = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) out.add(TradeWatcher.offer(i, offers.get(i)));
        return new Trader(v.getUUID().toString(), TradeWatcher.profession(v), dimension.apply(mc), v.getBlockX(), v.getBlockY(), v.getBlockZ(), out);
    }

    /** Where the planned offer is in the list now (same result, enchantment and payment), or -1 if it's gone or sold out. */
    private static int index(MerchantOffers offers, Offer want) {
        for (int i = 0; i < offers.size(); i++) {
            Offer o = TradeWatcher.offer(i, offers.get(i));
            if (o.left() <= 0) continue;
            if (o.result().item().equals(want.result().item()) && java.util.Objects.equals(o.enchant(), want.enchant())
                && o.level() == want.level() && o.costA().item().equals(want.costA().item())) return i;
        }
        return -1;
    }

    /** An inventory slot of the menu (3..38) the carried stack fits into: a matching stack with room, else an empty one. */
    private static int room(MerchantMenu menu, ItemStack carried) {
        int empty = -1;
        for (int i = 3; i < menu.slots.size(); i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty()) {
                if (empty < 0) empty = i;
            } else if (ItemStack.isSameItemSameComponents(s, carried) && s.getCount() < s.getMaxStackSize()) {
                return i;
            }
        }
        return empty;
    }

    /** Closes the screen and plans again (only looking: on to the next villager). */
    /** Junk stacks thrown out to make room in this run (makeRoomDropJunk). */
    private int roomThrows;

    /** Full inventory: closes the trade screen and throws a stack of junk out, then trading goes on. False: none to throw. */
    private boolean throwJunk(Minecraft mc, LocalPlayer p) {
        if (!settings.makeRoomDropJunk.get() || roomThrows >= 12) return false;
        int junk = dev.lazuli.mod.control.Tools.junkSlot(p, settings);
        if (junk < 0) return false;
        roomThrows++;
        close(mc, p);
        clicks.add(p.inventoryMenu.containerId, List.of(Click.throwAll(junk)));
        return true;
    }

    private void close(Minecraft mc, LocalPlayer p) {
        p.closeContainer();
        if (step == null) next(mc);
        else enter(Phase.PLAN, p);
    }

    private void giveUpOn(Minecraft mc, String why) {
        trip.cancel();
        input.release();
        skip.add(target);
        if (step == null) {
            next(mc); // only looking: on to the next one
            return;
        }
        chat.accept(Chat.warn("Skipping that {}: {}", step.trader().profession(), why));
        enter(Phase.PLAN, mc.player);
    }

    private static AbstractVillager find(Minecraft mc, String id) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof AbstractVillager v && v.getUUID().toString().equals(id)) return v;
        }
        return null;
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
        clicked = false;
        goal = null;
    }

    private void end(Outcome how, String msg) {
        phase = Phase.IDLE;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.containerMenu instanceof MerchantMenu) p.closeContainer();
        outcome = how;
        trip.cancel();
        input.release();
        if (msg != null) chat.accept(msg);
    }

    static String describe(List<Want> wants) {
        List<String> parts = new ArrayList<>();
        for (Want w : wants) parts.add(w.describe());
        return String.join(", ", parts);
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
