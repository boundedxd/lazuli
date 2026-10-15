package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.CraftTree;
import dev.lazuli.core.craft.Crafter;
import dev.lazuli.core.craft.Recipes;
import dev.lazuli.core.goal.GoalAdjacent;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Aim;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.craft.RecipeData;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * #craft: works out what to craft (ingredients first), crafts what fits in the inventory's 2x2 grid there, and for
 * bigger recipes walks to a crafting table nearby, or places one.
 */
public final class CraftProcess implements Process {
    private static final String TABLE = "minecraft:crafting_table";
    private static final int OPEN_TICKS = 60, RESULT_TICKS = 40, TAKE_TICKS = 20, CLEAR_TICKS = 100, PLACE_TICKS = 100, TRIES = 2;

    private enum Phase { IDLE, CRAFT, TABLE, OPEN, PLACE }

    /** The steps of one batch in the grid: empty it, lay out the ingredients, wait for the result, take it. */
    private enum Batch { CLEAR, FILL, RESULT, TAKE }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private Phase phase = Phase.IDLE;
    private Batch batchStep;
    private Outcome outcome = Outcome.NONE;
    private final Deque<CraftTree.Step> steps = new ArrayDeque<>();
    private List<ItemCount> asked = List.of();
    /** Crafts left in the current step, crafts in this batch, the result count before it. */
    private int stepLeft, batch, before, phaseStart, batchStart, tries, usedAt;
    private boolean acted;
    private BlockPos table, spot;

    public CraftProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.trip = new Trip(session);
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
        Recipes recipes;
        try {
            recipes = RecipeData.get().join();
        } catch (RuntimeException e) {
            fail("Couldn't read the game's recipes: " + e.getMessage());
            return;
        }
        asked = items.stream().map(i -> new ItemCount(id(i.item()), Math.max(1, i.count()))).toList();
        for (ItemCount i : asked) {
            if (recipes.producing(i.item()).isEmpty()) {
                fail("There's no crafting recipe for " + i.item());
                return;
            }
        }
        Map<String, Integer> have = counts(p);
        List<CraftTree.Step> plan = plan(recipes, have);
        if (plan == null) return;
        table = null;
        if (plan.stream().anyMatch(s -> !s.recipe().fits(2))) {
            table = nearestTable(mc, p, settings.craftTableRange.get());
            if (table == null) {
                if (!settings.craftPlaceTable.get()) {
                    fail("No crafting table within " + settings.craftTableRange.get() + " blocks");
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
        }
        steps.clear();
        steps.addAll(plan);
        tries = 0;
        trip.clear();
        chat.accept(Chat.ok("Crafting {} ({} step{})", ItemCount.describe(asked), plan.size(), plan.size() == 1 ? "" : "s"));
        next(p);
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

    private static List<ItemCount> missing(CraftTree.Result r) {
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
                if (table != null && !mc.level.getBlockState(table).is(Blocks.CRAFTING_TABLE)) table = null; // broken meanwhile
                if (table == null) table = nearestTable(mc, p, settings.craftTableRange.get());
                if (table == null) {
                    if (settings.craftPlaceTable.get()) enter(Phase.PLACE, p);
                    else fail("No crafting table within " + settings.craftTableRange.get() + " blocks");
                    return;
                }
                if (Aim.at(mc, p, table, null) != null) {
                    trip.cancel();
                    enter(Phase.OPEN, p);
                    return;
                }
                if (trip.running()) return;
                if (++tries > TRIES) {
                    fail("Can't get to the crafting table");
                    return;
                }
                trip.go(new GoalAdjacent(table.getX(), table.getY(), table.getZ()), p);
            }
            case OPEN -> {
                if (p.containerMenu instanceof CraftingMenu) {
                    input.release();
                    enter(Phase.CRAFT, p);
                    return;
                }
                if (p.tickCount - phaseStart > OPEN_TICKS) {
                    fail("The crafting table wouldn't open");
                    return;
                }
                if (!acted) acted = Aim.useOn(mc, p, table, null, input, settings);
            }
            case PLACE -> place(mc, p);
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
            tries = 0;
            enter(Phase.TABLE, p);
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
        if (phase != Phase.CRAFT) enter(Phase.CRAFT, p);
        startBatch(p);
    }

    private void startBatch(LocalPlayer p) {
        batchStep = Batch.CLEAR;
        batchStart = p.tickCount;
    }

    private void place(Minecraft mc, LocalPlayer p) {
        if (spot != null && mc.level.getBlockState(spot).is(Blocks.CRAFTING_TABLE)) {
            table = spot;
            spot = null;
            tries = 0;
            enter(Phase.TABLE, p);
            return;
        }
        if (p.tickCount - phaseStart > PLACE_TICKS) {
            fail("Couldn't place a crafting table");
            return;
        }
        int slot = -1;
        for (int i = 0; i < 36; i++) if (Inventories.key(p.getInventory().getItem(i)).equals(TABLE)) slot = i;
        if (slot < 0) {
            fail("No crafting table to place");
            return;
        }
        int sel = p.getInventory().getSelectedSlot();
        if (slot != sel) {
            if (slot < 9) p.getInventory().setSelectedSlot(slot);
            else if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId, List.of(Click.swap(slot, sel)));
            phaseStart = p.tickCount; // the clock starts once it's in hand
            return;
        }
        if (spot == null || !mc.level.getBlockState(spot).canBeReplaced()) spot = spot(mc, p);
        if (spot == null) {
            fail("No room to place a crafting table here");
            return;
        }
        if (p.tickCount - usedAt < 10) return; // give the last try time to show up
        if (Aim.useOn(mc, p, spot.below(), Direction.UP, input, settings)) usedAt = p.tickCount;
    }

