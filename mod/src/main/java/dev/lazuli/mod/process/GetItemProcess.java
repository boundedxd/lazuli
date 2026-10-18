package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.Drops;
import dev.lazuli.core.craft.ItemPlanner;
import dev.lazuli.core.craft.Recipes;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.MineProcess;
import dev.lazuli.mod.craft.RecipeData;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * #getitem: plans mine / craft / smelt steps to get items from what's in the inventory (tools, fuel, crafting table
 * and furnace too), runs the first step with the usual processes, then plans again from the real inventory. Several
 * items are planned together. Done, it can bring them back somewhere and put them in a chest there.
 */
public final class GetItemProcess implements Process {
    private static final int MAX_STEPS = 200, SAME_STEP_TRIES = 3;

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private enum Status { RUNNING, OK, FAILED, CANCELLED }

    /** After getting everything: walking back, putting it away. */
    private enum Bring { NONE, RETURN, DEPOSIT }

    private final Settings settings;
    private final Consumer<String> chat;
    private final MineProcess mine;
    private final CraftProcess crafts;
    private final SmeltProcess smelts;
    private SmithProcess smiths;
    private ContainerProcess containers;
    private java.util.function.Function<Minecraft, dev.lazuli.core.storage.ChestMemory> chests;
    private java.util.function.Function<Minecraft, String> dimension;
    /** Taking an item out of a remembered chest, and which. */
    private ChestTake take;
    private String withdrawItem;
    /** Started for another job's ingredients (#brew): what it wants counts as ingredients for the chest settings. */
    private boolean forIngredients;
    private boolean running;
    private Outcome outcome = Outcome.NONE;
    /** What to have: full ids, counts of at least 1. */
    private List<ItemCount> wants = List.of();
    private String lastStep;
    private int steps, repeats;
    private int[] returnTo;
    private boolean deposit;
    private Bring bring = Bring.NONE;
    private final Trip trip;
    private ItemPlanner.Action current;
    /** Trading for an item instead of making it (getItemTrade), and items trading didn't work out for. */
    private TradeProcess trade;
    private ItemCount trading;
    private final java.util.Set<String> noTrade = new java.util.HashSet<>();

    public GetItemProcess(dev.lazuli.core.session.PathingSession session, Settings settings, MineProcess mine, CraftProcess crafts,
                          SmeltProcess smelts, Consumer<String> chat) {
        this.trip = new Trip(session);
        this.settings = settings;
        this.chat = chat;
        this.mine = mine;
        this.crafts = crafts;
        this.smelts = smelts;
    }

    @Override
    public boolean active() {
        return running;
    }

        private HuntProcess hunt;

    /** Upgrading at a smithing table (getItemSmith). */
    public void useSmith(SmithProcess smiths) {
        this.smiths = smiths;
    }

    /** Hunting for mob drops (getItemHunt). */
    public void useHunt(HuntProcess hunt) {
        this.hunt = hunt;
        RecipeData.mobDrops();
    }

    /** Lets it take what it needs from chests opened before (chestFinal, chestIngredients). */
    public void useChests(ContainerProcess containers, java.util.function.Function<Minecraft, dev.lazuli.core.storage.ChestMemory> chests,
                          java.util.function.Function<Minecraft, String> dimension) {
        this.containers = containers;
        this.chests = chests;
        this.dimension = dimension;
        this.take = new ChestTake(settings, containers, chests, dimension);
    }

    /** Lets it trade with villagers for an item when that's the only way or cheaper (getItemTrade). */
    public void useTrades(TradeProcess trade) {
        this.trade = trade;
    }

    /** Steps finished, and steps the last plan still had (the HUD's bar). */
    private int done, planned;

    @Override
    public String status() {
        if (!running) return null;
        String s = "Get " + describe(wants);
        if (bring == Bring.RETURN) s += "  bringing it back";
        else if (bring == Bring.DEPOSIT) s += "  putting it away";
        else if (take != null && take.active()) s += "  taking " + name(withdrawItem) + " from a chest";
        else if (trading != null) s += "  trading for " + name(trading.item());
        else if (current != null) s += "  step " + (done + 1) + "/" + (done + planned) + ": " + verb(current) + " " + current.count() + " " + name(current.item());
        return s;
    }

