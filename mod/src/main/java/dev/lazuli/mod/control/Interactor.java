package dev.lazuli.mod.control;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.control.Builder;
import dev.lazuli.core.control.Controls;
import dev.lazuli.core.control.PlayerState;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Consumer;

/**
 * The game's side of {@link Builder}: mines through {@link dev.lazuli.mod.mixin.MinecraftMixin} (read in place of
 * the attack key) and places with the game mode's own use-item call.
 */
public final class Interactor implements Builder.Hands {
    private static final int PLACE_COOLDOWN = 4;

    /** The block to mine next tick and the face hit, read by MinecraftMixin in place of the attack key. */
    private static volatile BlockPos mining;
    private static volatile Direction miningFace = Direction.UP;

    private final Settings settings;
    private final PathingSession session;
    private final Consumer<String> chat;
    private final Builder builder;
    private LocalPlayer player;
    private Minecraft mc;
    private int placeCooldown;
    private int rebuildCooldown;

    Interactor(BlockView world, Settings settings, PathingSession session, Consumer<String> chat) {
        this.settings = settings;
        this.session = session;
        this.chat = chat;
        this.builder = new Builder(world, this);
    }

    /** Breaks {@code pos} next tick (for processes that aim on their own, like #mine). */
    public static void mineAt(BlockPos pos, Direction face) {
        miningFace = face;
        mining = pos;
    }

    public static BlockPos mining() {
        return mining;
    }

    public static Direction miningFace() {
        return miningFace;
    }

    void reset() {
        mining = null;
        builder.reset();
    }

    /** Controls for this tick while breaking or placing, or null when the path can just be walked. */
    Controls tick(Minecraft mc, PlayerState state, List<Long> path, int idx, long block) {
        this.mc = mc;
        this.player = mc.player;
        mining = null;
        if (placeCooldown > 0) placeCooldown--;
        if (rebuildCooldown > 0) rebuildCooldown--;
        builder.setEagle(settings.eagleBridge.get());
        return builder.tick(state, path, idx, block, settings.allowBreak.get(), settings.allowPlace.get());
    }

    @Override
    public boolean selectThrowaway() {
        int slot = Tools.throwaway(player, settings);
        if (slot >= 0) player.getInventory().setSelectedSlot(slot);
        return slot >= 0;
    }

    @Override
    public void place(long pos, long against) {
        if (placeCooldown > 0) return;
        BlockPos ap = new BlockPos(Pos.x(against), Pos.y(against), Pos.z(against));
        // right-clicking a chest, door or crafting table would open it instead, unless sneaking
        if (!player.isShiftKeyDown() && usable(ap)) return;
        Direction face = Direction.getApproximateNearest(Pos.x(pos) - Pos.x(against), Pos.y(pos) - Pos.y(against),
            Pos.z(pos) - Pos.z(against));
        // only what the crosshair is really on, as the game itself sees it
        BlockHitResult hit = crosshair();
        if (hit == null || !hit.getBlockPos().equals(ap) || hit.getDirection() != face) return;
        placeCooldown = PLACE_COOLDOWN;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit).consumesAction()) {
            player.swing(InteractionHand.MAIN_HAND);
            placeCooldown = 1; // it worked: the next block can go right away
        }
    }

    /** Blocks that do something when right-clicked. */
    private boolean usable(BlockPos bp) {
        var state = mc.level.getBlockState(bp);
        return state.getBlock() instanceof EntityBlock || state.getMenuProvider(mc.level, bp) != null
            || state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS) || state.is(BlockTags.FENCE_GATES)
            || state.is(BlockTags.BUTTONS) || state.is(BlockTags.BEDS) || state.getBlock() instanceof LeverBlock;
    }

    @Override
    public void mine(long pos) {
        BlockPos bp = new BlockPos(Pos.x(pos), Pos.y(pos), Pos.z(pos));
        BlockHitResult hit = crosshair();
        if (hit == null || !hit.getBlockPos().equals(bp)) return;
        Tools.equipBestTool(mc, player, player.level().getBlockState(bp), settings);
        mining = bp;
        miningFace = hit.getDirection();
    }

    /** The block the player's (server-side) rotation points at within reach, or null. */
    private BlockHitResult crosshair() {
        Vec3 eye = player.getEyePosition();
        Vec3 dir = Vec3.directionFromRotation(RotationDriver.pitch(player), RotationDriver.yaw(player));
        HitResult h = mc.level.clip(new ClipContext(eye, eye.add(dir.scale(player.blockInteractionRange())),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
    }

    @Override
    public double eyeHeight() {
        return player.getEyeHeight();
    }

    @Override
    public void stuck(long u, long v) {
        session.edgeFailed(u, v);
    }

    @Override
    public boolean breakable(long pos) {
        BlockPos bp = new BlockPos(Pos.x(pos), Pos.y(pos), Pos.z(pos));
        var state = mc.level.getBlockState(bp);
        return state.getFluidState().isEmpty() && state.getDestroySpeed(mc.level, bp) >= 0;
    }

    @Override
    public void outOfBlocks() {
        if (rebuildCooldown == 0) {
            rebuildCooldown = 40; // the new path takes a moment
            chat.accept(Chat.warn("Out of throwaway blocks; re-planning without placing"));
            session.rebuild();
        }
    }
}
