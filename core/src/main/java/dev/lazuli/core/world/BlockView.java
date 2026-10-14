package dev.lazuli.core.world;

/** Read-only, thread-safe view of block kinds (for planning) and block physics (for simulation). */
public interface BlockView {
    BlockKind get(int x, int y, int z);

    /** Collision boxes and movement properties; by default derived from {@link #get}. */
    default BlockPhysics physics(int x, int y, int z) {
        return BlockPhysics.forKind(get(x, y, z));
    }
}
