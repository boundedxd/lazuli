package dev.lazuli.mod.world;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps vanilla block states to the planner's coarse block kinds and to {@link BlockPhysics}: real collision boxes,
 * friction, speed and jump factors, and behaviour flags. Both are derived from the block itself, so modded and
 * future blocks work without a table.
 */
public final class BlockClassifier {
    private static final Map<BlockState, BlockKind> CACHE = new ConcurrentHashMap<>();
    private static final Map<BlockState, Short> PHYSICS = new ConcurrentHashMap<>();
    /** A block state for each physics id, for the break-time formula (states sharing an id break alike). */
    private static final Map<Short, BlockState> STATES = new ConcurrentHashMap<>();
    private static final Set<Block> DANGER = Set.of(
        Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH,
        Blocks.COBWEB, Blocks.POWDER_SNOW, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE, Blocks.LAVA);
    /** Blocks that drag a player to a crawl (Block.makeStuckInBlock). */
    private static final Set<Block> STICKY = Set.of(Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW);

    private BlockClassifier() {}

    public static BlockKind classify(BlockState state) {
        return CACHE.computeIfAbsent(state, BlockClassifier::compute);
    }

    /** Palette id of the state's physics, or {@link BlockPhysics#DERIVED} when its kind already says it all. */
    public static short physicsId(BlockState state) {
        return PHYSICS.computeIfAbsent(state, BlockClassifier::computePhysics);
    }

    /** A block state with this physics id, or null for derived physics. */
    public static BlockState state(short physicsId) {
        return STATES.get(physicsId);
    }

    private static BlockKind compute(BlockState s) {
        if (DANGER.contains(s.getBlock()) || s.getFluidState().is(FluidTags.LAVA)) return BlockKind.DANGER;
        if (s.getFluidState().is(FluidTags.WATER)) return BlockKind.WATER;
        if (s.isAir()) return BlockKind.AIR;
        VoxelShape shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) return BlockKind.AIR;
        return Block.isShapeFullBlock(shape) ? BlockKind.SOLID : BlockKind.OBSTACLE;
    }

    private static short computePhysics(BlockState s) {
        if (s.isAir()) return BlockPhysics.DERIVED;
        Block b = s.getBlock();
        List<AABB> aabbs = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs();
        double[] boxes = new double[aabbs.size() * 6];
        for (int i = 0; i < aabbs.size(); i++) {
            AABB a = aabbs.get(i);
            boxes[i * 6] = a.minX;
            boxes[i * 6 + 1] = a.minY;
            boxes[i * 6 + 2] = a.minZ;
            boxes[i * 6 + 3] = a.maxX;
            boxes[i * 6 + 4] = a.maxY;
            boxes[i * 6 + 5] = a.maxZ;
        }
        int flags = 0;
        if (s.is(BlockTags.CLIMBABLE)) flags |= BlockPhysics.CLIMBABLE;
        if (s.getFluidState().is(FluidTags.WATER)) flags |= BlockPhysics.WATER;
        if (s.getFluidState().is(FluidTags.LAVA)) flags |= BlockPhysics.LAVA;
        if (b == Blocks.SLIME_BLOCK) flags |= BlockPhysics.SLIME;
        if (b instanceof BedBlock) flags |= BlockPhysics.BED;
        if (STICKY.contains(b)) flags |= BlockPhysics.STICKY;
        if (b instanceof FallingBlock) flags |= BlockPhysics.FALLING;
        if (s.canBeReplaced() && aabbs.isEmpty() && s.getFluidState().isEmpty()) flags |= BlockPhysics.REPLACEABLE;
        float hardness = s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        BlockPhysics p = BlockPhysics.intern(boxes, b.getFriction(), b.getSpeedFactor(), b.getJumpFactor(), flags,
            hardness, BuiltInRegistries.BLOCK.getId(b));
        STATES.putIfAbsent(p.id, s);
        BlockKind k = classify(s);
        return p == BlockPhysics.forKind(k) ? BlockPhysics.DERIVED : p.id;
    }
}
