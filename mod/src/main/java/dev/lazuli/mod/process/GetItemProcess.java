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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.Consumer;

/**
 * #getitem: plans mine / craft / smelt steps to get an item from what's in the inventory (tools, fuel, crafting table
 * and furnace too), runs the first step with the usual processes, then plans again from the real inventory.
 */
public final class GetItemProcess implements Process {
    private static final int MAX_STEPS = 200, SAME_STEP_TRIES = 3;

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private enum Status { RUNNING, OK, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final MineProcess mine;
    private final CraftProcess crafts;
    private final SmeltProcess smelts;
    private boolean running;
    private Outcome outcome = Outcome.NONE;
    private String target, lastStep;
    private int want, steps, repeats;
    private ItemPlanner.Action current;

    public GetItemProcess(Settings settings, MineProcess mine, CraftProcess crafts, SmeltProcess smelts, Consumer<String> chat) {
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

    public Outcome outcome() {
        return outcome;
    }

    /** Gets {@code item} until the inventory holds at least {@code count} (a negative count: 1). */
    public void start(Minecraft mc, ItemCount item) {
        stop(false);
        outcome = Outcome.NONE;
        target = Inventories.id(item.item());
        want = Math.max(1, item.count());
        Identifier id = Identifier.tryParse(target);
        if (id == null || BuiltInRegistries.ITEM.getOptional(id).isEmpty()) {
            end(Outcome.FAILED, Chat.err("Unknown item: {}", item.item()));
            return;
        }
        RecipeData.get();
        RecipeData.drops();
        running = true;
        current = null;
        lastStep = null;
        steps = repeats = 0;
        chat.accept(Chat.ok("Getting {} {}", want, name(target)));
    }

    @Override
    public void stop(boolean announce) {
        if (!running) return;
        if (current != null) {
            switch (current.kind()) {
                case MINE -> mine.stop(false);
                case CRAFT -> crafts.stop(false);
                case SMELT -> smelts.stop(false);
            }
        }
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (!running) return;
        LocalPlayer p = mc.player;
        if (current != null) {
            switch (status(current)) {
                case RUNNING -> {
                    return;
                }
                case FAILED -> {
                    end(Outcome.FAILED, Chat.err("Couldn't {} {}: stuck getting {}", verb(current), name(current.item()), name(target)));
                    return;
                }
                case CANCELLED -> {
                    end(Outcome.CANCELLED, null);
                    return;
                }
                case OK -> current = null;
            }
        }
        if (count(p, target) >= want) {
            end(Outcome.DONE, Chat.ok("Got {} {}", want, name(target)));
            return;
        }
        if (p.containerMenu != p.inventoryMenu) return; // a step's menu still closing
        Recipes recipes = RecipeData.get().getNow(null);
        Drops drops = RecipeData.drops().getNow(null);
        if (recipes == null || drops == null) return; // still reading the game's data
        if (++steps > MAX_STEPS) {
            end(Outcome.FAILED, Chat.err("Gave up after {} steps", MAX_STEPS));
            return;
        }
        ItemPlanner planner = new ItemPlanner(recipes, drops, GetItemProcess::tier, SmeltProcess.burn(mc), smelts.fuels(),
            new ItemPlanner.Rules(settings.getItemMine.get(), settings.getItemSmelt.get(),
                settings.craftPlaceTable.get() && settings.allowPlace.get(), settings.smeltPlaceFurnace.get() && settings.allowPlace.get()));
        int range = settings.stationRange.get();
        ItemPlanner.Plan plan = planner.plan(target, want, Inventories.plainCounts(p), pickTier(p),
            Station.nearest(mc, p, Blocks.CRAFTING_TABLE, range) != null, Station.nearest(mc, p, Blocks.FURNACE, range) != null);
        if (!plan.ok()) {
            end(Outcome.FAILED, Chat.err("Can't get {}: {}", name(target), plan.problem()));
            return;
        }
        if (plan.actions().isEmpty()) {
            end(Outcome.FAILED, Chat.err("Nothing left to do, but there's no {} (only enchanted or damaged ones?)", name(target)));
            return;
        }
        ItemPlanner.Action a = plan.actions().get(0);
        String key = a.toString();
        repeats = key.equals(lastStep) ? repeats + 1 : 0;
        lastStep = key;
        if (repeats >= SAME_STEP_TRIES) {
            end(Outcome.FAILED, Chat.err("Stuck: {} {} {} keeps not helping", verb(a), a.count(), name(a.item())));
            return;
        }
        chat.accept(Chat.info("Step {}/{}: {} {} {}", 1, plan.actions().size(), verb(a), a.count(), name(a.item())));
        current = a;
        switch (a.kind()) {
            case MINE -> mine.start(mc, a.count(), a.blocks());
            case CRAFT -> crafts.start(mc, List.of(new ItemCount(a.item(), a.count())));
            case SMELT -> smelts.start(mc, new ItemCount(a.item(), a.count()), true);
        }
    }

    private Status status(ItemPlanner.Action a) {
        return switch (a.kind()) {
            case MINE -> mine.active() ? Status.RUNNING : mine.cancelled() ? Status.CANCELLED
                : mine.mined() > 0 ? Status.OK : Status.FAILED;
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
        };
    }

    private void end(Outcome how, String msg) {
        running = false;
        outcome = how;
        current = null;
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

    /** Any of {@code item} in the main inventory and hotbar, enchanted or worn too. */
    private static int count(LocalPlayer p, String item) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).toString().equals(item)) n += s.getCount();
        }
        return n;
    }

    private static String verb(ItemPlanner.Action a) {
        return switch (a.kind()) {
            case MINE -> "mine";
            case CRAFT -> "craft";
            case SMELT -> "smelt";
        };
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
