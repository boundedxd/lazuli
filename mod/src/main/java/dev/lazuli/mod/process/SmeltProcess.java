package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.Recipes;
import dev.lazuli.core.craft.SmeltRecipe;
import dev.lazuli.core.craft.Smelting;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.craft.RecipeData;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * #smelt: finds (or places, crafting it if need be) a furnace, a blast furnace or smoker when that's faster, loads
 * the input and fuel, and either waits and takes the output as it cooks or leaves it for {@code #smelt collect}.
 */
public final class SmeltProcess implements Process {
    private static final String FURNACE = "minecraft:furnace";
    /** Ticks with the furnace out and nothing happening before it gives up. */
    private static final int STALL_TICKS = 100;

    private enum Phase { IDLE, MAKE_FURNACE, STATION, LOAD, LEAVE, WAIT, PICKUP }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    /** A furnace job: where, what goes in and comes out, how many still to put in and take out, fuel still to add. */
    private static final class Job {
        BlockPos pos;
        SmeltRecipe.Furnace furnace;
        String input, result;
        int toLoad, toTake;
        Map<String, Integer> fuel;
        long readyAt;
        boolean told;
    }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final CraftProcess crafts;
    private final Station station;
    private final List<Job> left = new ArrayList<>();
    private Phase phase = Phase.IDLE;
    private String pickedMsg;
    private Outcome outcome = Outcome.NONE;
    private Job job;
    private ItemCount asked;
    private boolean wait;
    /** The output count in the inventory when the furnace opened; the last tick something happened. */
    private int before, activeAt;

    public SmeltProcess(PathingSession session, Settings settings, ClickDriver clicks, CraftProcess crafts, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.crafts = crafts;
        this.station = new Station(session, settings, clicks);
    }

    /** Fuels that can't be smelted into something, so a shift-click sends them to the fuel slot (logs go to the input). */
    private static boolean pureFuel(String key) {
        return switch (key.split("#")[0]) {
            case "minecraft:coal", "minecraft:charcoal", "minecraft:coal_block", "minecraft:blaze_rod",
                 "minecraft:lava_bucket", "minecraft:dried_kelp_block" -> true;
            default -> false;
        };
    }

    @Override
    public String status() {
        if (phase == Phase.IDLE || asked == null) return null;
        String doing = switch (phase) {
            case MAKE_FURNACE -> "making a furnace";
            case STATION -> "going to the furnace";
            case LOAD -> "loading";
            case LEAVE, WAIT -> "cooking";
            case PICKUP -> "picking up the furnace";
            case IDLE -> "";
        };
        return "Smelt " + asked.count() + " " + asked.item().replace("minecraft:", "") + "  (" + doing + ")";
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** @param item what comes out (iron_ingot) or goes in (raw_iron); a negative count: all of the input it has */
    public void start(Minecraft mc, ItemCount item) {
        start(mc, item, settings.smeltWait.get());
    }

    /** @param wait stay until it's all done (else load it and leave) */
    /** Starting again after a busy furnace: the furnaces to avoid stay. */
    private boolean retrying;

    public void start(Minecraft mc, ItemCount item, boolean wait) {
        stop(false);
        if (!retrying) station.clearAvoid();
        this.wait = wait;
        outcome = Outcome.NONE;
        LocalPlayer p = mc.player;
        if (p.containerMenu != p.inventoryMenu) {
            fail("Close the open menu first");
            return;
        }
        Recipes recipes;
        try {
            recipes = RecipeData.get().join();
        } catch (RuntimeException e) {
            fail("Couldn't read the game's recipes: " + e.getMessage());
            return;
        }
        String id = Inventories.id(item.item());
        Map<String, Integer> have = Inventories.plainCounts(p);
        Smelting.Job sj = Smelting.job(recipes, id, have);
        if (sj == null) {
            if (recipes.smelting(id).isEmpty() && recipes.smeltingFrom(id).isEmpty()) fail("There's no furnace recipe for " + id);
            else fail("Nothing to smelt into " + short_(id) + ": needs " + inputs(recipes, id));
            return;
        }
        int haveIn = have.getOrDefault(sj.input(), 0), n = item.count() < 0 ? haveIn : item.count();
        if (haveIn < n) {
            fail("Only have " + haveIn + " " + short_(sj.input()));
            return;
        }
        Map<String, Integer> fuelHave = new HashMap<>(have);
        fuelHave.merge(sj.input(), -n, Integer::sum); // don't burn what's being cooked
        Map<String, Integer> fuel = Smelting.fuel(fuels(), fuelHave, burn(mc), n);
        if (fuel == null) {
            fail("Not enough fuel for " + n + " items (fuels are set in the config, Inventory tab)");
            return;
        }
        SmeltRecipe.Furnace kind = SmeltRecipe.Furnace.FURNACE;
        if (settings.smeltFastFurnaces.get() && sj.fast() != null) {
            SmeltRecipe.Furnace fast = sj.fast().furnace();
            if (have.getOrDefault(fast.block, 0) > 0 && mayPlace() || Station.nearest(mc, p, block(fast), settings.stationRange.get()) != null) kind = fast;
        }
        if (kind == SmeltRecipe.Furnace.FURNACE && (have.getOrDefault(FURNACE, 0) < 1 || !mayPlace())
            && Station.nearest(mc, p, Blocks.FURNACE, settings.stationRange.get()) == null) {
            if (!mayPlace()) {
                fail("No furnace within " + settings.stationRange.get() + " blocks, and placing one is off ("
                    + (settings.allowPlace.get() ? "smeltPlaceFurnace" : "allowPlace") + ")");
                return;
            }
            if (!crafts.canCraft(p, FURNACE)) {
                fail("No furnace near, and one can't be made (8 cobblestone, or blackstone / cobbled deepslate)");
                return;
            }
            // make one, then plan again from what's left
            asked = item;
            phase = Phase.MAKE_FURNACE;
            chat.accept(Chat.info("Making a furnace first"));
            crafts.start(mc, List.of(new ItemCount(FURNACE, 1)));
            return;
        }
        job = new Job();
        job.furnace = kind;
        job.input = sj.input();
        job.result = sj.recipe().result();
        job.toLoad = job.toTake = n;
        job.fuel = new LinkedHashMap<>(fuel);
        chat.accept(Chat.ok("Smelting {} {} into {} in a {} (fuel: {})", n, short_(job.input), short_(job.result), name(kind),
            ItemCount.describe(fuel.entrySet().stream().map(e -> new ItemCount(short_(e.getKey()), e.getValue())).toList())));
        go(p, null);
    }

    /** Goes back to the first furnace left cooking. */
    public void collect(Minecraft mc) {
        stop(false);
        outcome = Outcome.NONE;
        if (left.isEmpty()) {
            fail("Nothing left cooking");
            return;
        }
        job = left.remove(0);
        wait = settings.smeltWait.get();
        chat.accept(Chat.ok("Going back for the {} at {} {} {}", short_(job.result), job.pos.getX(), job.pos.getY(), job.pos.getZ()));
        go(mc.player, job.pos);
    }

    private void go(LocalPlayer p, BlockPos at) {
        phase = Phase.STATION;
        station.start(p, block(job.furnace), job.furnace.block, name(job.furnace), m -> m instanceof AbstractFurnaceMenu, at,
            mayPlace());
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.MAKE_FURNACE) crafts.stop(false);
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (mc.level != null) {
            for (Job j : left) {
                if (j.told || mc.level.getGameTime() < j.readyAt) continue;
                j.told = true;
                chat.accept(Chat.info("The {} at {} {} {} should be done: {}smelt collect", short_(j.result), j.pos.getX(),
                    j.pos.getY(), j.pos.getZ(), settings.prefix.get()));
            }
        }
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        switch (phase) {
            case MAKE_FURNACE -> {
                switch (crafts.outcome()) {
                    case DONE -> start(mc, asked, wait);
                    case FAILED, CANCELLED -> end(Outcome.FAILED, Chat.err("Couldn't make a furnace"));
                    case NONE -> {}
                }
            }
            case STATION -> {
                switch (station.tick(mc, p)) {
                    case OPEN -> {
                        job.pos = station.pos();
                        before = count(p, job.result);
                        activeAt = p.tickCount;
                        phase = Phase.LOAD;
                    }
                    case FAILED -> fail(station.error());
                    case WORKING -> {}
                }
            }
            case LOAD, LEAVE, WAIT -> furnace(mc, p);
            case PICKUP -> {
                if (station.pickUp(mc, p)) end(Outcome.DONE, pickedMsg);
            }
            default -> {}
        }
    }

    private void furnace(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof AbstractFurnaceMenu fm)) {
            fail("The " + name(job.furnace) + " closed");
            return;
        }
        if (clicks.busy()) return;
        int taken = count(p, job.result) - before;
        if (phase == Phase.LEAVE || phase == Phase.WAIT && taken >= job.toTake) {
            job.toTake -= taken;
            if (job.toTake > 0) {
                left.add(job);
                end(Outcome.DONE, Chat.ok("Left {} {} cooking at {} {} {}, ready in about {} s: {}smelt collect", job.toTake,
                    short_(job.input), job.pos.getX(), job.pos.getY(), job.pos.getZ(), (job.readyAt - mc.level.getGameTime()) / 20,
                    settings.prefix.get()));
            } else if (station.startPickUp(p)) {
                // nothing left in the furnace it placed itself: take it along
                pickedMsg = Chat.ok("Smelted {} {}", taken, short_(job.result));
                phase = Phase.PICKUP;
            } else {
                end(Outcome.DONE, Chat.ok("Smelted {} {}", taken, short_(job.result)));
            }
            return;
        }
        ItemStack in = fm.slots.get(0).getItem(), fuel = fm.slots.get(1).getItem(), out = fm.slots.get(2).getItem();
        boolean othersOut = !out.isEmpty() && !Inventories.key(out).equals(job.result);
        if (othersOut || !in.isEmpty() && !Inventories.key(in).equals(job.input)) {
            // someone else's: another furnace (or a new one), this one left alone
            chat.accept(Chat.info("The {} at {} is busy; using another", name(job.furnace), job.pos.toShortString()));
            station.avoid(job.pos);
            p.closeContainer();
            phase = Phase.IDLE;
            retrying = true;
            try {
                start(mc, asked, wait);
            } finally {
                retrying = false;
            }
            return;
        }
        int[] mine = Inventories.slots(fm, p, true);
        Menu sim = Inventories.read(fm, p);
        List<Click> plan = new ArrayList<>();
        try {
            if (!out.isEmpty()) add(sim, plan, List.of(Click.shift(2))); // take what's done
            int put = Math.min(job.toLoad, in.getMaxStackSize() - in.getCount());
            if (put > 0) {
                add(sim, plan, Moves.put(sim, mine, 0, job.input, put, Moves.Quick.EXACT));
                job.toLoad -= put;
            }
            // fuel one kind at a time, once the slot is free for it
            var next = job.fuel.entrySet().stream().findFirst().orElse(null);
            if (next != null && (fuel.isEmpty() || Inventories.key(fuel).equals(next.getKey()))) {
                int k = Math.min(next.getValue(), 64 - fuel.getCount());
                if (k > 0) add(sim, plan, Moves.put(sim, mine, 1, next.getKey(), k, pureFuel(next.getKey()) ? Moves.Quick.EXACT : Moves.Quick.OFF));
                if (next.getValue() - k <= 0) job.fuel.remove(next.getKey());
                else job.fuel.put(next.getKey(), next.getValue() - k);
            }
        } catch (IllegalStateException e) {
            fail(e.getMessage() + " left in the inventory");
            return;
        }
        if (!plan.isEmpty() || fm.isLit()) activeAt = p.tickCount;
        if (!plan.isEmpty()) clicks.play(fm.containerId, plan);
        if (phase == Phase.LOAD) {
            int cooking = sim.get(0).count();
            job.readyAt = mc.level.getGameTime() + (long) cooking * cookTicks(job.furnace);
            job.told = false;
            phase = wait ? Phase.WAIT : Phase.LEAVE;
            return;
        }
        if (p.tickCount - activeAt > STALL_TICKS) {
            if (in.isEmpty() && job.toLoad == 0) fail("Only got " + taken + " of " + job.toTake + " " + short_(job.result));
            else fail("The " + name(job.furnace) + " went out (no fuel left?)");
        }
    }

    /** Whether it may place a furnace: its own toggle and allowPlace. */
    private boolean mayPlace() {
        return settings.smeltPlaceFurnace.get() && settings.allowPlace.get();
    }

    private static void add(Menu sim, List<Click> plan, List<Click> clicks) {
        sim.applyAll(clicks);
        plan.addAll(clicks);
    }

    private static int count(LocalPlayer p, String item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && Inventories.key(s).equals(item)) n += s.getCount();
        }
        return n;
    }

    List<String> fuels() {
        return Arrays.stream(settings.smeltFuels.get().split(",")).filter(s -> !s.isBlank()).map(Inventories::id).toList();
    }

    /** Fuel ticks for one of an item, as the game counts them. */
    static ToIntFunction<String> burn(Minecraft mc) {
        return id -> {
            try {
                var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                return mc.level.fuelValues().burnDuration(new ItemStack(item));
            } catch (RuntimeException e) {
                return 0;
            }
        };
    }

    private static String inputs(Recipes recipes, String id) {
        List<String> out = new ArrayList<>();
        for (SmeltRecipe r : recipes.smelting(id))
            for (String in : r.input()) if (out.size() < 4 && !out.contains(short_(in))) out.add(short_(in));
        return String.join(" or ", out);
    }

    private static String short_(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    private static Block block(SmeltRecipe.Furnace f) {
        return switch (f) {
            case FURNACE -> Blocks.FURNACE;
            case BLAST_FURNACE -> Blocks.BLAST_FURNACE;
            case SMOKER -> Blocks.SMOKER;
        };
    }

    private static String name(SmeltRecipe.Furnace f) {
        return switch (f) {
            case FURNACE -> "furnace";
            case BLAST_FURNACE -> "blast furnace";
            case SMOKER -> "smoker";
        };
    }

    private static int cookTicks(SmeltRecipe.Furnace f) {
        return f == SmeltRecipe.Furnace.FURNACE ? Smelting.ITEM_TICKS : Smelting.ITEM_TICKS / 2;
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err(msg));
    }

    private void end(Outcome how, String msg) {
        boolean was = phase != Phase.IDLE;
        boolean loaded = phase == Phase.LOAD || phase == Phase.LEAVE || phase == Phase.WAIT;
        phase = Phase.IDLE;
        outcome = how;
        station.cancel();
        // stopped with things still in the furnace: #smelt collect can go back for them
        if (how != Outcome.DONE && loaded && job != null && job.pos != null && !left.contains(job)) left.add(job);
        if (was) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && p.containerMenu instanceof AbstractFurnaceMenu) p.closeContainer();
        }
        if (msg != null) chat.accept(msg);
    }
}
