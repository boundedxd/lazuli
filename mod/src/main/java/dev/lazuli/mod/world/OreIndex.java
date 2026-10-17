package dev.lazuli.mod.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;

/**
 * Where the ores are (Baritone's cached-world idea): filled while chunks load (the scan that builds the world cache
 * already looks at every block), kept up to date as blocks change, and kept after a chunk unloads, so #mine finds
 * ores at once without rescanning, including ones it saw earlier further off. Client thread only; one world at a time.
 */
public final class OreIndex {
    /** Blocks worth remembering: every ore, and ancient debris. */
    public static final Set<Block> ORES = ores();

    private static Set<Block> ores() {
        java.util.Set<Block> out = new java.util.HashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
            if (path.endsWith("_ore") || path.equals("ancient_debris")) out.add(b);
        }
        return Set.copyOf(out);
    }

    private final Map<Block, LongOpenHashSet> byBlock = new HashMap<>();
    private final Long2ObjectOpenHashMap<Block> at = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LongArrayList> byChunk = new Long2ObjectOpenHashMap<>();

    public void clear() {
        byBlock.clear();
        at.clear();
        byChunk.clear();
    }

    /** A chunk's ores as just scanned: replaces what was remembered there. */
    public void putChunk(int cx, int cz, long[] positions, Block[] blocks) {
        long key = ChunkPos.asLong(cx, cz);
        LongArrayList old = byChunk.remove(key);
        if (old != null) for (int i = 0; i < old.size(); i++) remove(old.getLong(i));
        if (positions.length == 0) return;
        LongArrayList list = new LongArrayList(positions.length);
        for (int i = 0; i < positions.length; i++) {
            add(positions[i], blocks[i]);
            list.add(positions[i]);
        }
        byChunk.put(key, list);
    }

    /** A block changed: an ore there now, or not any more. */
    public void set(long pos, int x, int z, BlockState state) {
        Block b = state.getBlock();
        boolean ore = ORES.contains(b);
        Block had = at.get(pos);
        if (had == b || had == null && !ore) return;
        if (had != null) remove(pos);
        if (ore) {
            add(pos, b);
            byChunk.computeIfAbsent(ChunkPos.asLong(x >> 4, z >> 4), k -> new LongArrayList()).add(pos);
        }
    }

    /** Every remembered position of {@code b}. */
    public void forEach(Block b, LongConsumer out) {
        LongOpenHashSet s = byBlock.get(b);
        if (s != null) s.forEach(out);
    }

    public int size() {
        return at.size();
    }

    private void add(long pos, Block b) {
        at.put(pos, b);
        byBlock.computeIfAbsent(b, k -> new LongOpenHashSet()).add(pos);
    }

    private void remove(long pos) {
        Block b = at.remove(pos);
        if (b == null) return;
        LongOpenHashSet s = byBlock.get(b);
        if (s != null) s.remove(pos);
    }
}
