package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.Enchanting;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.inventory.Menu;
import dev.lazuli.core.inventory.Moves;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.inventory.ClickDriver;
import dev.lazuli.mod.inventory.Inventories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * #enchant at an enchanting table (the best option it can pay for, or the one asked), #repair and #combine at an anvil
 * (a damaged item with its repair material or another copy; an item with an enchanted book). Either block is the
 * nearest one, or one it carries and places (and picks up again after).
 */
public final class EnchantProcess implements Process {
    /** Ticks to wait for the table's offers or the anvil's result to show up. */
    private static final int SHOW_TICKS = 40;

    private enum Mode { ENCHANT, REPAIR, COMBINE }

    private enum Phase { IDLE, FETCH, STATION, PUT, CHOOSE, TAKE, PICKUP }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Station station;
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private Mode mode;
    private String item, with;
    private int count, done, option, since;
    private String doneMsg;
    /** Said once the last clicks have gone through (taking the enchanted item out). */
    private String finishing;

    /** Taking what's missing (lapis, a book, repair material) from remembered chests (chestIngredients). */
    private ChestTake take;
    private Runnable retry;
    private int fetches;
    private static final int MAX_FETCHES = 4;

    public void useChests(ContainerProcess containers, java.util.function.Function<Minecraft, dev.lazuli.core.storage.ChestMemory> chests,
                          java.util.function.Function<Minecraft, String> dimension) {
        this.take = new ChestTake(settings, containers, chests, dimension);
    }

    public EnchantProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
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

    @Override
    public String status() {
        if (phase == Phase.IDLE) return null;
        String what = switch (mode) {
            case ENCHANT -> "Enchant " + name(item) + (count > 1 ? "  " + done + "/" + count : "");
            case REPAIR -> "Repair " + name(item);
            case COMBINE -> "Combine " + name(item) + " + " + name(with);
        };
        return what + (phase == Phase.STATION ? "  (going to the " + stationName() + ")" : phase == Phase.PICKUP ? "  (picking it up)" : "");
    }

    /** The table or anvil the next enchant / repair / combine uses (null: the nearest, or one it places). */
    private BlockPos nextAt, fixedAt;

    public void station(BlockPos at) {
        nextAt = at;
    }

    /** Enchants {@code count} of {@code item} (books too); {@code option} 1-3, or 0 for the best it can pay for. */
    public void enchant(Minecraft mc, String item, int count, int option) {
        begin(Mode.ENCHANT, item, null, Math.max(1, count), option - 1);
        LocalPlayer p = mc.player;
        Runnable again = () -> enchant(mc, item, count, option);
        if (find(p, enchantable()) < 0) {
            if (!fetch(mc, this.item, this.count, again)) fail("No " + name(this.item) + " that can be enchanted");
            return;
        }
        int lapis = Inventories.plainCounts(p).getOrDefault("minecraft:lapis_lazuli", 0);
        if (lapis < 3 * this.count && fetch(mc, "minecraft:lapis_lazuli", 3 * this.count - lapis, again)) return;
        if (lapis == 0) {
            fail("Needs lapis lazuli");
            return;
        }
        if (p.experienceLevel < 1 && !p.hasInfiniteMaterials()) {
            fail("Needs experience levels");
            return;
        }
        go(p);
    }

    /** Repairs the most damaged {@code item}: with its repair material, else another one of it. */
    public void repair(Minecraft mc, String item) {
        begin(Mode.REPAIR, item, null, 1, -1);
        LocalPlayer p = mc.player;
        int left = find(p, damaged());
        if (left < 0) {
            fail("No damaged " + name(this.item));
            return;
        }
        if (right(p, left) < 0) {
            ItemStack l = p.getInventory().getItem(left);
            String mat = material(l);
            int n = mat == null ? 0 : dev.lazuli.core.craft.Enchanting.repairMaterials(l.getDamageValue(), l.getMaxDamage());
            Runnable again = () -> repair(mc, item);
            if (!(mat != null && fetch(mc, mat, n, again)) && !fetch(mc, this.item, 1, again)) {
                fail("Nothing to repair the " + name(this.item) + " with (its material, or another one)");
            }
            return;
        }
        go(p);
    }

    /** Puts {@code item} and {@code with} (an enchanted book, another tool...) together at an anvil. */
    public void combine(Minecraft mc, String item, String with) {
        begin(Mode.COMBINE, item, Inventories.id(with), 1, -1);
        LocalPlayer p = mc.player;
        int left = find(p, s -> true);
        if (left < 0) {
            if (!fetch(mc, this.item, 1, () -> combine(mc, item, with))) fail("No " + name(this.item));
            return;
        }
        if (right(p, left) < 0) {
            if (!fetch(mc, this.with, 1, () -> combine(mc, item, with))) fail("No " + name(this.with));
            return;
        }
        go(p);
    }

