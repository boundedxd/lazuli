package dev.lazuli.mod.control;

import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.PathFollower;
import dev.lazuli.core.move.Digging;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * Carries out the breaking and placing an edge needs (see {@link Digging#actions}) while the player stands at its
 * start: mines with the best hotbar tool, bridges by placing against the side of the floor, pillars by jumping and
 * placing underneath. Movement stops (sneaking, so nothing walks off an edge) until the edge can be walked.
 */
public final class Interactor {
    private static final long NONE = Long.MIN_VALUE;
    private static final double CENTER = 0.15;
    /** Horizontal speed (blocks/tick) below which a pillar jump stays over its block. */
    private static final double STILL_SPEED = 0.02;
    private static final int PLACE_COOLDOWN = 4;
    private static final int PILLAR_TIMEOUT = 40;

    /** The block to mine next tick and the face hit, read by MinecraftMixin in place of the attack key. */
    private static volatile BlockPos mining;
    private static volatile Direction miningFace = Direction.UP;

    private final BlockView world;
    private final Settings settings;
    private final PathingSession session;
    private final Consumer<String> chat;
    private long pillar = NONE;
    private int pillarTicks;
    private boolean pillarLeftGround;
    private int placeCooldown;
    private int rebuildCooldown;

    Interactor(BlockView world, Settings settings, PathingSession session, Consumer<String> chat) {
        this.world = world;
        this.settings = settings;
        this.session = session;
        this.chat = chat;
    }

    public static BlockPos mining() {
        return mining;
    }

    public static Direction miningFace() {
        return miningFace;
    }

    void reset() {
        mining = null;
        pillar = NONE;
    }

    /** Controls for this tick while breaking or placing, or null when the next edge can just be walked. */
    Controls tick(Minecraft mc, List<Long> path, int idx, long block) {
        mining = null;
        if (placeCooldown > 0) placeCooldown--;
        if (rebuildCooldown > 0) rebuildCooldown--;
        LocalPlayer p = mc.player;
        if (pillar != NONE) return pillarTick(mc, p);
        if (!p.onGround() || idx < 0 || idx + 1 >= path.size() || path.get(idx) != block) return null;
        long u = path.get(idx), v = path.get(idx + 1);
        List<Digging.Action> acts = new MoveGraph(world, settings.maxFallHeight.get(), true, 1,
            settings.allowParkour.get()).actions(u, v);
        if (acts.isEmpty()) return null;
        Digging.Action a = acts.get(0);
        // only what the settings allow and the game can do; otherwise leave it to the planner's next repair
        if (a.place() ? !settings.allowPlace.get() : !settings.allowBreak.get() || !breakable(mc, a.pos())) return null;
        boolean vertical = Pos.x(u) == Pos.x(v) && Pos.z(u) == Pos.z(v);
        double cx = Pos.x(u) + 0.5, cz = Pos.z(u) + 0.5;
        if (vertical && Math.hypot(p.getX() - cx, p.getZ() - cz) > CENTER) {
            // mining down or pillaring: stand in the middle of the block first
            float yaw = PathFollower.yawTo(p.getX(), p.getZ(), cx, cz);
            return new Controls(true, false, false, false, yaw, 60f, 0, true);
        }
        if (!a.place()) return mine(p, a.pos());
        if (vertical) {
            // a jump keeps its run-in speed (air drag only takes 9% a tick), so stop first or it lands beside the pillar
            Vec3 dv = p.getDeltaMovement();
            if (Math.hypot(dv.x, dv.z) > STILL_SPEED) return still(p, null);
            if (selectThrowaway(p) < 0) return outOfBlocks(p);
            pillar = a.pos();
            pillarTicks = 0;
            pillarLeftGround = false;
            return new Controls(false, false, true, false, p.getYRot(), 90f, 0, true);
        }
        return place(mc, p, a.pos(), a.against());
    }

    private Controls mine(LocalPlayer p, long pos) {
        BlockPos bp = new BlockPos(Pos.x(pos), Pos.y(pos), Pos.z(pos));
        p.getInventory().setSelectedSlot(Tools.bestTool(p, p.level().getBlockState(bp)));
        Vec3 eye = p.getEyePosition();
        Vec3 c = Vec3.atCenterOf(bp);
        mining = bp;
        miningFace = Direction.getApproximateNearest(eye.subtract(c));
        return still(p, c);
    }

    private Controls place(Minecraft mc, LocalPlayer p, long pos, long against) {
        if (selectThrowaway(p) < 0) return outOfBlocks(p);
        BlockPos ap = new BlockPos(Pos.x(against), Pos.y(against), Pos.z(against));
        Direction face = Direction.getApproximateNearest(Pos.x(pos) - Pos.x(against), Pos.y(pos) - Pos.y(against),
            Pos.z(pos) - Pos.z(against));
        Vec3 hit = Vec3.atCenterOf(ap).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        // sneaking, so placing against a chest, door or crafting table doesn't open it
        if (placeCooldown == 0 && p.isShiftKeyDown()) {
            placeCooldown = PLACE_COOLDOWN;
            if (mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, ap, false)).consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
            }
        }
        return still(p, hit);
    }

    /** Jumping out of the pillar's cell: place under the feet once they clear its top, until landed on it. */
    private Controls pillarTick(Minecraft mc, LocalPlayer p) {
        int x = Pos.x(pillar), y = Pos.y(pillar), z = Pos.z(pillar);
        boolean placed = !Digging.open(world, x, y, z);
        pillarTicks++;
        if (!p.onGround()) pillarLeftGround = true;
        if (p.onGround() && (placed || pillarLeftGround) || pillarTicks > PILLAR_TIMEOUT) {
            pillar = NONE; // landed on it, or came back down without it: re-evaluate from the path
            return null;
        }
        if (!placed && !p.onGround() && p.getY() > y + 1 + 0.02) place(mc, p, pillar, Pos.pack(x, y - 1, z));
        return new Controls(false, false, p.onGround() && !pillarLeftGround, false, p.getYRot(), 90f, 0, true);
    }

    private static boolean breakable(Minecraft mc, long pos) {
        BlockPos bp = new BlockPos(Pos.x(pos), Pos.y(pos), Pos.z(pos));
        var state = mc.level.getBlockState(bp);
        return state.getFluidState().isEmpty() && state.getDestroySpeed(mc.level, bp) >= 0;
    }

    private int selectThrowaway(LocalPlayer p) {
        int slot = Tools.throwaway(p, settings);
        if (slot >= 0) p.getInventory().setSelectedSlot(slot);
        return slot;
    }

    private Controls outOfBlocks(LocalPlayer p) {
        if (rebuildCooldown == 0) {
            rebuildCooldown = 40; // the new path takes a moment
            chat.accept("Out of throwaway blocks; re-planning without placing");
            session.rebuild();
        }
        return still(p, null);
    }

    /** No movement, sneaking, looking at {@code at}. */
    private static Controls still(LocalPlayer p, Vec3 at) {
        float yaw = p.getYRot(), pitch = p.getXRot();
        if (at != null) {
            Vec3 d = at.subtract(p.getEyePosition());
            yaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
            pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
        }
        return new Controls(false, false, false, false, yaw, pitch, 0, true);
    }
}
