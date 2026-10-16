package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.ElytraPilot;
import dev.lazuli.core.control.InputPlanner;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.inventory.Click;
import dev.lazuli.core.physics.ElytraSim;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.mod.control.Hands;
import dev.lazuli.mod.control.InputDriver;
import dev.lazuli.mod.control.RotationDriver;
import dev.lazuli.mod.inventory.ClickDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.function.Consumer;

/**
 * #fly (and #goto far away, elytraGoto): puts on the elytra, takes off (jump, open the wings, a rocket), flies with
 * {@link ElytraPilot} high over the terrain ahead, glides down onto the spot and walks the last bit. Rockets are
 * fired from the hotbar; the chestplate goes back on after.
 */
public final class ElytraProcess implements Process {
    /** The inventory screen's chest armor slot. */
    private static final int CHEST_SLOT = 6;
    private static final int TAKEOFF_TICKS = 60, TAKEOFF_TRIES = 3, EQUIP_TICKS = 40;
    /** Land before the elytra has fewer uses left than this. */
    private static final int WORN = 12;

    private enum Phase { IDLE, EQUIP, TAKEOFF, FLY, WALK }

    public enum Outcome { NONE, DONE, FAILED, CANCELLED }

    private final Settings settings;
    private final Consumer<String> chat;
    private final ClickDriver clicks;
    private final Trip trip;
    private final InputDriver input = new InputDriver();
    private Phase phase = Phase.IDLE;
    private Outcome outcome = Outcome.NONE;
    private ElytraPilot pilot;
    private int tx, tz, phaseStart, tries, swappedSlot = -1;
    private Integer ty;
    private boolean cutShort, lastJump, equipSent;

    public ElytraProcess(PathingSession session, Settings settings, ClickDriver clicks, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.clicks = clicks;
        this.trip = new Trip(session);
    }

    @Override
    public boolean active() {
        return phase != Phase.IDLE;
    }

    public Outcome outcome() {
        return outcome;
    }

    @Override
    public String status() {
        if (phase == Phase.IDLE) return null;
        LocalPlayer p = Minecraft.getInstance().player;
        int d = p == null ? 0 : (int) Math.hypot(tx + 0.5 - p.getX(), tz + 0.5 - p.getZ());
        String doing = switch (phase) {
            case EQUIP -> "putting on the elytra";
            case TAKEOFF -> "taking off";
            case FLY -> "flying, " + d + " blocks to go";
            case WALK -> "walking the last bit";
            case IDLE -> "";
        };
        return "Fly to " + tx + (ty != null ? " " + ty : "") + " " + tz + "  (" + doing + ")";
    }

    /** Whether flying there is possible now (an elytra and rockets, not under a ceiling dimension). */
    public static String cannot(Minecraft mc, LocalPlayer p) {
        if (mc.level.dimensionType().hasCeiling()) return "Can't fly under a ceiling";
        ItemStack worn = p.getItemBySlot(EquipmentSlot.CHEST);
        boolean elytra = usable(worn) || Hands.has(p, ElytraProcess::usable);
        if (!elytra) return "No elytra (that isn't nearly broken)";
        if (!Hands.has(p, s -> s.is(Items.FIREWORK_ROCKET))) return "No firework rockets to take off with";
        return null;
    }

    private static boolean usable(ItemStack s) {
        return s.is(Items.ELYTRA) && s.getMaxDamage() - s.getDamageValue() > WORN;
    }

    /** Flies to x z (and onto y when given). */
    public void start(Minecraft mc, int x, Integer y, int z) {
        stop(false);
        outcome = Outcome.NONE;
        LocalPlayer p = mc.player;
        String why = cannot(mc, p);
        if (why != null) {
            fail(why);
            return;
        }
        tx = x;
        ty = y;
        tz = z;
        tries = 0;
        swappedSlot = -1;
        cutShort = equipSent = false;
        pilot = new ElytraPilot(settings.elytraHeight.get());
        chat.accept(Chat.ok("Flying to {}{} {}", x, y != null ? " " + y : "", z));
        enter(Phase.EQUIP, p);
    }

