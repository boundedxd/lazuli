package dev.lazuli.core.target;

/**
 * A named thing in the world: a container, villager, station, plain block or other entity.
 * {@code uuid} is null for blocks, {@code block} (like minecraft:brewing_stand) null for entities, {@code label}
 * is free text (an entity's type, a villager's profession...).
 */
public record Target(String name, Kind kind, String dim, int x, int y, int z, String uuid, String block, String label) {
    public enum Kind {
        CONTAINER("chest"), VILLAGER("villager"), STATION("station"), BLOCK("block"), ENTITY("entity");

        private final String prefix;

        Kind(String prefix) {
            this.prefix = prefix;
        }

        public String prefix() {
            return prefix;
        }
    }

    public boolean isEntity() {
        return uuid != null;
    }

    public double distSq(int px, int py, int pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public Target at(int nx, int ny, int nz) {
        return new Target(name, kind, dim, nx, ny, nz, uuid, block, label);
    }

    public Target named(String n) {
        return new Target(n, kind, dim, x, y, z, uuid, block, label);
    }

    public String coords() {
        return x + " " + y + " " + z;
    }
}
