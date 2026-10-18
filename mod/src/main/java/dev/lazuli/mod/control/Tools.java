package dev.lazuli.mod.control;

import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.mod.world.BlockClassifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The hotbar as the planner sees it: how fast each block breaks with the best tool, and how many blocks it can place. */
public final class Tools {
    private static final double INF = Double.POSITIVE_INFINITY;

    private Tools() {}

    /** Snapshot for the planner, taken on the client thread; safe to query from the planner thread. */
    public static Interaction snapshot(Player p, Settings settings) {
        if (p == null) return Interaction.NONE;
        ItemStack[] hotbar = new ItemStack[9];
        for (int i = 0; i < 9; i++) hotbar[i] = p.getInventory().getItem(i).copy();
        Holder<Enchantment> efficiency = efficiency(p);
        float haste = haste(p);
        boolean creative = p.getAbilities().instabuild;
        int throwaways = 0;
        Set<String> names = throwawayNames(settings);
        Map<String, Integer> spare = spares(p, settings);
        for (ItemStack s : hotbar) {
            if (throwawayRank(s, names) <= 0) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            int left = spare.getOrDefault(id, Integer.MAX_VALUE), use = Math.max(0, Math.min(s.getCount(), left));
            throwaways += use;
            if (spare.containsKey(id)) spare.put(id, left - use);
        }
        Map<Short, Double> cache = new ConcurrentHashMap<>();
        Interaction.BreakTime time = phys -> cache.computeIfAbsent(phys.id, id -> {
            if (creative) return phys.hardness < 0 ? INF : 1.0;
            BlockState s = BlockClassifier.state(phys.id);
            if (s == null) return Interaction.handTicks(phys, false);
            // digging through stone by hand (a block that drops nothing without the right tool): never on the way
            if (settings.breakNeedsTool.get() && s.requiresCorrectToolForDrops()) {
                boolean tool = false;
                for (ItemStack stack : hotbar) tool |= !wornOut(stack) && stack.isCorrectToolForDrops(s);
                if (!tool) return INF;
            }
            double best = INF;
            for (ItemStack stack : hotbar) best = Math.min(best, wornOut(stack) ? INF : ticks(stack, s, efficiency, haste));
            best = Math.min(best, ticks(ItemStack.EMPTY, s, efficiency, haste));
            return best;
        });
        return new Interaction(true, true, throwaways, time);
    }

