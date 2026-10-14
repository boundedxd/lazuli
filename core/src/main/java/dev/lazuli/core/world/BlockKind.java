package dev.lazuli.core.world;

/** What the planner needs to know about one block. */
public enum BlockKind {
    /** Not loaded: freespace assumption (walkable floor and open air). */
    UNKNOWN,
    /** No collision, harmless. */
    AIR,
    /** Full-cube collision, safe to stand on. */
    SOLID,
    /** Water or waterlogged; impassable for now. */
    WATER,
    /** Lava, fire, magma, cactus, berry bush, cobweb, powder snow, campfire, wither rose, void. */
    DANGER,
    /** Partial collision (slabs, stairs, fences, carpets, ladders); avoided for now. */
    OBSTACLE;

    private static final BlockKind[] VALUES = values();

    public static BlockKind byId(int id) {
        return VALUES[id];
    }
}
