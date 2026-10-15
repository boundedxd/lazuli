package dev.lazuli.mod.inventory;

import dev.lazuli.core.storage.ChestMemory;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Remembers what's in each chest, barrel and shulker box the player opens (by hand or by Lazuli): the block last
 * right-clicked, and the menu's own slots while it's open.
 */
public final class ChestWatcher {
    private final Supplier<Boolean> enabled;
    private final Function<Minecraft, ChestMemory> memory;
    private final Function<Minecraft, String> dimension;
    private BlockPos clicked;
    private int clickedTick;

    public ChestWatcher(Supplier<Boolean> enabled, Function<Minecraft, ChestMemory> memory, Function<Minecraft, String> dimension) {
        this.enabled = enabled;
        this.memory = memory;
        this.dimension = dimension;
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide()) {
                var be = level.getBlockEntity(hit.getBlockPos());
                if (be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity) {
                    clicked = hit.getBlockPos();
                    clickedTick = player.tickCount;
                }
            }
            return InteractionResult.PASS;
        });
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || clicked == null) return;
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == p.inventoryMenu) {
            // the menu closed (or never came: a locked chest, a cat on it)
            if (p.tickCount - clickedTick > 40) clicked = null;
            return;
        }
        if (!enabled.get() || p.tickCount % 5 != 0) return;
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) return;
        int size = menu.slots.size() - 36; // the player's inventory comes last
        Map<String, Integer> items = new HashMap<>();
        for (int i = 0; i < size; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            if (!s.isEmpty()) items.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        ChestMemory m = memory.apply(mc);
        String dim = dimension.apply(mc);
        // a double chest shows both halves: keep it under the clicked half only
        var state = mc.level.getBlockState(clicked);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = clicked.relative(ChestBlock.getConnectedDirection(state));
            m.forget(dim, other.getX(), other.getY(), other.getZ());
        }
        m.record(dim, clicked.getX(), clicked.getY(), clicked.getZ(), items);
    }
}