    /**
     * Starts taking {@code n} of {@code id} from a remembered chest (chestIngredients), then runs {@code again};
     * false if that's off, none is known, or it has tried enough.
     */
    private boolean fetch(Minecraft mc, String id, int n, Runnable again) {
        if (take == null || !ChestTake.allowed(settings, ChestTake.Use.INGREDIENT) || fetches >= MAX_FETCHES) return false;
        LocalPlayer p = mc.player;
        java.util.function.Predicate<String> m = Inventories.matcher(id);
        if (!take.start(mc, p, id, n, pl -> {
            int c = 0;
            for (int i = 0; i < 36; i++) if (m.test(Inventories.key(pl.getInventory().getItem(i)))) c += pl.getInventory().getItem(i).getCount();
            return c;
        })) return false;
        fetches++;
        retry = again;
        phase = Phase.FETCH;
        chat.accept(Chat.info("Taking {} {} from a chest first", n, name(id)));
        return true;
    }

    /** What repairs {@code s} at an anvil (iron ingot for iron tools...), or null. */
    private static String material(ItemStack s) {
        var r = s.get(net.minecraft.core.component.DataComponents.REPAIRABLE);
        if (r == null) return null;
        return r.items().stream().findFirst()
            .map(h -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).toString()).orElse(null);
    }

    private void begin(Mode mode, String item, String with, int count, int option) {
        int f = phase == Phase.FETCH ? fetches : 0; // a retry after taking from a chest keeps counting
        if (phase != Phase.FETCH) {
            fixedAt = nextAt;
            nextAt = null;
        }
        stop(false);
        fetches = f;
        outcome = Outcome.NONE;
        this.mode = mode;
        this.item = Inventories.id(item);
        this.with = with;
        this.count = count;
        this.option = option;
        done = 0;
        finishing = null;
    }

    private void go(LocalPlayer p) {
        if (p.containerMenu != p.inventoryMenu) {
            fail("Close the open menu first");
            return;
        }
        phase = Phase.STATION;
        boolean table = mode == Mode.ENCHANT;
        Predicate<AbstractContainerMenu> menu = table ? m -> m instanceof EnchantmentMenu : m -> m instanceof AnvilMenu;
        if (fixedAt != null) station.exact();
        station.start(p, table ? s -> s.is(Blocks.ENCHANTING_TABLE) : s -> s.is(BlockTags.ANVIL),
            table ? "minecraft:enchanting_table" : "minecraft:anvil", stationName(), menu, fixedAt,
            settings.enchantPlace.get() && settings.allowPlace.get() && fixedAt == null);
        chat.accept(Chat.ok(switch (mode) {
            case ENCHANT -> "Enchanting " + (count > 1 ? count + " " : "") + name(item);
            case REPAIR -> "Repairing " + name(item);
            case COMBINE -> "Combining " + name(item) + " with " + name(with);
        }));
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        if (phase == Phase.FETCH) take.stop();
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
            case FETCH -> {
                switch (take.tick(mc, p)) {
                    case RUNNING -> {}
                    case CANCELLED -> end(Outcome.CANCELLED, null);
                    case DONE -> {
                        Runnable r = retry;
                        r.run();
                    }
                }
            }
            case STATION -> {
                switch (station.tick(mc, p)) {
                    case OPEN -> phase = Phase.PUT;
                    case FAILED -> fail(station.error());
                    case WORKING -> {}
                }
            }
            case PUT, CHOOSE, TAKE -> {
                if (mode == Mode.ENCHANT) table(mc, p);
                else anvil(mc, p);
            }
            case PICKUP -> {
                if (station.pickUp(mc, p)) end(Outcome.DONE, doneMsg);
            }
            default -> {}
        }
    }

    // ---- enchanting table ----

    private void table(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof EnchantmentMenu em)) {
            fail("The enchanting table closed");
            return;
        }
        if (clicks.busy()) return;
        if (finishing != null) {
            finish(p, finishing);
            return;
        }
        ItemStack in = em.slots.get(0).getItem();
        switch (phase) {
            case PUT -> {
                Menu sim = Inventories.read(em, p);
                int[] mine = Inventories.slots(em, p, true);
                List<Click> plan = new ArrayList<>();
                if (in.isEmpty()) {
                    int slot = find(em, p, enchantable());
                    if (slot < 0) {
                        finish(p, done > 0 ? Chat.ok("Enchanted {} {} (no more to enchant)", done, name(item))
                            : Chat.err("No " + name(item) + " that can be enchanted"));
                        return;
                    }
                    add(sim, plan, Moves.put(sim, mine, 0, sim.get(slot).item(), 1, Moves.Quick.EXACT));
                }
                int lapis = em.slots.get(1).getItem().getCount();
                int more = Math.min(3 - lapis, sim.count(mine, k -> k.equals("minecraft:lapis_lazuli")));
                if (more > 0) add(sim, plan, Moves.put(sim, mine, 1, "minecraft:lapis_lazuli", more, lapis == 0 ? Moves.Quick.OVERFILL : Moves.Quick.EXACT));
                if (!plan.isEmpty()) clicks.play(em.containerId, plan);
                phase = Phase.CHOOSE;
                since = p.tickCount;
            }
            case CHOOSE -> {
                boolean offered = em.costs[0] > 0 || em.costs[1] > 0 || em.costs[2] > 0;
                if (!offered && p.tickCount - since < SHOW_TICKS) return;
                int lapis = em.slots.get(1).getItem().getCount();
                int levels = p.hasInfiniteMaterials() ? Integer.MAX_VALUE : p.experienceLevel;
                int pick = Enchanting.pick(em.costs, levels, p.hasInfiniteMaterials() ? 3 : lapis, option);
                if (pick < 0) {
                    String why = !offered ? "The table offers nothing for it" : lapis == 0 ? "Out of lapis lazuli"
                        : "Needs " + need(em.costs, option) + " levels (has " + p.experienceLevel + ")";
                    finish(p, done > 0 ? Chat.warn("Enchanted {} {}; {}", done, name(item), why) : Chat.err(why));
                    return;
                }
                mc.gameMode.handleInventoryButtonClick(em.containerId, pick);
                phase = Phase.TAKE;
                since = p.tickCount;
            }
            case TAKE -> {
                if (!in.isEnchanted() && !in.is(Items.ENCHANTED_BOOK)) {
                    if (p.tickCount - since > SHOW_TICKS) fail("The enchanting didn't happen");
                    return;
                }
                clicks.play(em.containerId, List.of(Click.shift(0)));
                done++;
                if (done >= count) finishing = Chat.ok("Enchanted {} {}", done, name(item));
                else phase = Phase.PUT;
            }
            default -> {}
        }
    }

    private static int need(int[] costs, int option) {
        if (option >= 0) return costs[option];
        int least = Integer.MAX_VALUE;
        for (int c : costs) if (c > 0) least = Math.min(least, c);
        return least;
    }

    // ---- anvil ----

    private void anvil(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof AnvilMenu am)) {
            fail("The anvil closed");
            return;
        }
        if (clicks.busy()) return;
        switch (phase) {
            case PUT -> {
                Menu sim = Inventories.read(am, p);
                int[] mine = Inventories.slots(am, p, true);
                List<Click> plan = new ArrayList<>();
                // anything left in it comes out first
                for (int s = 0; s < 2; s++) if (!am.slots.get(s).getItem().isEmpty()) add(sim, plan, List.of(Click.shift(s)));
                int left = find(am, p, mode == Mode.REPAIR ? damaged() : s -> true);
                if (left < 0) {
                    fail("No " + (mode == Mode.REPAIR ? "damaged " : "") + name(item));
                    return;
                }
                ItemStack l = am.slots.get(left).getItem();
                int right = rightSlot(am, p, left);
                if (right < 0) {
                    fail("Nothing to put with the " + name(item));
                    return;
                }
                ItemStack r = am.slots.get(right).getItem();
                int n = l.isValidRepairItem(r) ? Math.min(r.getCount(), Enchanting.repairMaterials(l.getDamageValue(), l.getMaxDamage())) : 1;
                String lk = sim.get(left).item(), rk = sim.get(right).item();
                add(sim, plan, Moves.put(sim, mine, 0, lk, 1));
                add(sim, plan, Moves.put(sim, mine, 1, rk, n));
                clicks.play(am.containerId, plan);
                phase = Phase.CHOOSE;
                since = p.tickCount;
            }
            case CHOOSE -> {
                if (am.slots.get(2).getItem().isEmpty()) {
                    if (p.tickCount - since > SHOW_TICKS) fail("The anvil can't put those together");
                    return;
                }
                int cost = am.getCost();
                if (!p.hasInfiniteMaterials()) {
                    if (cost >= Enchanting.TOO_EXPENSIVE) {
                        fail("Too expensive (" + cost + " levels)");
                        return;
                    }
                    if (p.experienceLevel < cost) {
                        fail("Needs " + cost + " levels (has " + p.experienceLevel + ")");
                        return;
                    }
                }
                clicks.play(am.containerId, List.of(Click.shift(2)));
                phase = Phase.TAKE;
                since = p.tickCount;
            }
            case TAKE -> {
                if (!am.slots.get(2).getItem().isEmpty() && p.tickCount - since < SHOW_TICKS) return;
                finish(p, Chat.ok(mode == Mode.REPAIR ? "Repaired the " + name(item) : "Combined the " + name(item)));
            }
            default -> {}
        }
    }

    /** The slot (in the open menu) of what goes in the anvil's right side with the item at {@code left}, or -1. */
    private int rightSlot(AbstractContainerMenu m, LocalPlayer p, int left) {
        ItemStack l = m.slots.get(left).getItem();
        int[] mine = Inventories.slots(m, p, true);
        if (mode == Mode.COMBINE) {
            for (int s : mine) if (s != left && Inventories.matcher(with).test(Inventories.key(m.slots.get(s).getItem()))) return s;
            return -1;
        }
        for (int s : mine) if (l.isValidRepairItem(m.slots.get(s).getItem())) return s;
        // another of the same: the least worth keeping (the most damaged other than this one)
        int best = -1;
        for (int s : mine) {
            ItemStack o = m.slots.get(s).getItem();
            if (s == left || !o.is(l.getItem()) || !o.isDamageableItem()) continue;
            if (best < 0 || o.getDamageValue() > m.slots.get(best).getItem().getDamageValue()) best = s;
        }
        return best;
    }

    /** Before opening: is there something to put with the item at inventory slot {@code left}? */
    private int right(LocalPlayer p, int left) {
        return rightSlot(p.inventoryMenu, p, toMenu(left));
    }

    private Predicate<ItemStack> enchantable() {
        return s -> s.isEnchantable() && matches(s);
    }

    private Predicate<ItemStack> damaged() {
        return s -> s.isDamaged() && matches(s);
    }

    private boolean matches(ItemStack s) {
        return !s.isEmpty() && Inventories.matcher(item).test(Inventories.key(s));
    }

    /** An inventory slot (0-35) holding a matching item (the most damaged one), or -1. */
    private static int find(LocalPlayer p, Predicate<ItemStack> test) {
        int best = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!test.test(s)) continue;
            if (best < 0 || s.getDamageValue() > p.getInventory().getItem(best).getDamageValue()) best = i;
        }
        return best;
    }

    /** The open menu's slot holding a matching item of the player's (the most damaged one), or -1. */
    private static int find(AbstractContainerMenu m, LocalPlayer p, Predicate<ItemStack> test) {
        int best = -1;
        for (int s : Inventories.slots(m, p, true)) {
            ItemStack st = m.slots.get(s).getItem();
            if (!test.test(st)) continue;
            if (best < 0 || st.getDamageValue() > m.slots.get(best).getItem().getDamageValue()) best = s;
        }
        return best;
    }

    /** The inventory menu's slot for inventory slot i (hotbar 0-8 is 36-44, the rest 9-35 as is). */
    private static int toMenu(int i) {
        return i < 9 ? 36 + i : i;
    }

    private static void add(Menu sim, List<Click> plan, List<Click> clicks) {
        sim.applyAll(clicks);
        plan.addAll(clicks);
    }

    private String stationName() {
        return mode == Mode.ENCHANT ? "enchanting table" : "anvil";
    }

    private static String name(String id) {
        return id == null ? "" : id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** Done: picks up a block it placed itself, then says {@code msg}. */
    private void finish(LocalPlayer p, String msg) {
        if (station.startPickUp(p)) {
            doneMsg = msg;
            phase = Phase.PICKUP;
            return;
        }
        end(Outcome.DONE, msg);
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err(msg));
    }

    private void end(Outcome how, String msg) {
        boolean was = phase != Phase.IDLE;
        phase = Phase.IDLE;
        outcome = how;
        station.cancel();
        if (was) {
            LocalPlayer p = Minecraft.getInstance().player;
            // anything left in the table or anvil comes back to the inventory as it closes
            if (p != null && (p.containerMenu instanceof EnchantmentMenu || p.containerMenu instanceof AnvilMenu)) p.closeContainer();
        }
        if (msg != null) chat.accept(msg);
    }
}
