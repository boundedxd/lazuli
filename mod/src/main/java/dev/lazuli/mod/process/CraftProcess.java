package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.CraftTree;
import dev.lazuli.core.craft.Crafter;
import dev.lazuli.core.craft.Recipes;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.craft.RecipeData;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * #craft: works out what to craft (ingredients first), crafts what fits in the inventory's 2x2 grid there, and for
 * bigger recipes walks to a crafting table nearby, or places one.
 */
public final class CraftProcess implements Process {
    private static final String TABLE = "minecraft:crafting_table";
    private static final int RESULT_TICKS = 40, TAKE_TICKS = 20, CLEAR_TICKS = 100;

    private enum Phase { IDLE, CRAFT, TABLE }

    /** The steps of one batch in the grid: empty it, lay out the ingredients, wait for the result, take it. */
    private enum Batch { CLEAR, FILL, RESULT, TAKE }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Station station;
    private Phase phase = Phase.IDLE;
    private Batch batchStep;
    private Outcome outcome = Outcome.NONE;
    private final Deque<CraftTree.Step> steps = new ArrayDeque<>();
    private List<ItemCount> asked = List.of();
    /** Crafts left in the current step, crafts in this batch, the result count before it. */
    private int stepLeft, batch, before, batchStart;

    public CraftProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.station = new Station(session, settings, clicks);
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** @param items ids and how many of each to make (a negative count: one craft) */
    public void start(Minecraft mc, List<ItemCount> items) {
        stop(false);
        outcome = Outcome.NONE;
        LocalPlayer p = mc.player;
        if (p.containerMenu != p.inventoryMenu) {
            fail("Close the open menu first");
            return;
        }
        Recipes recipes = recipes();
        if (recipes == null) return;
        asked = items.stream().map(i -> new ItemCount(Inventories.id(i.item()), Math.max(1, i.count()))).toList();
        for (ItemCount i : asked) {
            if (recipes.producing(i.item()).isEmpty()) {
                fail("There's no crafting recipe for " + i.item());
                return;
            }
        }
        Map<String, Integer> have = Inventories.plainCounts(p);
        List<CraftTree.Step> plan = plan(recipes, have);
        if (plan == null) return;
        if (plan.stream().anyMatch(s -> !s.recipe().fits(2))
            && Station.nearest(mc, p, Blocks.CRAFTING_TABLE, settings.stationRange.get()) == null) {
            if (!mayPlace()) {
                fail("No crafting table within " + settings.stationRange.get() + " blocks, and placing one is off ("
                    + (settings.allowPlace.get() ? "craftPlaceTable" : "allowPlace") + ")");
                return;
            }
            // make (if need be) and keep one table to place, then plan the rest from what's left
            List<CraftTree.Step> pre = new ArrayList<>();
            if (have.getOrDefault(TABLE, 0) < 1) {
                CraftTree.Result t = CraftTree.resolve(recipes, TABLE, 1, have, settings.craftIngredients.get());
                if (!t.ok()) {
                    fail("Needs a crafting table, and one can't be made: missing " + ItemCount.describe(missing(t)));
                    return;
                }
                pre.addAll(t.steps());
                have = new HashMap<>(t.left());
            }
            have.merge(TABLE, -1, Integer::sum);
            plan = plan(recipes, have);
            if (plan == null) return;
            pre.addAll(plan);
            plan = pre;
        }
        steps.clear();
        steps.addAll(plan);
        chat.accept(Chat.ok("Crafting {} ({} step{})", ItemCount.describe(asked), plan.size(), plan.size() == 1 ? "" : "s"));
        next(p);
    }

    /** Whether {@code item} could be crafted from what the player has (ingredients too, if the setting allows). */
    public boolean canCraft(LocalPlayer p, String item) {
        Recipes recipes = RecipeData.get().getNow(null);
        return recipes != null && CraftTree.resolve(recipes, Inventories.id(item), 1, Inventories.plainCounts(p),
            settings.craftIngredients.get()).ok();
    }

    /** Whether it may place a crafting table: its own toggle and allowPlace. */
    private boolean mayPlace() {
        return settings.craftPlaceTable.get() && settings.allowPlace.get();
    }

    private Recipes recipes() {
        try {
            return RecipeData.get().join();
        } catch (RuntimeException e) {
            fail("Couldn't read the game's recipes: " + e.getMessage());
            return null;
        }
    }

    /** Every asked-for item from {@code have}, or null (and a message) if something's missing. */
    private List<CraftTree.Step> plan(Recipes recipes, Map<String, Integer> have) {
        List<CraftTree.Step> out = new ArrayList<>();
        Map<String, Integer> left = have;
        for (ItemCount i : asked) {
            CraftTree.Result r = CraftTree.resolve(recipes, i.item(), i.count(), left, settings.craftIngredients.get());
            if (!r.ok()) {
                fail("Can't craft " + i.item() + ": missing " + ItemCount.describe(missing(r)));
                return null;
            }
            out.addAll(r.steps());
            left = r.left();
        }
        return out;
    }

