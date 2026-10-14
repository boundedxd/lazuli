package dev.lazuli.mod.world;

import dev.lazuli.core.world.BlockKind;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Maps vanilla block states to the planner's coarse block kinds. */
public final class BlockClassifier {
    private static final Map<BlockState, BlockKind> CACHE = new ConcurrentHashMap<>();
    private static final Set<Block> DANGER = Set.of(
        Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH,
        Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE, Blocks.LAVA);

    private BlockClassifier() {}

    public static BlockKind classify(BlockState state) {
        return CACHE.computeIfAbsent(state, BlockClassifier::compute);
    }

    private static BlockKind compute(BlockState s) {
        if (DANGER.contains(s.getBlock()) || s.getFluidState().is(FluidTags.LAVA)) return BlockKind.DANGER;
        if (s.getFluidState().is(FluidTags.WATER)) return BlockKind.WATER;
        if (s.isAir()) return BlockKind.AIR;
        VoxelShape shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) return BlockKind.AIR;
        return Block.isShapeFullBlock(shape) ? BlockKind.SOLID : BlockKind.OBSTACLE;
    }
}
