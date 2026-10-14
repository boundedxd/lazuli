package dev.lazuli.mod.control;

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
import net.minecraft.world.phys.BlockHitResult;
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
        Vec3 hit = Vec3.atCenterOf(ap).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        placeCooldown = PLACE_COOLDOWN;
        if (mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, ap, false)).consumesAction()) {
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
        player.getInventory().setSelectedSlot(Tools.bestTool(player, player.level().getBlockState(bp)));
        mining = bp;
        miningFace = Direction.getApproximateNearest(player.getEyePosition().subtract(Vec3.atCenterOf(bp)));
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
            chat.accept("Out of throwaway blocks; re-planning without placing");
            session.rebuild();
        }
    }
}