    static List<ItemCount> missing(CraftTree.Result r) {
        return r.missing().entrySet().stream().map(e -> new ItemCount(e.getKey().replace("minecraft:", ""), e.getValue())).toList();
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
            case CRAFT -> craft(p);
            case TABLE -> {
                switch (station.tick(mc, p)) {
                    case OPEN -> phase = Phase.CRAFT;
                    case FAILED -> fail(station.error());
                    case WORKING -> {}
                }
            }
            default -> {}
        }
    }

    private void craft(LocalPlayer p) {
        if (!(p.containerMenu instanceof AbstractCraftingMenu menu)) {
            fail("The crafting menu closed");
            return;
        }
        CraftTree.Step step = steps.peek();
        int size = (int) Math.round(Math.sqrt(menu.getInputGridSlots().size()));
        if (!step.recipe().fits(size)) {
            phase = Phase.TABLE;
            station.start(p, Blocks.CRAFTING_TABLE, TABLE, "crafting table", m -> m instanceof CraftingMenu, null, mayPlace());
            return;
        }
        if (menu == p.inventoryMenu) clicks.keepOpen();
        if (clicks.busy()) {
            batchStart = p.tickCount; // the clock starts once the clicks are in
            return;
        }
        int[] grid = menu.getInputGridSlots().stream().mapToInt(s -> s.index).toArray();
        int result = menu.getResultSlot().index;
        int[] mine = Inventories.slots(menu, p, true);
        String made = step.recipe().result();
        switch (batchStep) {
            case CLEAR -> {
                List<Click> out = new ArrayList<>();
                for (int g : grid) if (!menu.slots.get(g).getItem().isEmpty()) out.add(Click.shift(g));
                if (out.isEmpty()) {
                    batchStep = Batch.FILL;
                    return;
                }
                if (p.tickCount - batchStart > CLEAR_TICKS) {
                    fail("Can't empty the crafting grid (inventory full?)");
                    return;
                }
                clicks.play(menu.containerId, out);
            }
            case FILL -> {
                Menu m = Inventories.read(menu, p);
                batch = Crafter.batch(stepLeft);
                for (String c : step.cells()) if (c != null) batch = Math.min(batch, maxStack(m, mine, c));
                List<Click> plan;
                try {
                    plan = Crafter.fill(m, mine, grid, size, step.recipe(), step.cells(), batch);
                } catch (IllegalStateException e) {
                    fail(e.getMessage());
                    return;
                }
                before = m.count(mine, made::equals);
                clicks.play(menu.containerId, plan);
                batchStep = Batch.RESULT;
                batchStart = p.tickCount;
            }
            case RESULT -> {
                ItemStack r = menu.slots.get(result).getItem();
                if (!r.isEmpty() && Inventories.key(r).equals(made)) {
                    clicks.play(menu.containerId, List.of(Click.shift(result)));
                    batchStep = Batch.TAKE;
                    batchStart = p.tickCount;
                } else if (p.tickCount - batchStart > RESULT_TICKS) {
                    fail("The game didn't take the recipe for " + made);
                }
            }
            case TAKE -> {
                int got = Inventories.read(menu, p).count(mine, made::equals) - before;
                if (got < batch * step.recipe().count()) {
                    if (p.tickCount - batchStart > TAKE_TICKS) fail("Only got " + got + " " + made + " (inventory full?)");
                    return;
                }
                stepLeft -= batch;
                if (stepLeft <= 0) {
                    steps.poll();
                    next(p);
                } else {
                    startBatch(p);
                }
            }
        }
    }

    /** The next step, or done. */
    private void next(LocalPlayer p) {
        if (steps.isEmpty()) {
            end(Outcome.DONE, Chat.ok("Crafted {}", ItemCount.describe(asked)));
            return;
        }
        stepLeft = steps.peek().crafts();
        phase = Phase.CRAFT;
        startBatch(p);
    }

    private void startBatch(LocalPlayer p) {
        batchStep = Batch.CLEAR;
        batchStart = p.tickCount;
    }

    private static int maxStack(Menu m, int[] slots, String item) {
        for (int s : slots) if (item.equals(m.get(s).item())) return m.get(s).max();
        return 64;
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err(msg));
    }

    private void end(Outcome how, String msg) {
        boolean was = phase != Phase.IDLE;
        phase = Phase.IDLE;
        outcome = how;
        steps.clear();
        station.cancel();
        if (was) {
            // closing hands back whatever is still in the grid
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && (p.containerMenu != p.inventoryMenu || !p.inventoryMenu.getCraftSlots().isEmpty())) p.closeContainer();
        }
        if (msg != null) chat.accept(msg);
    }
}
