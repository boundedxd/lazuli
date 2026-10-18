package dev.lazuli.mod.process;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * #explore [x z]: walks to the nearest chunk (spiralling out from the origin) that has never been loaded, again and
 * again, so new terrain keeps coming into view. A chunk that never loads when it gets there counts as seen after a few
 * ticks; one it can't reach is tried once more after a cooldown (retrySkippedSeconds); one it makes no headway
 * towards is given up on.
 */
public final class ExploreProcess implements Process {
    /** How far out (chunks) the spiral looks before giving up. */
    private static final int MAX_RING = 1000;
    /** Ticks to wait for a chunk to show up after arriving, ticks before a failed one is tried again, ticks without moving. */
    private static final int LOAD_WAIT = 60, COOLDOWN = 20 * 20, STALL_TICKS = 20 * 30;

    private final Settings settings;
    private final Consumer<String> chat;
    private final Trip trip;
    private final Set<Long> seen = new HashSet<>();
    /** Chunks whose trip failed once, and the tick they're tried again. */
    private final Map<Long, Integer> deferred = new HashMap<>();
    private final Set<Long> retried = new HashSet<>();
    private int originX, originZ;
    private long target = Long.MIN_VALUE;
    private int fromRing, arrivedTick = -1, now, lastMoved;
    private BlockPos lastPos;
    private boolean active;

    public ExploreProcess(PathingSession session, Settings settings, Consumer<String> chat) {
        this.settings = settings;
        this.chat = chat;
        this.trip = new Trip(session);
    }

    @Override
    public String status() {
        return active() ? "Exploring" : null;
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
        deferred.clear();
        retried.clear();
        arrivedTick = -1;
        lastPos = null;
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
        LocalPlayer p = mc.player;
        if (p == null) return;
        now = p.tickCount;
        // a chunk that can't be reached: once more after a cooldown, then it counts as done
        if (trip.ended() == PathingSession.State.FAILED) {
            giveUpOn(target);
            trip.clear();
        }
        // got there but the chunk hasn't shown up (outside the server's view, never loads): a few ticks, then done
        if (trip.ended() == PathingSession.State.ARRIVED) {
            if (arrivedTick < 0) arrivedTick = now;
            if (!seen.contains(target) && now - arrivedTick <= LOAD_WAIT) return;
            seen.add(target);
            arrivedTick = -1;
            trip.clear();
        }
        // watchdog: no headway for a long while
        if (lastPos == null || p.blockPosition().distSqr(lastPos) > 4) {
            lastPos = p.blockPosition();
            lastMoved = now;
        } else if (target != Long.MIN_VALUE && now - lastMoved > STALL_TICKS && !seen.contains(target)) {
            trip.cancel();
            trip.clear();
            giveUpOn(target);
            lastMoved = now;
        }
        if (target != Long.MIN_VALUE && !seen.contains(target) && !deferred.containsKey(target) && trip.running()) return;
        long next = nextUnseen();
        if (next == Long.MIN_VALUE) {
            if (!deferred.isEmpty()) return; // waiting for the cooldown of the ones that failed
            active = false;
            chat.accept(Chat.ok("Explored everything within {} chunks", MAX_RING));
            return;
        }
        target = next;
        arrivedTick = -1;
        lastMoved = now;
        trip.go(new GoalXZ(ChunkPos.getX(next) * 16 + 8, ChunkPos.getZ(next) * 16 + 8), p);
    }

    /** Sets {@code chunk} aside to try once more after a cooldown; a second time (or with retries off) it counts as seen. */
    private void giveUpOn(long chunk) {
        if (chunk == Long.MIN_VALUE) return;
        if (settings.retrySkippedSeconds.get() > 0 && retried.add(chunk)) deferred.put(chunk, now + COOLDOWN);
        else seen.add(chunk);
    }

    /** The first never-loaded chunk, ring by ring around the origin (those cooling down after a failure skipped). */
    private long nextUnseen() {
        for (int r = fromRing; r <= MAX_RING; r++) {
            boolean allSeen = true;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    long c = ChunkPos.asLong(originX + dx, originZ + dz);
                    if (seen.contains(c)) continue;
                    allSeen = false;
                    Integer until = deferred.get(c);
                    if (until != null) {
                        if (now < until) continue; // failed lately: later
                        deferred.remove(c); // cooled down: its one more go
                    }
                    return c;
                }
            if (allSeen) fromRing = r + 1; // every chunk of that ring has been seen
        }
        return Long.MIN_VALUE;
    }
}
