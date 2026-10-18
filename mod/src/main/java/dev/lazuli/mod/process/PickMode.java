package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.target.Target;
import dev.lazuli.core.target.Targets;
import dev.lazuli.mod.inventory.TradeWatcher;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code #pick}: while it's on, shift+right-clicking a block or an entity saves it as a named target (the click doesn't
 * open the chest or the trade screen). Names are automatic (chest1, stand2, villager1...) unless {@code #pick <name>}
 * said what the next one is called.
 */
public final class PickMode {
    private final Supplier<Targets> store;
    private final Supplier<String> dimension;
    private final Consumer<String> chat;
    private boolean on;
    private String nextName;

    public PickMode(Supplier<Targets> store, Supplier<String> dimension, Consumer<String> chat) {
        this.store = store;
        this.dimension = dimension;
        this.chat = chat;
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!on || !level.isClientSide() || !player.isShiftKeyDown()) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) pickBlock(level.getBlockState(hit.getBlockPos()).getBlock(), hit.getBlockPos());
            return InteractionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!on || !level.isClientSide() || !player.isShiftKeyDown()) return InteractionResult.PASS;
            if (hand == net.minecraft.world.InteractionHand.MAIN_HAND) pickEntity(entity);
            return InteractionResult.FAIL;
        });
    }

    public boolean active() {
        return on;
    }

    /** {@code #pick}: toggles; {@code #pick <name>}: on, and the next pick gets that name. */
    public void command(String name) {
        if (name == null) {
            on = !on;
            nextName = null;
        } else {
            try {
                nextName = Targets.clean(name);
            } catch (IllegalArgumentException e) {
                chat.accept(Chat.err("{}", e.getMessage()));
                return;
            }
            on = true;
        }
        if (on) {
            chat.accept(Chat.ok("Pick mode on: shift+right-click a chest, villager, furnace, brewing stand... to save it{} ({}pick again to stop)",
                nextName == null ? "" : " as @" + nextName, "#"));
        } else {
            chat.accept(Chat.info("Pick mode off"));
        }
    }

    private void pickBlock(net.minecraft.world.level.block.Block block, BlockPos pos) {
        String id = BuiltInRegistries.BLOCK.getKey(block).toString();
        String path = id.substring(id.indexOf(':') + 1);
        Target.Kind kind = Target.Kind.BLOCK;
        String stem = null;
        if (path.matches(".*(chest|barrel|shulker_box|hopper|dispenser|dropper)")) {
            kind = Target.Kind.CONTAINER;
        } else {
            stem = stationStem(path);
            if (stem != null) kind = Target.Kind.STATION;
        }
        save(kind, pos.getX(), pos.getY(), pos.getZ(), null, id, "", stem);
    }

    /** What a crafting table, furnace... is called when it's named by itself (stand1, furnace2...). */
    static String stationStem(String path) {
        return switch (path) {
            case "crafting_table" -> "table";
            case "furnace" -> "furnace";
            case "blast_furnace" -> "blast";
            case "smoker" -> "smoker";
            case "brewing_stand" -> "stand";
            case "smithing_table" -> "smith";
            case "enchanting_table" -> "enchant";
            case "anvil", "chipped_anvil", "damaged_anvil" -> "anvil";
            case "grindstone", "stonecutter", "loom", "cartography_table" -> path.replace("_table", "");
            default -> null;
        };
    }

    private void pickEntity(net.minecraft.world.entity.Entity e) {
        BlockPos p = e.blockPosition();
        if (e instanceof AbstractVillager v) {
            save(Target.Kind.VILLAGER, p.getX(), p.getY(), p.getZ(), v.getUUID().toString(), null, TradeWatcher.profession(v), null);
        } else {
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            save(Target.Kind.ENTITY, p.getX(), p.getY(), p.getZ(), e.getUUID().toString(), null, type, null);
        }
    }

    private void save(Target.Kind kind, int x, int y, int z, String uuid, String block, String label, String stem) {
        Targets t = store.get();
        if (t == null) return;
        try {
            Target made = t.pick(nextName, kind, dimension.get(), x, y, z, uuid, block, label, stem);
            nextName = null;
            chat.accept(Chat.ok("Picked @{} ({} at {})", made.name(), made.kind().prefix(), made.coords()));
        } catch (IllegalArgumentException ex) {
            chat.accept(Chat.err("{}", ex.getMessage()));
        }
    }
}
