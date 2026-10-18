package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.craft.Recipes;
import dev.lazuli.core.craft.SmithRecipe;
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
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Upgrades at a smithing table (netherite gear: template + diamond item + netherite ingot): uses the nearest table, or
 * places one it has, puts the three inputs in, takes the result, once per item asked for.
 */
public final class SmithProcess implements Process {
    private static final int TIMEOUT_TICKS = 80;

    private enum Phase { IDLE, STATION, LOAD, WAIT_RESULT, WAIT_CLEAR, PICKUP }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Station station;
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private SmithRecipe recipe;
    private ItemCount asked;
    private String doneMsg;
    private int left, before, since;

    public SmithProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.station = new Station(session, settings, clicks);
    }

    @Override
    public String status() {
        if (phase == Phase.IDLE || asked == null) return null;
        return "Upgrade " + asked.count() + " " + asked.item().replace("minecraft:", "")
            + (phase == Phase.STATION ? "  (going to the smithing table)" : "");
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** @param item what comes out (netherite_pickaxe); the template, base item and addition must be in the inventory */
    public void start(Minecraft mc, ItemCount item) {
        stop(false);
        outcome = Outcome.NONE;
        LocalPlayer p = mc.player;
        if (!settings.getItemSmith.get()) {
            fail("Smithing is off (getItemSmith)");
            return;
        }
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
        int n = Math.max(1, item.count());
        Map<String, Integer> have = Inventories.plainCounts(p);
        recipe = null;
        for (SmithRecipe r : recipes.smithing(id)) {
            if (pick(r.template(), have) != null && pick(r.base(), have) != null && pick(r.addition(), have) != null) {
                recipe = r;
                break;
            }
        }
        if (recipe == null) {
            fail(recipes.smithing(id).isEmpty() ? "There's no smithing recipe for " + short_(id)
                : "Missing a template, base item or addition for " + short_(id));
            return;
        }
        asked = new ItemCount(id, n);
        left = n;
        before = count(p, id);
        phase = Phase.STATION;
        chat.accept(Chat.info("Upgrading to {} {}", n, short_(id)));
        station.start(p, Blocks.SMITHING_TABLE, "minecraft:smithing_table", "smithing table", m -> m instanceof SmithingMenu, null,
            settings.craftPlaceTable.get() && settings.allowPlace.get());
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
            case STATION -> {
                switch (station.tick(mc, p)) {
                    case OPEN -> phase = Phase.LOAD;
                    case FAILED -> fail(station.error());
                    case WORKING -> {}
                }
            }
            case LOAD, WAIT_RESULT, WAIT_CLEAR -> table(mc, p);
            case PICKUP -> {
                if (station.pickUp(mc, p)) end(Outcome.DONE, doneMsg);
            }
            default -> {}
        }
    }

    private void table(Minecraft mc, LocalPlayer p) {
        if (!(p.containerMenu instanceof SmithingMenu sm)) {
            fail("The smithing table closed");
            return;
        }
        if (clicks.busy()) return;
        ItemStack result = sm.slots.get(3).getItem();
        switch (phase) {
            case LOAD -> {
                for (int i = 0; i < 3; i++) {
                    if (!sm.slots.get(i).getItem().isEmpty()) {
                        fail("The smithing table already has things in it");
                        return;
                    }
                }
                Map<String, Integer> have = Inventories.plainCounts(p);
                String t = pick(recipe.template(), have), b = pick(recipe.base(), have), a = pick(recipe.addition(), have);
                if (t == null || b == null || a == null) {
                    fail("Ran out of ingredients for " + short_(asked.item()));
                    return;
                }
                int[] mine = Inventories.slots(sm, p, true);
                Menu sim = Inventories.read(sm, p);
                List<Click> plan = new ArrayList<>();
                try {
                    String[] ins = {t, b, a};
                    for (int slot = 0; slot < 3; slot++) {
                        List<Click> c = Moves.put(sim, mine, slot, ins[slot], 1);
                        sim.applyAll(c);
                        plan.addAll(c);
                    }
                } catch (IllegalStateException e) {
                    fail(e.getMessage() + " left in the inventory");
                    return;
                }
                clicks.play(sm.containerId, plan);
                since = p.tickCount;
                phase = Phase.WAIT_RESULT;
            }
            case WAIT_RESULT -> {
                if (!result.isEmpty()) {
                    clicks.play(sm.containerId, List.of(Click.shift(3)));
                    since = p.tickCount;
                    phase = Phase.WAIT_CLEAR;
                } else if (p.tickCount - since > TIMEOUT_TICKS) {
                    fail("The smithing table gave no " + short_(asked.item()) + " (wrong items for it?)");
                }
            }
            case WAIT_CLEAR -> {
                if (result.isEmpty() && sm.slots.get(1).getItem().isEmpty()) {
                    if (--left > 0) phase = Phase.LOAD;
                    else finish(p);
                } else if (p.tickCount - since > TIMEOUT_TICKS) {
                    fail("Couldn't take the " + short_(asked.item()) + " (inventory full?)");
                }
            }
            default -> {}
        }
    }

    private void finish(LocalPlayer p) {
        int got = count(p, asked.item()) - before;
        String msg = Chat.ok("Upgraded {} {}", asked.count(), short_(asked.item()));
        if (got < asked.count()) {
            fail("Only got " + Math.max(0, got) + " of " + asked.count() + " " + short_(asked.item()));
        } else if (station.startPickUp(p)) {
            doneMsg = msg;
            phase = Phase.PICKUP;
        } else {
            end(Outcome.DONE, msg);
        }
    }

    /** Of these, one the inventory has. */
    private static String pick(Set<String> options, Map<String, Integer> have) {
        for (String o : options) if (have.getOrDefault(o, 0) > 0) return o;
        return null;
    }

    private static int count(LocalPlayer p, String item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && Inventories.key(s).equals(item)) n += s.getCount();
        }
        return n;
    }

    private static String short_(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
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
            if (p != null && p.containerMenu instanceof SmithingMenu) p.closeContainer();
        }
        if (msg != null) chat.accept(msg);
    }
}