    /** Ticks to break {@code s} holding {@code stack}: vanilla's destroy progress per tick, inverted. */
    public static double ticks(ItemStack stack, BlockState s, Holder<Enchantment> efficiency, float haste) {
        float hardness = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hardness < 0) return INF;
        if (hardness == 0) return 1;
        float f = stack.getDestroySpeed(s);
        if (f > 1 && efficiency != null) {
            int lvl = EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack);
            if (lvl > 0) f += lvl * lvl + 1;
        }
        f *= haste;
        boolean harvest = !s.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(s);
        double progress = f / hardness / (harvest ? 30.0 : 100.0);
        return progress >= 1 ? 1 : Math.ceil(1 / progress);
    }

    /** Hotbar slot that breaks {@code s} fastest; the selected slot on ties. */
    public static int bestTool(Player p, BlockState s) {
        return best(p, s, 9);
    }

    /** Durability left before a tool counts as worn out ({@code saveTools}). */
    private static final int SPARE_DURABILITY = 3;
    /** Set from the settings each tick: whether worn-out tools are off limits. */
    public static volatile boolean saveTools = true;

    /** A tool about to break, kept back so it can be repaired. */
    public static boolean wornOut(ItemStack s) {
        return saveTools && s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= SPARE_DURABILITY;
    }

    /** Ticks to break {@code s} with {@code stack}, never with a worn-out tool. */
    private static double usableTicks(ItemStack stack, BlockState s, Holder<Enchantment> eff, float haste) {
        return wornOut(stack) ? INF : ticks(stack, s, eff, haste);
    }

    /** Set from the settings each tick: prefer a fresh tool over a nearly broken one (mineSpareTool). */
    public static volatile boolean spareTool = true;

    /** Slot among the first {@code slots} inventory slots that breaks {@code s} fastest (the selected one on ties). */
    private static int best(Player p, BlockState s, int slots) {
        Holder<Enchantment> eff = efficiency(p);
        float haste = haste(p);
        int sel = p.getInventory().getSelectedSlot();
        if (s.requiresCorrectToolForDrops()) {
            // a block that drops nothing without the right tool: only a tool that gets the drop, never a faster wrong one
            java.util.List<dev.lazuli.core.craft.ToolChoice.Cand> cands = new java.util.ArrayList<>();
            for (int i = 0; i < slots; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty() || !st.isCorrectToolForDrops(s)) continue;
                int left = st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : Integer.MAX_VALUE;
                cands.add(new dev.lazuli.core.craft.ToolChoice.Cand(i, true, ticks(st, s, eff, haste), left));
            }
            int slot = dev.lazuli.core.craft.ToolChoice.best(cands, sel, true, SPARE_DURABILITY, spareTool);
            if (slot >= 0) return slot;
        }
        int best = sel;
        double bestTicks = usableTicks(p.getInventory().getItem(sel), s, eff, haste);
        if (wornOut(p.getInventory().getItem(sel))) bestTicks = ticks(ItemStack.EMPTY, s, eff, haste) + 0.5;
        for (int i = 0; i < slots; i++) {
            double t = usableTicks(p.getInventory().getItem(i), s, eff, haste);
            if (t < bestTicks) {
                bestTicks = t;
                best = i;
            }
        }
        // only a worn-out tool in hand and nothing better: an empty slot is the bare hand
        if (wornOut(p.getInventory().getItem(best))) {
            for (int i = 0; i < 9; i++) if (p.getInventory().getItem(i).isEmpty()) return i;
        }
        return best;
    }

    /**
     * Selects the fastest tool for {@code s}, first moving it into the hotbar from the rest of the inventory when it's
     * there (allowInventory, and only with no screen open).
     */
    public static void equipBestTool(net.minecraft.client.Minecraft mc, Player p, BlockState s, Settings settings) {
        saveTools = settings.saveTools.get();
        spareTool = settings.mineSpareTool.get();
        int slot = best(p, s, settings.allowInventory.get() && mc.screen == null ? 36 : 9);
        if (slot >= 9) {
            // swap it into the selected hotbar slot (menu slots: 9-35 are the main inventory); the click driver may
            // first stop and open the inventory, so it's selected once it's there
            var clicks = dev.lazuli.mod.inventory.ClickDriver.get();
            if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId,
                java.util.List.of(dev.lazuli.core.inventory.Click.swap(slot, p.getInventory().getSelectedSlot())));
            return;
        }
        p.getInventory().setSelectedSlot(slot);
    }

    /**
     * Hotbar slot to place from, or -1: a listed throwaway block (the selected one if it is), else any plain full
     * block nobody would miss.
     */
    public static int throwaway(Player p, Settings settings) {
        Set<String> names = throwawayNames(settings);
        Map<String, Integer> spare = spares(p, settings);
        int sel = p.getInventory().getSelectedSlot();
        int best = -1, bestRank = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || spare.getOrDefault(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), 1) <= 0) continue;
            int r = throwawayRank(s, names) * 2 + (i == sel ? 1 : 0);
            if (r > 1 && r > bestRank) {
                bestRank = r;
                best = i;
            }
        }
        return best;
    }

    /**
     * Menu slot (inventory screen numbering) of a stack of junk to throw out when the inventory is full: a listed
     * throwaway block first, else any plain full block, never what a process is collecting. -1 if none.
     */
    public static int junkSlot(Player p, Settings settings) {
        Set<String> names = throwawayNames(settings);
        Map<String, Integer> spare = spares(p, settings);
        int best = -1, bestRank = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || spare.getOrDefault(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), 1) <= 0) continue;
            int r = throwawayRank(s, names) * 100 + s.getCount();
            if (r > 100 && r > bestRank) {
                bestRank = r;
                best = i;
            }
        }
        return best < 0 ? -1 : best < 9 ? best + 36 : best; // hotbar is menu slots 36-44
    }

    /** Items processes are collecting, by process: how many of each it needs (Integer.MAX_VALUE: all). */
    private static final Map<String, Map<String, Integer>> KEEP = new ConcurrentHashMap<>();

    /** Forgets what every process was collecting (#kill). */
    public static void keepNothing() {
        KEEP.clear();
    }

    /** {@code who} is collecting these (an empty map: nothing any more); see keepCollected. */
    public static void keep(String who, Map<String, Integer> items) {
        if (items == null || items.isEmpty()) KEEP.remove(who);
        else KEEP.put(who, Map.copyOf(items));
    }

    /** Whether a running process is collecting {@code id} (restocking leaves those alone). */
    public static boolean collecting(String id) {
        for (Map<String, Integer> m : KEEP.values()) if (m.containsKey(id)) return true;
        return false;
    }

    /** For each kept item: how many beyond what's kept the inventory holds (may be 0 or less); others unlimited. */
    private static Map<String, Integer> spares(Player p, Settings settings) {
        Map<String, Integer> spare = new java.util.HashMap<>();
        if (!settings.keepCollected.get() || KEEP.isEmpty()) return spare;
        for (Map<String, Integer> m : KEEP.values()) m.forEach((k, v) -> spare.merge(k, -v, Math::min));
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
            spare.computeIfPresent(id, (k, v) -> v + s.getCount());
        }
        return spare;
    }

    /** Values that would hurt to place, bounce, burn, fall or open a menu. */
    private static final Set<String> NOT_THROWAWAY = Set.of("tnt", "slime_block", "honey_block", "magma_block",
        "soul_sand", "ancient_debris", "diamond_block", "emerald_block", "gold_block", "iron_block", "netherite_block",
        "lapis_block", "redstone_block", "copper_block", "raw_iron_block", "raw_gold_block", "raw_copper_block",
        "amethyst_block", "budding_amethyst", "spawner", "beacon", "crafting_table", "dragon_egg");

    /** 2 for a listed throwaway, 1 for any other plain full block, 0 for anything else. */
    private static int throwawayRank(ItemStack s, Set<String> names) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return 0;
        var id = BuiltInRegistries.ITEM.getKey(s.getItem());
        if (names.contains(id.toString()) || names.contains(id.getPath())) return 2;
        Block b = bi.getBlock();
        String path = id.getPath();
        if (b instanceof EntityBlock || b instanceof FallingBlock || path.contains("ore") || NOT_THROWAWAY.contains(path)) return 0;
        return b.defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) ? 1 : 0;
    }

    private static Set<String> throwawayNames(Settings settings) {
        return new java.util.HashSet<>(java.util.Arrays.asList(settings.throwawayBlocks.get().toLowerCase(Locale.ROOT).split(",")));
    }

    private static Holder<Enchantment> efficiency(Player p) {
        return p.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(Enchantments.EFFICIENCY).orElse(null);
    }

    private static float haste(Player p) {
        return MobEffectUtil.hasDigSpeed(p) ? 1.0F + (MobEffectUtil.getDigSpeedAmplification(p) + 1) * 0.2F : 1.0F;
    }
}
