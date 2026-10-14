package dev.lazuli.core.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a block does to a moving player: its collision boxes (block-local, y may exceed 1 for fences and walls),
 * friction, speed and jump factors, and special behaviour flags. Instances are interned in a global palette and
 * identified by a short id, so the world cache stores two bytes per block.
 */
public final class BlockPhysics {
    public static final int CLIMBABLE = 1;
    public static final int WATER = 2;
    public static final int SLIME = 4;
    public static final int BED = 8;
    public static final int LAVA = 16;
    /** Cobwebs, berry bushes, powder snow: slows to a crawl; never path through. */
    public static final int STICKY = 32;

    public static final float DEFAULT_FRICTION = 0.6f;

    private static final List<BlockPhysics> PALETTE = new ArrayList<>();
    /** Copy-on-write snapshot of the palette for lock-free reads on the simulation hot path. */
    private static volatile BlockPhysics[] byIdArray = new BlockPhysics[1];
    private static final Map<BlockPhysics, BlockPhysics> INTERN = new HashMap<>();

    /** Id 0 means "derive from the block kind", see {@link #forKind}. */
    public static final short DERIVED = 0;
    public static final BlockPhysics EMPTY = intern(new double[0], DEFAULT_FRICTION, 1, 1, 0);
    public static final BlockPhysics FULL = intern(new double[]{0, 0, 0, 1, 1, 1}, DEFAULT_FRICTION, 1, 1, 0);
    public static final BlockPhysics WATER_BLOCK = intern(new double[0], DEFAULT_FRICTION, 1, 1, WATER);

    /** Flattened boxes: minX, minY, minZ, maxX, maxY, maxZ per box, block-local. */
    public final double[] boxes;
    public final float friction;
    public final float speedFactor;
    public final float jumpFactor;
    public final int flags;
    /** Highest collision top, block-local (0 if no collision). */
    public final double top;
    public final short id;

    private BlockPhysics(double[] boxes, float friction, float speedFactor, float jumpFactor, int flags, short id) {
        this.boxes = boxes;
        this.friction = friction;
        this.speedFactor = speedFactor;
        this.jumpFactor = jumpFactor;
        this.flags = flags;
        double t = 0;
        for (int i = 0; i < boxes.length; i += 6) t = Math.max(t, boxes[i + 4]);
        this.top = t;
        this.id = id;
    }

    public static synchronized BlockPhysics intern(double[] boxes, float friction, float speedFactor, float jumpFactor, int flags) {
        BlockPhysics probe = new BlockPhysics(boxes, friction, speedFactor, jumpFactor, flags, (short) -1);
        BlockPhysics existing = INTERN.get(probe);
        if (existing != null) return existing;
        if (PALETTE.size() >= Short.MAX_VALUE - 1) return FULL; // absurdly many distinct shapes: be conservative
        short id = (short) (PALETTE.size() + 1);
        BlockPhysics p = new BlockPhysics(boxes.clone(), friction, speedFactor, jumpFactor, flags, id);
        PALETTE.add(p);
        INTERN.put(p, p);
        BlockPhysics[] next = Arrays.copyOf(byIdArray, id + 1);
        next[id] = p;
        byIdArray = next;
        return p;
    }

    public static BlockPhysics byId(short id) {
        BlockPhysics[] a = byIdArray;
        return id <= 0 || id >= a.length ? null : a[id];
    }

    /** Physics implied by a coarse block kind, for terrain without detailed data (tests, unknown chunks). */
    public static BlockPhysics forKind(BlockKind k) {
        return switch (k) {
            case AIR -> EMPTY;
            case WATER -> WATER_BLOCK;
            default -> FULL;
        };
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public boolean empty() {
        return boxes.length == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BlockPhysics p)) return false;
        return Float.compare(friction, p.friction) == 0 && Float.compare(speedFactor, p.speedFactor) == 0
            && Float.compare(jumpFactor, p.jumpFactor) == 0 && flags == p.flags && Arrays.equals(boxes, p.boxes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(boxes) * 31 + Float.hashCode(friction) * 17 + Float.hashCode(speedFactor) * 7
            + Float.hashCode(jumpFactor) * 3 + flags;
    }
}
