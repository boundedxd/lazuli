package dev.lazuli.core.testutil;

import dev.lazuli.core.world.BlockPhysics;

/** Vanilla block physics for tests. */
public final class Shapes {
    private Shapes() {}

    public static final BlockPhysics BOTTOM_SLAB = BlockPhysics.intern(new double[]{0, 0, 0, 1, 0.5, 1}, 0.6f, 1, 1, 0);
    public static final BlockPhysics CARPET = BlockPhysics.intern(new double[]{0, 0, 0, 1, 0.0625, 1}, 0.6f, 1, 1, 0);
    /** Stairs ascending toward +x: bottom half plus the back (+x) half on top. */
    public static final BlockPhysics STAIRS_EAST = BlockPhysics.intern(
        new double[]{0, 0, 0, 1, 0.5, 1, 0.5, 0.5, 0, 1, 1, 1}, 0.6f, 1, 1, 0);
    /** A fence segment running along x through the middle of the block. */
    public static final BlockPhysics FENCE_X = BlockPhysics.intern(new double[]{0, 0, 0.375, 1, 1.5, 0.625}, 0.6f, 1, 1, 0);
    public static final BlockPhysics FENCE_Z = BlockPhysics.intern(new double[]{0.375, 0, 0, 0.625, 1.5, 1}, 0.6f, 1, 1, 0);
    public static final BlockPhysics ICE = BlockPhysics.intern(new double[]{0, 0, 0, 1, 1, 1}, 0.98f, 1, 1, 0);
    public static final BlockPhysics SOUL_SAND = BlockPhysics.intern(new double[]{0, 0, 0, 1, 0.875, 1}, 0.6f, 0.4f, 1, 0);
    public static final BlockPhysics HONEY = BlockPhysics.intern(
        new double[]{0.0625, 0, 0.0625, 0.9375, 0.9375, 0.9375}, 0.6f, 0.4f, 0.5f, 0);
    public static final BlockPhysics SLIME = BlockPhysics.intern(new double[]{0, 0, 0, 1, 1, 1}, 0.8f, 1, 1, BlockPhysics.SLIME);
    /** Ladder on the -x face of its cell (attached to a wall at x-1). */
    public static final BlockPhysics LADDER = BlockPhysics.intern(
        new double[]{0, 0, 0, 0.1875, 1, 1}, 0.6f, 1, 1, BlockPhysics.CLIMBABLE);
    public static final BlockPhysics VINE = BlockPhysics.intern(new double[0], 0.6f, 1, 1, BlockPhysics.CLIMBABLE);
}