    @Override
    public double progress() {
        return running && done + planned > 0 ? (double) done / (done + planned) : -1;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Gets each item until the inventory holds at least its count (a negative count: 1), then brings them back if asked. */
    public void start(Minecraft mc, dev.lazuli.core.craft.ItemRequest request) {
        start(mc, request, false);
    }

    /** @param forIngredients the items are for another job (#brew): chestIngredients decides on chests for all of them */
    public void start(Minecraft mc, dev.lazuli.core.craft.ItemRequest request, boolean forIngredients) {
        stop(false);
        this.forIngredients = forIngredients;
        outcome = Outcome.NONE;
        java.util.Map<String, Integer> merged = new java.util.LinkedHashMap<>();
        for (ItemCount i : request.items()) {
            String t = Inventories.id(i.item());
            Identifier id = Identifier.tryParse(t);
            if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
                end(Outcome.FAILED, Chat.err("Unknown item: {}", i.item()));
                return;
            }
            merged.merge(t, Math.max(1, i.count()), Integer::sum);
        }
        wants = merged.entrySet().stream().map(e -> new ItemCount(e.getKey(), e.getValue())).toList();
        returnTo = request.returnTo();
        deposit = request.deposit();
        bring = Bring.NONE;
        trip.clear();
        RecipeData.get();
        RecipeData.drops();
        running = true;
        current = null;
        lastStep = null;
        steps = repeats = 0;
        done = planned = 0;
        trading = null;
        noTrade.clear();
        noChest.clear();
        chat.accept(Chat.ok("Getting {}", describe(wants)));
    }

