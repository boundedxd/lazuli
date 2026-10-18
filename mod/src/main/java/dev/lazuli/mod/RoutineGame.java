package dev.lazuli.mod;

import dev.lazuli.core.storage.ChestMemory;
import dev.lazuli.core.target.Target;
import dev.lazuli.core.target.Targets;
import dev.lazuli.core.task.Cond;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
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
    private final Supplier<Targets> targets;

    RoutineGame(Supplier<ChestMemory> chests, Supplier<String> dimension, BooleanSupplier busy, Supplier<Targets> targets) {
        this.chests = chests;
        this.dimension = dimension;
        this.busy = busy;
        this.targets = targets;
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

    @Override
    public int anywhere(String item) {
        return items(item) + chestItems(item);
    }

    private static double percentLeft(ItemStack s) {
        if (!s.isDamageableItem() || s.getMaxDamage() <= 0) return 100;
        return 100.0 * (s.getMaxDamage() - s.getDamageValue()) / s.getMaxDamage();
    }

    @Override
    public double armorDurability() {
        var p = Minecraft.getInstance().player;
        if (p == null) return 100;
        double worst = 100;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack s = p.getItemBySlot(slot);
            if (!s.isEmpty()) worst = Math.min(worst, percentLeft(s));
        }
        return worst;
    }

    @Override
    public double toolDurability(String item) {
        var p = Minecraft.getInstance().player;
        if (p == null) return 0;
        String want = path(item);
        double worst = -1;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(want)) continue;
            double left = percentLeft(s);
            if (worst < 0 || left < worst) worst = left;
        }
        return worst < 0 ? 0 : worst;
    }

    @Override
    public int xpLevel() {
        var p = Minecraft.getInstance().player;
        return p == null ? 0 : p.experienceLevel;
    }

    @Override
    public int timeOfDay() {
        var mc = Minecraft.getInstance();
        return mc.level == null ? 0 : (int) (mc.level.getDayTime() % 24000);
    }

    @Override
    public boolean raining() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.isRaining();
    }

    @Override
    public boolean thundering() {
        var mc = Minecraft.getInstance();
        return mc.level != null && mc.level.isThundering();
    }

    @Override
    public int y() {
        var p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getBlockY();
    }

    @Override
    public double distanceTo(String where) {
        var p = Minecraft.getInstance().player;
        if (p == null) return 1e9;
        if (where.startsWith("@")) {
            Targets t = targets.get();
            if (t == null) return 1e9;
            Target found = t.get(where.substring(1));
            if (found == null || !found.dim().equals(dimension.get())) return 1e9;
            return Math.sqrt(found.distSq(p.getBlockX(), p.getBlockY(), p.getBlockZ()));
        }
        String[] c = where.split(",");
        if (c.length != 3) return 1e9;
        try {
            double dx = Double.parseDouble(c[0]) - p.getX(), dy = Double.parseDouble(c[1]) - p.getY(), dz = Double.parseDouble(c[2]) - p.getZ();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        } catch (NumberFormatException e) {
            return 1e9;
        }
    }

    @Override
    public int playersNearby() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;
        int n = 0;
        for (var other : mc.level.players()) if (other != mc.player && other.distanceToSqr(mc.player) <= 32 * 32) n++;
        return n;
    }

    @Override
    public int hostilesNearby() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return 0;
        return mc.level.getEntitiesOfClass(LivingEntity.class, mc.player.getBoundingBox().inflate(16), e -> e instanceof Enemy && e.isAlive()).size();
    }

    @Override
    public boolean holding(String item) {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        ItemStack s = p.getMainHandItem();
        return !s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(path(item));
    }

    @Override
    public boolean hasEffect(String name) {
        var p = Minecraft.getInstance().player;
        if (p == null) return false;
        String want = path(name);
        for (var e : p.getActiveEffects()) {
            if (BuiltInRegistries.MOB_EFFECT.getKey(e.getEffect().value()).getPath().equals(want)) return true;
        }
        return false;
    }
}
