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
        for (ItemStack s : hotbar) if (throwawayRank(s, names) > 0) throwaways += s.getCount();
        Map<Short, Double> cache = new ConcurrentHashMap<>();
        Interaction.BreakTime time = phys -> cache.computeIfAbsent(phys.id, id -> {
            if (creative) return phys.hardness < 0 ? INF : 1.0;
            BlockState s = BlockClassifier.state(phys.id);
            if (s == null) return Interaction.handTicks(phys, false);
            double best = INF;
            for (ItemStack stack : hotbar) best = Math.min(best, ticks(stack, s, efficiency, haste));
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
        Holder<Enchantment> eff = efficiency(p);
        float haste = haste(p);
        int sel = p.getInventory().getSelectedSlot();
        int best = sel;
        double bestTicks = ticks(p.getInventory().getItem(sel), s, eff, haste);
        for (int i = 0; i < 9; i++) {
            double t = ticks(p.getInventory().getItem(i), s, eff, haste);
            if (t < bestTicks) {
                bestTicks = t;
                best = i;
            }
        }
        return best;
    }

    /**
     * Hotbar slot to place from, or -1: a listed throwaway block (the selected one if it is), else any plain full
     * block nobody would miss.
     */
    public static int throwaway(Player p, Settings settings) {
        Set<String> names = throwawayNames(settings);
        int sel = p.getInventory().getSelectedSlot();
        int best = -1, bestRank = 0;
        for (int i = 0; i < 9; i++) {
            int r = throwawayRank(p.getInventory().getItem(i), names) * 2 + (i == sel ? 1 : 0);
            if (r > 1 && r > bestRank) {
                bestRank = r;
                best = i;
            }
        }
        return best;
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