    @Override
    public void stop(boolean announce) {
        if (phase == Phase.IDLE) return;
        end(Outcome.CANCELLED, announce ? Chat.warn("Stopped") : null);
    }

    @Override
    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            stop(false);
            return;
        }
        switch (phase) {
            case EQUIP -> equip(p);
            case TAKEOFF -> takeoff(mc, p);
            case FLY -> fly(mc, p);
            case WALK -> walk(p);
            default -> {}
        }
    }

    private void equip(LocalPlayer p) {
        if (usable(p.getItemBySlot(EquipmentSlot.CHEST))) {
            enter(Phase.TAKEOFF, p);
            return;
        }
        if (clicks.busy()) return;
        if (p.tickCount - phaseStart > EQUIP_TICKS) {
            fail("Couldn't put the elytra on");
            return;
        }
        if (equipSent) return; // sent, waiting for it to show
        int e = -1;
        for (int i = 0; i < 36 && e < 0; i++) if (usable(p.getInventory().getItem(i))) e = i;
        if (e < 0) {
            fail("No elytra");
            return;
        }
        int slot = e < 9 ? 36 + e : e;
        // pick it up, swap it with what's worn, and put that where the elytra was
        boolean worn = !p.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
        clicks.add(p.inventoryMenu.containerId, worn ? List.of(Click.left(slot), Click.left(CHEST_SLOT), Click.left(slot))
            : List.of(Click.left(slot), Click.left(CHEST_SLOT)));
        swappedSlot = worn ? slot : -1;
        equipSent = true;
    }

    private void takeoff(Minecraft mc, LocalPlayer p) {
        if (p.isFallFlying()) {
            enter(Phase.FLY, p);
            return;
        }
        if (p.tickCount - phaseStart > TAKEOFF_TICKS) {
            if (++tries >= TAKEOFF_TRIES) {
                fail("Couldn't take off (no room above?)");
                return;
            }
            enter(Phase.TAKEOFF, p);
        }
        Hands.hold(p, clicks, s -> s.is(Items.FIREWORK_ROCKET));
        float yaw = yawTo(p), pitch = -30;
        // jump, then press jump again in the air: that opens the wings
        boolean jump = p.onGround() ? !lastJump : !lastJump && p.getDeltaMovement().y < 0.2;
        lastJump = jump;
        drive(mc, p, new Controls(false, false, jump, false, yaw, pitch, 0, false), yaw, pitch);
    }

    private void fly(Minecraft mc, LocalPlayer p) {
        int under = ground(mc, p.getBlockX(), p.getBlockZ());
        double over = under == ElytraPilot.UNKNOWN ? 99 : p.getY() - under;
        if (!p.isFallFlying()) {
            if (p.onGround() || p.isInWater()) {
                land(p);
                return;
            }
            if (over < 5) {
                // coming down onto something (a tree top...): let it land; jumping here would hop and flap on the ground
                input.release();
                lastJump = false;
                return;
            }
            // knocked out of it high up: open the wings again
            boolean jump = !lastJump;
            lastJump = jump;
            drive(mc, p, new Controls(false, false, jump, false, RotationDriver.yaw(p), RotationDriver.pitch(p), 0, false),
                RotationDriver.yaw(p), RotationDriver.pitch(p));
            return;
        }
        lastJump = false;
        ItemStack wings = p.getItemBySlot(EquipmentSlot.CHEST);
        double gx = tx + 0.5, gz = tz + 0.5;
        if (!cutShort && wings.getMaxDamage() - wings.getDamageValue() <= WORN) {
            // wearing out: come down a little way ahead and walk the rest
            cutShort = true;
            chat.accept(Chat.warn("Elytra nearly broken; landing"));
        }
        if (cutShort) {
            double yaw = Math.toRadians(RotationDriver.yaw(p));
            gx = p.getX() - Math.sin(yaw) * 20;
            gz = p.getZ() + Math.cos(yaw) * 20;
        }
        var v = p.getDeltaMovement();
        ElytraSim.State s = new ElytraSim.State(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z);
        // onto the ground at the spot itself, not the tree tops over it (asked for a y: that one)
        int landY = ty != null && !cutShort ? ty : floor(mc, (int) Math.floor(gx), (int) Math.floor(gz));
        if (landY == ElytraPilot.UNKNOWN) landY = mc.level.getSeaLevel();
        boolean rockets = Hands.hold(p, clicks, r -> r.is(Items.FIREWORK_ROCKET));
        ElytraPilot.Command c = pilot.tick(s, gx, gz, landY, (x, z) -> ground(mc, x, z), rockets);
        float maxTurn = Math.max(settings.rotationSpeed.get().floatValue(), 10f);
        float yaw = InputPlanner.approach(RotationDriver.yaw(p), c.yaw(), maxTurn);
        float pitch = RotationDriver.pitch(p) + Math.max(-maxTurn, Math.min(maxTurn, c.pitch() - RotationDriver.pitch(p)));
        drive(mc, p, new Controls(false, false, false, false, c.yaw(), c.pitch(), 0, false), yaw, pitch);
        if (c.fire()) mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    /** Down: the chestplate back on, then the last bit on foot. */
    private void land(LocalPlayer p) {
        input.release();
        RotationDriver.end();
        if (swappedSlot >= 0 && settings.elytraSwapBack.get()) {
            ItemStack back = p.inventoryMenu.slots.get(swappedSlot).getItem();
            if (!back.isEmpty() && !back.is(Items.ELYTRA)) {
                clicks.add(p.inventoryMenu.containerId, List.of(Click.left(swappedSlot), Click.left(CHEST_SLOT), Click.left(swappedSlot)));
            }
        }
        swappedSlot = -1;
        BlockPos f = p.blockPosition();
        double left = Math.hypot(tx + 0.5 - p.getX(), tz + 0.5 - p.getZ());
        if (left < 1.5 && (ty == null || Math.abs(ty - f.getY()) < 1)) {
            end(Outcome.DONE, Chat.ok("Flew to {} {}", tx, tz));
            return;
        }
        Goal g = ty != null ? new GoalBlock(tx, ty, tz) : new GoalXZ(tx, tz);
        trip.go(g, p, true);
        enter(Phase.WALK, p);
    }

    private void walk(LocalPlayer p) {
        if (trip.running()) return;
        PathingSession.State how = trip.ended();
        if (how == PathingSession.State.ARRIVED) end(Outcome.DONE, Chat.ok("Flew to {} {}", tx, tz));
        else end(Outcome.FAILED, Chat.err("Landed, but couldn't walk the rest of the way"));
    }

    /** Like {@link #ground} but under any leaves: where to land. */
    private static int floor(Minecraft mc, int x, int z) {
        if (!mc.level.hasChunk(x >> 4, z >> 4)) return ElytraPilot.UNKNOWN;
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    /** The standing y on top of the ground at x z, or UNKNOWN where the chunk isn't loaded. */
    private static int ground(Minecraft mc, int x, int z) {
        if (!mc.level.hasChunk(x >> 4, z >> 4)) return ElytraPilot.UNKNOWN;
        return mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    private float yawTo(LocalPlayer p) {
        return (float) (Math.toDegrees(Math.atan2(tz + 0.5 - p.getZ(), tx + 0.5 - p.getX())) - 90.0);
    }

    private void drive(Minecraft mc, LocalPlayer p, Controls c, float yaw, float pitch) {
        // free look works: the wings steer by the rotation the player ticks with, rockets push along it (FireworkRocketMixin)
        input.apply(mc, c, yaw, pitch, settings.freeLook.get());
    }

    private void enter(Phase next, LocalPlayer p) {
        phase = next;
        phaseStart = p.tickCount;
        lastJump = false;
    }

    private void fail(String msg) {
        end(Outcome.FAILED, Chat.err(msg));
    }

    private void end(Outcome how, String msg) {
        phase = Phase.IDLE;
        outcome = how;
        trip.cancel();
        input.release();
        if (msg != null) chat.accept(msg);
    }
}
