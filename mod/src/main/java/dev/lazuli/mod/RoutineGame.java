package dev.lazuli.mod;

import dev.lazuli.core.storage.ChestMemory;
import dev.lazuli.core.task.Cond;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** What routine conditions see: the player's inventory and state, remembered chests, the dimension. */
final class RoutineGame implements Cond.World {
    private final Supplier<ChestMemory> chests;
    private final Supplier<String> dimension;
    private final BooleanSupplier busy;

    RoutineGame(Supplier<ChestMemory> chests, Supplier<String> dimension, BooleanSupplier busy) {
        this.chests = chests;
        this.dimension = dimension;
        this.busy = busy;
    }

    /** "minecraft:Iron_Ingot" and "iron_ingot" are the same. */
    private static String path(String item) {
        String s = item.toLowerCase(Locale.ROOT);
        int c = s.indexOf(':');
        if (c >= 0) s = s.substring(c + 1);
        int h = s.indexOf('#');
        if (h >= 0) s = s.substring(0, h);
        int a = s.indexOf('@');
        return a >= 0 ? s.substring(0, a) : s;
    }

    @Override
    public int items(String item) {
        var p = Minecraft.getInstance().player;
        if (p == null) return 0;
        String want = path(item);
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(want)) n += s.getCount();
        }
        return n;
    }

    @Override
    public int chestItems(String item) {
        ChestMemory m = chests.get();
        if (m == null) return 0;
        String want = path(item);
        int n = 0;
        for (var e : m.totals(dimension.get()).entrySet()) if (path(e.getKey()).equals(want)) n += e.getValue();
        return n;
    }

    @Override
    public int freeSlots() {
        var p = Minecraft.getInstance().player;
        if (p == null) return 0;
        int n = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getItem(i).isEmpty()) n++;
        return n;
    }

    @Override
    public double health() {
        var p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getHealth();
    }

    @Override
    public int hunger() {
        var p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getFoodData().getFoodLevel();
    }

    @Override
    public boolean night() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !dimension.get().equals("overworld")) return false;
        long t = mc.level.getDayTime() % 24000;
        return t >= 13000 && t < 23000;
    }

    @Override
    public String dimension() {
        return dimension.get();
    }

    @Override
    public boolean busy() {
        return busy.getAsBoolean();
    }
}