    /** A free block next to the player, standing on something solid that doesn't open when clicked, in reach. */
    private static BlockPos spot(Minecraft mc, LocalPlayer p) {
        BlockPos feet = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos at = feet.offset(dx, dy, dz), below = at.below();
                    var state = mc.level.getBlockState(below);
                    if (!mc.level.getBlockState(at).canBeReplaced() || !state.isFaceSturdy(mc.level, below, Direction.UP)
                        || state.getBlock() instanceof EntityBlock || state.getMenuProvider(mc.level, below) != null
                        || p.getBoundingBox().intersects(new AABB(at))) continue;
                    double d = at.distToCenterSqr(p.position());
                    if (d < bestD && Aim.at(mc, p, below, Direction.UP) != null) {
                        bestD = d;
                        best = at;
                    }
                }
        return best;
    }

    private static BlockPos nearestTable(Minecraft mc, LocalPlayer p, int r) {
        BlockPos feet = p.blockPosition(), best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int minY = Math.max(mc.level.getMinY(), feet.getY() - r / 2), maxY = Math.min(mc.level.getMaxY(), feet.getY() + r / 2);
        for (int y = minY; y <= maxY; y++)
            for (int x = feet.getX() - r; x <= feet.getX() + r; x++)
                for (int z = feet.getZ() - r; z <= feet.getZ() + r; z++) {
                    at.set(x, y, z);
                    if (!mc.level.getBlockState(at).is(Blocks.CRAFTING_TABLE)) continue;
                    double d = at.distSqr(feet);
                    if (d < bestD) {
                        bestD = d;
                        best = at.immutable();
                    }
                }
        return best;
    }

    /** Plain items (nothing enchanted, named or damaged) in the main inventory and hotbar. */
    private static Map<String, Integer> counts(LocalPlayer p) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String k = Inventories.key(s);
            if (!k.contains("#")) out.merge(k, s.getCount(), Integer::sum);
        }
        return out;
    }

    private static int maxStack(Menu m, int[] slots, String item) {
        for (int s : slots) if (item.equals(m.get(s).item())) return m.get(s).max();
        return 64;
    }

    private static String id(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains(":") ? n : "minecraft:" + n;
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
        acted = false;
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err(msg));
    }

    private void end(Outcome how, String msg) {
        boolean was = phase != Phase.IDLE;
        phase = Phase.IDLE;
        outcome = how;
        steps.clear();
        spot = null;
        trip.cancel();
        input.release();
        if (was) {
            // closing hands back whatever is still in the grid
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null && (p.containerMenu != p.inventoryMenu || !p.inventoryMenu.getCraftSlots().isEmpty())) p.closeContainer();
        }
        if (msg != null) chat.accept(msg);
    }
}
