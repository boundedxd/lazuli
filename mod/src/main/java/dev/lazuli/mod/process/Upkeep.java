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
import dev.lazuli.core.inventory.Click;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.equipment.Equippable;

import java.util.Set;
import java.util.function.Consumer;

/**
 * Looking after the player while Lazuli is busy: eats when hungry (food from anywhere in the inventory; rotten flesh and
 * the like only when starving) (movement pauses meanwhile) and
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

    /** Lets go of food being eaten (#kill). */
    public void reset(Minecraft mc) {
        if (!eating) return;
        eating = false;
        mc.options.keyUse.setDown(false);
        if (mc.player != null && mc.player.isUsingItem()) mc.gameMode.releaseUsingItem(mc.player);
        if (mc.player != null && prevSlot >= 0) mc.player.getInventory().setSelectedSlot(prevSlot);
        prevSlot = -1;
    }

    /** @param busy Lazuli is pathing or running a process */
    public void tick(Minecraft mc, boolean busy) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        // flying: the hand holds rockets, and the wings stay on
        if (p.isFallFlying()) return;
        eat(mc, p, busy);
        if (busy && !eating && settings.autoArmor.get() && mc.screen == null && p.tickCount % ARMOR_EVERY == 0) armor(mc, p);
        if (!eating && mc.screen == null && p.tickCount % ARMOR_EVERY == 10 && !p.isUsingItem()) offhand(p);
    }

    /** A totem into the offhand (autoTotem), else a shield (autoShield), from the inventory. */
    private void offhand(LocalPlayer p) {
        ItemStack off = p.getOffhandItem();
        if (off.is(Items.TOTEM_OF_UNDYING)) return;
        int slot = settings.autoTotem.get() ? find(p, Items.TOTEM_OF_UNDYING) : -1;
        // a shield only replaces nothing (never something the player put there)
        if (slot < 0 && settings.autoShield.get() && off.isEmpty()) slot = find(p, Items.SHIELD);
        if (slot < 0) return;
        var clicks = dev.lazuli.mod.inventory.ClickDriver.get();
        if (clicks.busy()) return;
        clicks.add(p.inventoryMenu.containerId, java.util.List.of(Click.swap(slot < 9 ? 36 + slot : slot, 40)));
        chat.accept(Chat.info("{} into the offhand", p.getInventory().getItem(slot).getHoverName().getString()));
    }

    private static int find(LocalPlayer p, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).is(item)) return i;
        return -1;
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
        if (!busy || !settings.autoEat.get() || food > (Guard.healing() ? 19 : settings.autoEatHunger.get()) || mc.screen != null || p.isUsingItem()) return;
        int slot = bestFood(p, food <= DESPERATE);
        if (slot < 0) {
            RestockProcess.urgent("food"); // a remembered chest may have some
            if (!warnedNoFood) chat.accept(Chat.warn("Hungry and no food to eat"));
            warnedNoFood = true;
            return;
        }
        warnedNoFood = false;
        if (slot >= 9) {
            // in the main inventory: into the hand's hotbar slot first
            var clicks = dev.lazuli.mod.inventory.ClickDriver.get();
            if (!clicks.busy()) clicks.add(p.inventoryMenu.containerId,
                java.util.List.of(dev.lazuli.core.inventory.Click.swap(slot, p.getInventory().getSelectedSlot())));
            return;
        }
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

    /** Hunger at or under which the foods usually avoided (rotten flesh, raw chicken...) are eaten too. */
    private static final int DESPERATE = 6;
    /** Never eaten on the way, however hungry: too good, or too harmful. */
    private static final Set<String> NEVER = Set.of("golden_apple", "enchanted_golden_apple", "pufferfish", "poisonous_potato",
        "chorus_fruit", "suspicious_stew");
    private boolean warnedNoFood;

    /** Inventory slot (hotbar first) of the most filling food worth eating, or -1. */
    private static int bestFood(LocalPlayer p, boolean desperate) {
        int best = -1;
        float bestValue = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            FoodProperties f = s.get(DataComponents.FOOD);
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (f == null || NEVER.contains(id) || !desperate && AVOID.contains(id)) continue;
            float v = f.nutrition() + f.saturation() - (AVOID.contains(id) ? 10 : 0) + (i < 9 ? 0.5f : 0);
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
            var clicks = dev.lazuli.mod.inventory.ClickDriver.get();
            if (clicks.busy()) return; // something else is using the inventory: next time
            int from = bestIdx < 9 ? 36 + bestIdx : bestIdx;
            // into the armor slot by shift-click; over a worn piece, pick the new one up, swap, put the old one back
            // (over a worn piece from the hotbar: one swap; from the main inventory: shift the old one out, the new one in)
            java.util.List<Click> plan;
            if (worn.isEmpty()) plan = java.util.List.of(Click.shift(from));
            else if (bestIdx < 9) plan = java.util.List.of(Click.swap(ARMOR_MENU_SLOT[a], bestIdx));
            else if (p.getInventory().getFreeSlot() >= 0) plan = java.util.List.of(Click.shift(ARMOR_MENU_SLOT[a]), Click.shift(from));
            else plan = java.util.List.of(Click.left(from), Click.left(ARMOR_MENU_SLOT[a]), Click.left(from));
            clicks.add(p.inventoryMenu.containerId, plan);
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
