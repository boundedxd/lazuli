package dev.lazuli.mod.process;

import dev.lazuli.core.mine.OreDepth;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashSet;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Which blocks are in the loaded world around the player, for #getitem to prefer them. Only each chunk section's
 * palette is looked at (the kinds of block in it), not every block, so it's quick.
 */
final class Nearby {
    private Nearby() {}

    /** For a block id: 1 if some are loaded around, 0 if none, -1 for ores when anti-xray hides them. */
    static ToIntFunction<String> scan(Minecraft mc, boolean antiXray) {
        Set<Block> seen = new HashSet<>();
        if (mc.player != null && mc.level != null) {
            int r = mc.options.getEffectiveRenderDistance();
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            for (int cx = pcx - r; cx <= pcx + r; cx++)
                for (int cz = pcz - r; cz <= pcz + r; cz++) {
                    LevelChunk c = mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (c == null) continue;
                    for (LevelChunkSection s : c.getSections()) {
                        if (s == null || s.hasOnlyAir()) continue;
                        // adds every kind in the palette (a big mixed section's palette says "maybe anything": skipped)
                        s.getStates().maybeHas(st -> {
                            seen.add(st.getBlock());
                            return false;
                        });
                    }
                }
        }
        return id -> {
            if (antiXray && OreDepth.isOre(id)) return -1;
            var rl = net.minecraft.resources.Identifier.tryParse(id);
            Block b = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (b == null) return -1;
            return seen.contains(b) ? 1 : 0;
        };
    }
}
