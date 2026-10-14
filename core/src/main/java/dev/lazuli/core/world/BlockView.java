package dev.lazuli.core.world;

/** Read-only, thread-safe view of block kinds. */
public interface BlockView {
    BlockKind get(int x, int y, int z);
}
