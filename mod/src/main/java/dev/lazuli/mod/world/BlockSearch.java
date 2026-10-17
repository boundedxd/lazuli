package dev.lazuli.mod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Looking for blocks in the loaded world the way Baritone's WorldScanner does: chunk section by chunk section, skipping
 * every section whose palette (the kinds of block in it) has nothing that matches, so only the few sections that may
 * hold one are looked through block by block. Client thread.
 */
public final class BlockSearch {
    private BlockSearch() {}

    /** Calls {@code out} for each block in the box matching {@code test} (the position is reused: copy to keep it). */
    public static void forEach(Level level, int x0, int y0, int z0, int x1, int y1, int z1, Predicate<BlockState> test,
                               Consumer<BlockPos.MutableBlockPos> out) {
        y0 = Math.max(y0, level.getMinY());
        y1 = Math.min(y1, level.getMaxY());
        if (y0 > y1) return;
        boolean air = test.test(Blocks.AIR.defaultBlockState());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++)
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!(level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) continue;
                LevelChunkSection[] sections = chunk.getSections();
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15);
                int az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                for (int si = chunk.getSectionIndex(y0); si <= chunk.getSectionIndex(y1) && si < sections.length; si++) {
                    if (si < 0) continue;
                    LevelChunkSection sec = sections[si];
                    if (sec == null || sec.hasOnlyAir() ? !air : !sec.getStates().maybeHas(test)) continue;
                    int sy = chunk.getSectionYFromSectionIndex(si) << 4;
                    int ay = Math.max(y0, sy), by = Math.min(y1, sy + 15);
                    for (int y = ay; y <= by; y++)
                        for (int z = az; z <= bz; z++)
                            for (int x = ax; x <= bx; x++) {
                                BlockState s = sec == null ? Blocks.AIR.defaultBlockState() : sec.getBlockState(x & 15, y & 15, z & 15);
                                if (!test.test(s)) continue;
                                at.set(x, y, z);
                                out.accept(at);
                            }
                }
            }
    }

    /** The matching block in the box with the lowest {@code score} (e.g. distance), or null. */
    public static BlockPos best(Level level, int x0, int y0, int z0, int x1, int y1, int z1, Predicate<BlockState> test,
                                ToDoubleFunction<BlockPos> score) {
        BlockPos[] best = {null};
        double[] bestScore = {Double.MAX_VALUE};
        forEach(level, x0, y0, z0, x1, y1, z1, test, at -> {
            double s = score.applyAsDouble(at);
            if (s < bestScore[0]) {
                bestScore[0] = s;
                best[0] = at.immutable();
            }
        });
        return best[0];
    }
}
