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
        for (ItemStack s : hotbar) if (isThrowaway(s, names)) throwaways += s.getCount();
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

    /** Hotbar slot holding a throwaway block (the selected one if it does), or -1. */
    public static int throwaway(Player p, Settings settings) {
        Set<String> names = throwawayNames(settings);
        int sel = p.getInventory().getSelectedSlot();
        if (isThrowaway(p.getInventory().getItem(sel), names)) return sel;
        for (int i = 0; i < 9; i++) if (isThrowaway(p.getInventory().getItem(i), names)) return i;
        return -1;
    }

    private static boolean isThrowaway(ItemStack s, Set<String> names) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem)) return false;
        var id = BuiltInRegistries.ITEM.getKey(s.getItem());
        return names.contains(id.toString()) || names.contains(id.getPath());
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
