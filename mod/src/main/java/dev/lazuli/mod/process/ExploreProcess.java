package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #explore [x z]: walks to the nearest chunk (spiralling out from the origin) that has never been loaded, again and
 * again, so new terrain keeps coming into view.
 */
public final class ExploreProcess implements Process {
    /** How far out (chunks) the spiral looks before giving up. */
    private static final int MAX_RING = 1000;

    private final Consumer<String> chat;
    private final Trip trip;
    private final Set<Long> seen = new HashSet<>();
    private int originX, originZ;
    private long target = Long.MIN_VALUE;
    private int fromRing;
    private boolean active;

    public ExploreProcess(PathingSession session, Consumer<String> chat) {
        this.chat = chat;
        this.trip = new Trip(session);
    }

    @Override
    public boolean active() {
        return active;
    }

    /** @param origin {x, z} block coordinates to spiral out from, or null for here */
    public void start(Minecraft mc, int[] origin) {
        LocalPlayer p = mc.player;
        originX = (origin != null ? origin[0] : p.getBlockX()) >> 4;
        originZ = (origin != null ? origin[1] : p.getBlockZ()) >> 4;
        seen.clear();
        int r = mc.options.getEffectiveRenderDistance() + 2;
        int pcx = p.getBlockX() >> 4, pcz = p.getBlockZ() >> 4;
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++) {
                if (mc.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) seen.add(ChunkPos.asLong(cx, cz));
            }
        target = Long.MIN_VALUE;
        fromRing = 0;
        active = true;
        trip.clear();
        chat.accept(Chat.ok("Exploring from {}", (originX * 16 + 8) + " " + (originZ * 16 + 8)));
    }

    public void onChunkLoad(LevelChunk chunk) {
        if (active) seen.add(chunk.getPos().toLong());
    }

    @Override
    public void stop(boolean announce) {
        if (!active) return;
        active = false;
        trip.cancel();
        if (announce) chat.accept(Chat.warn("Stopped exploring"));
    }

    @Override
    public void tick(Minecraft mc) {
        if (!active) return;
        if (trip.ended() == PathingSession.State.FAILED) {
            seen.add(target); // can't get there: count it as done
            trip.clear();
        }
        if (target != Long.MIN_VALUE && !seen.contains(target) && trip.running()) return;
        long next = nextUnseen();
        if (next == Long.MIN_VALUE) {
            active = false;
            chat.accept(Chat.ok("Explored everything within {} chunks", MAX_RING));
            return;
        }
        target = next;
        trip.go(new GoalXZ(ChunkPos.getX(next) * 16 + 8, ChunkPos.getZ(next) * 16 + 8), mc.player);
    }

    /** The first never-loaded chunk, ring by ring around the origin. */
    private long nextUnseen() {
        for (int r = fromRing; r <= MAX_RING; r++) {
            boolean ringDone = true;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    long c = ChunkPos.asLong(originX + dx, originZ + dz);
                    if (!seen.contains(c)) return c;
                }
            if (ringDone) fromRing = r; // every chunk of rings before this one has been seen
        }
        return Long.MIN_VALUE;
    }
}
