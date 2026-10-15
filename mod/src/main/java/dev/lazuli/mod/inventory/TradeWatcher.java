package dev.lazuli.mod.inventory;

import dev.lazuli.core.inventory.ItemCount;
import dev.lazuli.core.trade.Offer;
import dev.lazuli.core.trade.TradeMemory;
import dev.lazuli.core.trade.Trader;
import dev.lazuli.core.trade.Want;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Remembers every villager's trades: the villager last right-clicked (by hand or by Lazuli) and the offers its
 * trading screen shows once they arrive.
 */
public final class TradeWatcher {
    private final Supplier<Boolean> enabled;
    private final Function<Minecraft, TradeMemory> memory;
    private final Function<Minecraft, String> dimension;
    private AbstractVillager clicked;
    private MerchantOffers recorded;

    public TradeWatcher(Supplier<Boolean> enabled, Function<Minecraft, TradeMemory> memory, Function<Minecraft, String> dimension) {
        this.enabled = enabled;
        this.memory = memory;
        this.dimension = dimension;
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && entity instanceof AbstractVillager v) {
                clicked = v;
                recorded = null;
            }
            return InteractionResult.PASS;
        });
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || clicked == null || !enabled.get()) return;
        if (!(p.containerMenu instanceof MerchantMenu menu)) return;
        MerchantOffers offers = menu.getOffers();
        if (offers.isEmpty() || offers == recorded && p.tickCount % 10 != 0) return;
        recorded = offers;
        memory.apply(mc).record(trader(clicked, offers, dimension.apply(mc)));
    }

    /** The villager whose screen is open (the one last clicked), or null. */
    public AbstractVillager trading() {
        return clicked;
    }

    static Trader trader(AbstractVillager v, MerchantOffers offers, String dim) {
        List<Offer> out = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) out.add(offer(i, offers.get(i)));
        return new Trader(v.getUUID().toString(), profession(v), dim, v.getBlockX(), v.getBlockY(), v.getBlockZ(), out);
    }

    /** librarian, fletcher... ("wandering_trader" for those, "none" for jobless ones). */
    public static String profession(AbstractVillager v) {
        if (v instanceof Villager vil) {
            return vil.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath()).orElse("none");
        }
        return "wandering_trader";
    }

    /** Can be traded with at all: grown up, with a job (nitwits and the jobless have no trades). */
    public static boolean trades(AbstractVillager v) {
        if (v.isBaby()) return false;
        String p = profession(v);
        return !p.equals("none") && !p.equals("nitwit");
    }

    public static Offer offer(int index, MerchantOffer o) {
        ItemStack a = o.getCostA(), b = o.getCostB(), r = o.getResult();
        String ench = null;
        int level = 0;
        ItemEnchantments e = r.get(DataComponents.STORED_ENCHANTMENTS);
        if (e != null && !e.isEmpty()) {
            var entry = e.entrySet().iterator().next();
            ench = entry.getKey().unwrapKey().map(k -> k.identifier().toString()).orElse(null);
            level = entry.getIntValue();
        }
        return new Offer(index, new ItemCount(id(a), a.getCount()), b.isEmpty() ? null : new ItemCount(id(b), b.getCount()),
            new ItemCount(id(r), r.getCount()), ench, level, o.isOutOfStock() ? 0 : Math.max(0, o.getMaxUses() - o.getUses()));
    }

    /** The stack is what's wanted (an enchanted book: has the enchantment, at least that high). */
    public static boolean matches(ItemStack s, Want w) {
        if (s.isEmpty() || !id(s).equals(w.item())) return false;
        if (w.enchant() == null) return true;
        ItemEnchantments e = s.get(DataComponents.STORED_ENCHANTMENTS);
        if (e == null) return false;
        for (var entry : e.entrySet()) {
            String k = entry.getKey().unwrapKey().map(x -> x.identifier().toString()).orElse("");
            if (k.equals(w.enchant()) && entry.getIntValue() >= w.level()) return true;
        }
        return false;
    }

    /** How many of a want the inventory holds. */
    public static int count(LocalPlayer p, Want w) {
        int n = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (matches(s, w)) n += s.getCount();
        }
        return n;
    }

    /** An enchantment the game knows (minecraft:sharpness...). */
    public static boolean isEnchantment(Minecraft mc, String id) {
        Identifier i = Identifier.tryParse(id);
        if (i == null || mc.getConnection() == null) return false;
        return mc.getConnection().registryAccess().lookup(Registries.ENCHANTMENT).map(r -> r.containsKey(i)).orElse(false);
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }
}
