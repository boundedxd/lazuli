package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Looking after the player while Lazuli is busy: eats from the hotbar when hungry (movement pauses meanwhile) and
 * puts on better armor from the inventory.
 */
public final class Upkeep {
    /** Foods not worth the side effects, or too good to eat on the way. */
    private static final Set<String> AVOID = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish",
        "chorus_fruit", "suspicious_stew", "golden_apple", "enchanted_golden_apple", "raw_chicken", "chicken");
    private static final int EAT_TIMEOUT = 80;
    private static final int ARMOR_EVERY = 20;
    /** Menu slots of the armor pieces in the player's inventory screen. */
    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int[] ARMOR_MENU_SLOT = {5, 6, 7, 8};

    private static volatile boolean eating;
    private final Settings settings;
    private final Consumer<String> chat;
    private int eatTicks, eatFood, prevSlot = -1;

    public Upkeep(Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
    }

    /** True while eating: movement and processes wait. */
    public static boolean eating() {
        return eating;
    }

    /** @param busy Lazuli is pathing or running a process */
    public void tick(Minecraft mc, boolean busy) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        eat(mc, p, busy);
        if (busy && !eating && settings.autoArmor.get() && mc.screen == null && p.tickCount % ARMOR_EVERY == 0) armor(mc, p);
    }

    private void eat(Minecraft mc, LocalPlayer p, boolean busy) {
        int food = p.getFoodData().getFoodLevel();
        if (eating) {
            eatTicks++;
            boolean done = food > eatFood || eatTicks > EAT_TIMEOUT || !busy || eatTicks > 5 && !p.isUsingItem();
            if (done) {
                eating = false;
                mc.options.keyUse.setDown(false);
                if (p.isUsingItem()) mc.gameMode.releaseUsingItem(p);
                if (prevSlot >= 0) p.getInventory().setSelectedSlot(prevSlot);
                prevSlot = -1;
                return;
            }
            mc.options.keyUse.setDown(true); // vanilla stops eating the moment use isn't held
            return;
        }
        if (!busy || !settings.autoEat.get() || food > settings.autoEatHunger.get() || mc.screen != null || p.isUsingItem()) return;
        int slot = bestFood(p);
        if (slot < 0) return;
        prevSlot = p.getInventory().getSelectedSlot();
        p.getInventory().setSelectedSlot(slot);
        if (!mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction()) {
            p.getInventory().setSelectedSlot(prevSlot);
            prevSlot = -1;
            return;
        }
        eating = true;
        eatTicks = 0;
        eatFood = food;
        mc.options.keyUse.setDown(true);
        chat.accept(Chat.info("Eating {}", p.getInventory().getItem(slot).getHoverName().getString()));
    }

    /** Hotbar slot of the most filling food worth eating, or -1. */
    private static int bestFood(LocalPlayer p) {
        int best = -1;
        float bestValue = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getItem(i);
            FoodProperties f = s.get(DataComponents.FOOD);
            if (f == null || AVOID.contains(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())) continue;
            float v = f.nutrition() + f.saturation();
            if (v > bestValue) {
                bestValue = v;
                best = i;
            }
        }
        return best;
    }

    /** Swaps in one better armor piece, if there is one. */
    private void armor(Minecraft mc, LocalPlayer p) {
        var inv = p.getInventory();
        for (int a = 0; a < ARMOR.length; a++) {
            EquipmentSlot slot = ARMOR[a];
            ItemStack worn = p.getItemBySlot(slot);
            if (EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) continue;
            if (slot == EquipmentSlot.CHEST && worn.is(Items.ELYTRA)) continue; // flying gear stays on
            double current = value(worn, slot);
            int bestIdx = -1;
            double best = current;
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                Equippable e = s.get(DataComponents.EQUIPPABLE);
                if (e == null || e.slot() != slot) continue;
                double v = value(s, slot);
                if (v > best + 1e-6) {
                    best = v;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) continue;
            int menu = p.inventoryMenu.containerId;
            int from = bestIdx < 9 ? 36 + bestIdx : bestIdx;
            if (worn.isEmpty()) {
                mc.gameMode.handleInventoryMouseClick(menu, from, 0, ClickType.QUICK_MOVE, p);
            } else {
                // pick the new piece up, swap it with the worn one, put the old one where the new one was
                mc.gameMode.handleInventoryMouseClick(menu, from, 0, ClickType.PICKUP, p);
                mc.gameMode.handleInventoryMouseClick(menu, ARMOR_MENU_SLOT[a], 0, ClickType.PICKUP, p);
                mc.gameMode.handleInventoryMouseClick(menu, from, 0, ClickType.PICKUP, p);
            }
            chat.accept(Chat.info("Putting on better {}", slot.getName()));
            return;
        }
    }

    private static double value(ItemStack s, EquipmentSlot slot) {
        if (s.isEmpty()) return 0;
        ItemAttributeModifiers m = s.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return m.compute(Attributes.ARMOR, 0, slot) + 0.5 * m.compute(Attributes.ARMOR_TOUGHNESS, 0, slot);
    }
}