    /** "3 diamond" or "2 items: iron_pickaxe, 16 torch". */
    private static String describe(List<ItemCount> items) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (ItemCount i : items) parts.add((i.count() > 1 ? i.count() + " " : "") + name(i.item()));
        String all = String.join(", ", parts);
        if (items.size() == 1) return items.get(0).count() + " " + name(items.get(0).item());
        return all.length() > 40 ? items.size() + " items" : all;
    }

    /** The first wanted item there isn't enough of yet, or null when it has everything. */
    private ItemCount missing(LocalPlayer p) {
        for (ItemCount w : wants) if (count(p, w.item()) < w.count()) return w;
        return null;
    }

    @Override
    public void stop(boolean announce) {
        if (!running) return;
        if (current != null) {
            switch (current.kind()) {
                case MINE -> mine.stop(false);
                case CRAFT -> crafts.stop(false);
                case SMELT -> smelts.stop(false);
                case SMITH -> smiths.stop(false);
                case KILL -> hunt.stop(false);
            }
        }
        if (take != null) take.stop();
        if (bring == Bring.DEPOSIT) containers.stop(false);
        if (trading != null) trade.stop(false);
        trading = null;
        trip.cancel();
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (bring != Bring.NONE) {
            bringTick(mc, p);
            return;
        }
        if (take != null && take.active()) {
            switch (take.tick(mc, p)) {
                case RUNNING -> {
                    return;
                }
                case CANCELLED -> {
                    end(Outcome.CANCELLED, null);
                    return;
                }
                case DONE -> {
                    if (take.got(p) > 0) {
                        done++;
                    } else {
                        // nothing came out (full inventory, chest emptied by someone): not that way again this run
                        noChest.add(withdrawItem);
                        if (p.getInventory().getFreeSlot() < 0) {
                            end(Outcome.FAILED, Chat.err("Inventory full: can't take {} from the chest", name(withdrawItem)));
                            return;
                        }
                    }
                }
            }
        }
        if (trading != null) {
            if (trade.active()) return;
            switch (trade.outcome()) {
                case CANCELLED -> {
                    end(Outcome.CANCELLED, null);
                    return;
                }
                case DONE -> done++;
                default -> noTrade.add(trading.item()); // make it instead, if there's a way
            }
            trading = null;
        }
        if (current != null) {
            switch (status(current)) {
                case RUNNING -> {
                    return;
                }
                case FAILED -> {
                    end(Outcome.FAILED, Chat.err("Couldn't {} {}: stuck getting {}", verb(current), name(current.item()), describe(wants)));
                    return;
                }
                case CANCELLED -> {
                    end(Outcome.CANCELLED, null);
                    return;
                }
                case OK -> {
                    current = null;
                    done++;
                }
            }
        }
        if (crafts.pickingUp()) return; // taking the table along before moving on
        ItemCount need = missing(p);
        if (need == null) {
            if (crafts.pickUpTable()) return;
            got(mc, p);
            return;
        }
        if (p.containerMenu != p.inventoryMenu) return; // a step's menu still closing
        for (ItemCount w : wants) if (withdraw(mc, p, w.item(), w.count() - count(p, w.item()), !forIngredients)) return;
        Recipes recipes = RecipeData.get().getNow(null);
        Drops drops = RecipeData.drops().getNow(null);
        if (recipes == null || drops == null) return; // still reading the game's data
        if (++steps > MAX_STEPS) {
            end(Outcome.FAILED, Chat.err("Gave up after {} steps", MAX_STEPS));
            return;
        }
        ItemPlanner planner = planner(mc, recipes, drops);
        ItemPlanner.Plan plan = plan(mc, p, planner, wants);
        if (tradeFor(mc, p, need, plan, planner, recipes, drops)) return;
        if (!plan.ok()) {
            end(Outcome.FAILED, Chat.err("Can't get {}: {}", describe(wants), plan.problem()));
            return;
        }
        if (plan.actions().isEmpty()) {
            end(Outcome.FAILED, Chat.err("Nothing left to do, but there's no {} (only enchanted or damaged ones?)", name(need.item())));
            return;
        }
        dev.lazuli.mod.control.Tools.keep("getitem", plan.keep());
        ItemPlanner.Action a = plan.actions().get(0);
        // crafts in a row share one table; anything else first takes it along
        if (a.kind() != ItemPlanner.Kind.CRAFT && crafts.pickUpTable()) return;
        // what the first step makes is lying in a chest: take it instead
        if (withdraw(mc, p, a.item(), a.count(), !forIngredients && wants.stream().anyMatch(w -> w.item().equals(a.item())))) return;
        // upgrading what's worn: off it comes first (planned again next tick, with it in the inventory)
        if (a.kind() == ItemPlanner.Kind.SMITH && unequipFor(p, a)) return;
        String key = a.toString();
        repeats = key.equals(lastStep) ? repeats + 1 : 0;
        lastStep = key;
        if (repeats >= SAME_STEP_TRIES) {
            end(Outcome.FAILED, Chat.err("Stuck: {} {} {} keeps not helping", verb(a), a.count(), name(a.item())));
            return;
        }
        chat.accept(Chat.info("Step {}/{}: {} {} {}{}", 1, plan.actions().size(), verb(a), a.count(), name(a.item()),
            kinds(a) > 1 ? " (or any of " + kinds(a) + " kinds)" : ""));
        current = a;
        planned = plan.actions().size();
        switch (a.kind()) {
            case MINE -> mine.start(mc, drops.blocksFor(a.item(), a.count(), a.blocks()), a.blocks()); // 64 redstone: ~15 ores
            case CRAFT -> crafts.start(mc, List.of(new ItemCount(a.item(), a.count())), true);
            case SMELT -> smelts.start(mc, new ItemCount(a.item(), a.count()), true);
            case SMITH -> smiths.start(mc, new ItemCount(a.item(), a.count()));
            case KILL -> hunt.start(mc, a.item(), count(p, a.item()) + a.count(), a.blocks());
        }
    }

    /**
     * Trades for the missing item instead when villagers seen so far sell it and making it can't be done, or takes
     * more work than the trades (getting the emeralds included).
     */
    private boolean tradeFor(Minecraft mc, LocalPlayer p, ItemCount need, ItemPlanner.Plan plan, ItemPlanner planner,
                             Recipes recipes, Drops drops) {
        if (trade == null || !settings.getItemTrade.get() || noTrade.contains(need.item())) return false;
        int missing = need.count() - count(p, need.item());
        dev.lazuli.core.trade.Want w = new dev.lazuli.core.trade.Want(need.item(), null, 0, need.count());
        dev.lazuli.core.trade.TradePlanner.Plan tp = trade.plan(mc, p, List.of(w), recipes, drops);
        if (!tp.ok() || tp.steps().isEmpty()) return false;
        if (plan.ok()) {
            double make = planner.cost(need.item(), Inventories.plainCounts(p), pickTier(p)) * missing;
            double buy = trade.cost(p, tp, trade.gatherCost(mc, p, recipes, drops));
            if (buy >= make) return false;
        }
        chat.accept(Chat.info("Trading for {} {} ({})", missing, name(need.item()), plan.ok() ? "less work than making it" : "no other way"));
        trading = need;
        trade.start(mc, List.of(w));
        return true;
    }

    /** Has everything: done, or off to bring it back / put it away first. */
    private void got(Minecraft mc, LocalPlayer p) {
        String what = describe(wants);
        if (returnTo != null) {
            chat.accept(Chat.ok("Got {}; bringing it back", what));
            bring = Bring.RETURN;
            trip.go(new dev.lazuli.core.goal.GoalBlock(returnTo[0], returnTo[1], returnTo[2]), p, true);
        } else if (deposit) {
            putAway(mc);
        } else {
            end(Outcome.DONE, Chat.ok("Got {}", what));
        }
    }

    private void putAway(Minecraft mc) {
        bring = Bring.DEPOSIT;
        containers.start(mc, true, wants, null);
        if (!containers.active()) end(Outcome.FAILED, Chat.err("Got {}, but there's no chest here to put it in", describe(wants)));
    }

    private void bringTick(Minecraft mc, LocalPlayer p) {
        if (bring == Bring.RETURN) {
            if (trip.running()) return;
            var how = trip.ended();
            trip.clear();
            if (how == dev.lazuli.core.session.PathingSession.State.IDLE) {
                end(Outcome.CANCELLED, null);
                return;
            }
            if (how == dev.lazuli.core.session.PathingSession.State.FAILED) {
                end(Outcome.FAILED, Chat.err("Got {}, but couldn't get back", describe(wants)));
                return;
            }
            if (deposit) putAway(mc);
            else end(Outcome.DONE, Chat.ok("Got {} and brought it back", describe(wants)));
            return;
        }
        if (containers.active()) return;
        switch (containers.outcome()) {
            case DONE -> end(Outcome.DONE, Chat.ok("Got {} and put it away", describe(wants)));
            case CANCELLED -> end(Outcome.CANCELLED, null);
            default -> end(Outcome.FAILED, Chat.err("Got {}, but couldn't put it away", describe(wants)));
        }
    }

    /**
     * Starts taking {@code n} of {@code item} from the nearest remembered chest holding some; false if there's none.
     * @param finished whether it's the thing asked for (chestFinal) rather than something to make it with (chestIngredients)
     */
    /** Items a chest take got none of this run: not tried again. */
    private final java.util.Set<String> noChest = new java.util.HashSet<>();

    private boolean withdraw(Minecraft mc, LocalPlayer p, String item, int n, boolean finished) {
        if (take == null || !(finished ? settings.chestFinal.get() : settings.chestIngredients.get())) return false;
        String id = Inventories.id(item);
        if (noChest.contains(id)) return false;
        withdrawItem = id;
        return take.start(mc, p, id, n, pl -> count(pl, id));
    }

    /** A planner with the settings' rules (what it may mine, smelt, place) and what's around. */
    private ItemPlanner planner(Minecraft mc, Recipes recipes, Drops drops) {
        ItemPlanner planner = new ItemPlanner(recipes, drops, GetItemProcess::tier, SmeltProcess.burn(mc), smelts.fuels(),
            new ItemPlanner.Rules(settings.getItemMine.get(), settings.getItemSmelt.get(),
                settings.craftPlaceTable.get() && settings.allowPlace.get(), settings.smeltPlaceFurnace.get() && settings.allowPlace.get(),
                settings.getItemSmith.get()))
            .batching(settings.getItemBatch.get());
        if (settings.getItemNearby.get()) planner.nearby(Nearby.scan(mc, settings.antiXray.get()));
        if (hunt != null && settings.getItemHunt.get()) planner.mobs(RecipeData.mobDrops().getNow(null));
        return planner;
    }

    /** The steps from what the player has now (stations around count). */
    private ItemPlanner.Plan plan(Minecraft mc, LocalPlayer p, ItemPlanner planner, List<ItemCount> wants) {
        int range = settings.stationRange.get();
        // worn armor and the offhand count as had, for what it wants and for what goes into it (the diamond chestplate
        // a netherite one is smithed from): never made again; it's taken off when a step uses it (see unequipFor)
        Map<String, Integer> have = new HashMap<>(Inventories.plainCounts(p));
        for (EquipmentSlot e : EquipmentSlot.values()) {
            if (e == EquipmentSlot.MAINHAND) continue;
            ItemStack s = p.getItemBySlot(e);
            if (!s.isEmpty()) have.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        planner.smithingTable(Station.nearest(mc, p, Blocks.SMITHING_TABLE, range) != null);
        return planner.plan(wants, have, pickTier(p),
            Station.nearest(mc, p, Blocks.CRAFTING_TABLE, range) != null, Station.nearest(mc, p, Blocks.FURNACE, range) != null);
    }

    /** #getitem ... dry: says the steps and a rough time, does nothing. */
    public void dryRun(Minecraft mc, List<ItemCount> wants) {
        LocalPlayer p = mc.player;
        Recipes recipes = RecipeData.get().getNow(null);
        Drops drops = RecipeData.drops().getNow(null);
        if (p == null || recipes == null || drops == null) {
            chat.accept(Chat.warn("Still reading the game's recipes; try again in a moment"));
            return;
        }
        List<ItemCount> resolved = new java.util.ArrayList<>();
        for (ItemCount w : wants) resolved.add(new ItemCount(Inventories.id(w.item()), w.count()));
        ItemPlanner.Plan plan = plan(mc, p, planner(mc, recipes, drops), resolved);
        if (!plan.ok()) {
            chat.accept(Chat.err("Can't get {}: {} (it might still trade for it)", describe(resolved), plan.problem()));
            return;
        }
        if (plan.actions().isEmpty()) {
            chat.accept(Chat.ok("Already have {}", describe(resolved)));
            return;
        }
        chat.accept(Chat.info("To get {}: {} steps, about {}", describe(resolved), plan.actions().size(),
            dev.lazuli.core.craft.PlanEstimate.format(dev.lazuli.core.craft.PlanEstimate.seconds(plan.actions()))));
        int i = 0;
        for (ItemPlanner.Action a : plan.actions()) {
            chat.accept(Chat.info("{}. {} {} {}{} (~{})", ++i, verb(a), a.count(), name(a.item()),
                a.blocks().isEmpty() ? "" : " from " + String.join(", ", a.blocks().stream().map(GetItemProcess::name).toList()),
                dev.lazuli.core.craft.PlanEstimate.format(dev.lazuli.core.craft.PlanEstimate.seconds(a))));
        }
    }

    private Status status(ItemPlanner.Action a) {
        return switch (a.kind()) {
            case MINE -> mine.active() ? Status.RUNNING : mine.cancelled() ? Status.CANCELLED
                : mine.mined() > 0 && !mine.failed() ? Status.OK : Status.FAILED;
            case CRAFT -> switch (crafts.outcome()) {
                case NONE -> Status.RUNNING;
                case DONE -> Status.OK;
                case FAILED -> Status.FAILED;
                case CANCELLED -> Status.CANCELLED;
            };
            case SMELT -> switch (smelts.outcome()) {
                case NONE -> Status.RUNNING;
                case DONE -> Status.OK;
                case FAILED -> Status.FAILED;
                case CANCELLED -> Status.CANCELLED;
            };
            case SMITH -> switch (smiths.outcome()) {
                case NONE -> Status.RUNNING;
                case DONE -> Status.OK;
                case FAILED -> Status.FAILED;
                case CANCELLED -> Status.CANCELLED;
            };
            case KILL -> switch (hunt.outcome()) {
                case NONE -> Status.RUNNING;
                case DONE -> Status.OK;
                case FAILED -> Status.FAILED;
                case CANCELLED -> Status.CANCELLED;
            };
        };
    }

    private void end(Outcome how, String msg) {
        dev.lazuli.mod.control.Tools.keep("getitem", null);
        if (how == Outcome.FAILED) crafts.pickUpTable(); // the table it left standing (runs on as the craft process)
        running = false;
        outcome = how;
        current = null;
        bring = Bring.NONE;
        if (msg != null) chat.accept(msg);
    }

    /** The pickaxe tier a block needs to drop anything: 0 none, 1 wood ... 4 diamond, 9 another tool. */
    static int tier(String block) {
        Identifier id = Identifier.tryParse(block);
        var b = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (b == null) return 0;
        BlockState s = b.defaultBlockState();
        if (!s.requiresCorrectToolForDrops()) return 0;
        if (s.is(BlockTags.NEEDS_DIAMOND_TOOL)) return 4;
        if (s.is(BlockTags.NEEDS_IRON_TOOL)) return 3;
        if (s.is(BlockTags.NEEDS_STONE_TOOL)) return 2;
        return s.is(BlockTags.MINEABLE_WITH_PICKAXE) ? 1 : 9;
    }

    /** The best pickaxe in the inventory (worn ones count): 0 none, 1 wood or gold ... 4 diamond or netherite. */
    static int pickTier(LocalPlayer p) {
        int best = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String path = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!path.endsWith("_pickaxe")) continue;
            int t = switch (path) {
                case "wooden_pickaxe", "golden_pickaxe" -> 1;
                case "stone_pickaxe" -> 2;
                case "iron_pickaxe" -> 3;
                case "diamond_pickaxe", "netherite_pickaxe" -> 4;
                default -> 0;
            };
            best = Math.max(best, t);
        }
        return best;
    }

    /** Any of {@code item} in the main inventory and hotbar, enchanted or worn too, and what's worn or in the offhand. */
    /** Menu slots of the armor pieces and the offhand in the player's own inventory screen. */
    private static final int[] WORN = {5, 6, 7, 8, 45};
    private static final EquipmentSlot[] WORN_SLOT = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
        EquipmentSlot.FEET, EquipmentSlot.OFFHAND};

    /**
     * A smithing step whose base item is worn (or in the offhand) and not in the inventory: takes it off into the
     * inventory (one shift-click), true while doing so.
     */
    private boolean unequipFor(LocalPlayer p, ItemPlanner.Action a) {
        Recipes recipes = RecipeData.get().getNow(null);
        if (recipes == null) return false;
        Map<String, Integer> inv = Inventories.plainCounts(p);
        for (dev.lazuli.core.craft.SmithRecipe r : recipes.smithing(a.item())) {
            if (r.base().stream().anyMatch(b -> inv.getOrDefault(b, 0) > 0)) return false; // one's in the inventory already
            for (int i = 0; i < WORN.length; i++) {
                ItemStack s = p.getItemBySlot(WORN_SLOT[i]);
                if (s.isEmpty() || !r.base().contains(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())) continue;
                // curse of binding: it won't come off
                if (net.minecraft.world.item.enchantment.EnchantmentHelper.has(s,
                    net.minecraft.world.item.enchantment.EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
                if (p.getInventory().getFreeSlot() < 0) return false; // no room: the smith step says what's missing
                var clicks = dev.lazuli.mod.inventory.ClickDriver.get();
                if (!clicks.busy()) {
                    chat.accept(Chat.info("Taking off the {} to upgrade it", name(BuiltInRegistries.ITEM.getKey(s.getItem()).toString())));
                    clicks.add(p.inventoryMenu.containerId, List.of(dev.lazuli.core.inventory.Click.shift(WORN[i])));
                }
                return true;
            }
        }
        return false;
    }

    private static int count(LocalPlayer p, String item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item)) n += s.getCount();
        }
        for (EquipmentSlot e : EquipmentSlot.values()) {
            if (e == EquipmentSlot.MAINHAND) continue; // that's a hotbar slot, counted above
            ItemStack s = p.getItemBySlot(e);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item)) n += s.getCount();
        }
        return n;
    }

    /** How many different blocks a mining step takes (deepslate ores count with their plain kind). */
    private static long kinds(ItemPlanner.Action a) {
        return a.blocks().stream().map(b -> b.replace("deepslate_", "")).distinct().count();
    }

    private static String verb(ItemPlanner.Action a) {
        return switch (a.kind()) {
            case MINE -> "mine";
            case CRAFT -> "craft";
            case SMELT -> "smelt";
            case SMITH -> "upgrade to";
            case KILL -> "hunt for";
        };
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
