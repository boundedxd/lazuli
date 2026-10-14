package dev.lazuli.core.course;

import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;

import java.util.ArrayList;
import java.util.List;

/**
 * A test course in local coordinates: the player starts standing at {@link #start} (feet), the goal is standing at
 * {@link #goal}. Courses float in open air with their own ground, so a gap really is a drop into the void. The game
 * builds them with {@code #course build}; the tests drive the same blocks in the sim.
 *
 * @param lowest the lowest feet height a correct run ever reaches (a run going below it took a wrong way round,
 *               e.g. walked down and towered back up instead of bridging)
 */
public record Course(String name, String what, List<Fill> fills, int[] start, int[] goal, int lowest,
                     boolean needsBreak, boolean needsPlace) {

    /** Block types a course is made of. */
    public enum Material {
        AIR, STONE, DIRT, GRAVEL, GLASS, ICE, SLAB, WATER, LAVA,
        /** The block the goal stands on (gold in the game). */
        GOAL;

        public BlockKind kind() {
            return switch (this) {
                case AIR -> BlockKind.AIR;
                case WATER -> BlockKind.WATER;
                case LAVA -> BlockKind.DANGER;
                case SLAB -> BlockKind.OBSTACLE;
                default -> BlockKind.SOLID;
            };
        }

        /** Close to the game's physics for the block the mod places. */
        public BlockPhysics physics() {
            double[] full = {0, 0, 0, 1, 1, 1};
            return switch (this) {
                case AIR -> BlockPhysics.EMPTY;
                case STONE -> BlockPhysics.intern(full, 0.6f, 1, 1, 0, 1.5f, 1);
                case DIRT -> BlockPhysics.intern(full, 0.6f, 1, 1, 0, 0.5f, 2);
                case GRAVEL -> BlockPhysics.intern(full, 0.6f, 1, 1, BlockPhysics.FALLING, 0.6f, 3);
                case GLASS -> BlockPhysics.intern(full, 0.6f, 1, 1, 0, 0.3f, 4);
                case ICE -> BlockPhysics.intern(full, 0.98f, 1, 1, 0, 0.5f, 5);
                case SLAB -> BlockPhysics.intern(new double[]{0, 0, 0, 1, 0.5, 1}, 0.6f, 1, 1, 0, 2f, 6);
                case WATER -> BlockPhysics.WATER_BLOCK;
                case LAVA -> BlockPhysics.intern(new double[0], 0.6f, 1, 1, BlockPhysics.LAVA, -1, 7);
                case GOAL -> BlockPhysics.intern(full, 0.6f, 1, 1, 0, 3f, 8);
            };
        }
    }

    /** Every block in the box becomes {@code m}; later fills overwrite earlier ones. */
    public record Fill(int x0, int y0, int z0, int x1, int y1, int z1, Material m) {}

    /** Collects fills for one course. Local y=0 is the ground block, so the player stands at y=1. */
    static final class Builder {
        final List<Fill> fills = new ArrayList<>();

        Builder fill(int x0, int y0, int z0, int x1, int y1, int z1, Material m) {
            fills.add(new Fill(Math.min(x0, x1), Math.min(y0, y1), Math.min(z0, z1), Math.max(x0, x1), Math.max(y0, y1),
                Math.max(z0, z1), m));
            return this;
        }

        /** Ground 4 blocks thick (dirt on stone) under x0..x1 of the lane. */
        Builder ground(int x0, int x1) {
            return ground(x0, x1, -2, 2);
        }

        Builder ground(int x0, int x1, int z0, int z1) {
            fill(x0, -3, z0, x1, -1, z1, Material.STONE);
            return fill(x0, 0, z0, x1, 0, z1, Material.DIRT);
        }

        Builder set(int x, int y, int z, Material m) {
            return fill(x, y, z, x, y, z, m);
        }
    }
}
