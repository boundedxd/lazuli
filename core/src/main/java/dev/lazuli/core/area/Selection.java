package dev.lazuli.core.area;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** #sel: the two corners picked, and the box between them. */
public final class Selection {
    private int[] pos1, pos2;

    /** A box of blocks, both corners included, given in any order. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Box {
            int t;
            if (minX > maxX) { t = minX; minX = maxX; maxX = t; }
            if (minY > maxY) { t = minY; minY = maxY; maxY = t; }
            if (minZ > maxZ) { t = minZ; minZ = maxZ; maxZ = t; }
        }

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /**
         * Every block in the box in working order: clearing goes from the top layer down (nothing left hanging, the
         * player never digs out what they stand on before the layer above), filling from the bottom up (each block
         * on the one below); within a layer the nearest to x y z first.
         */
        public List<int[]> order(boolean topDown, int x, int y, int z) {
            List<int[]> out = new ArrayList<>();
            for (int by = minY; by <= maxY; by++)
                for (int bx = minX; bx <= maxX; bx++)
                    for (int bz = minZ; bz <= maxZ; bz++) out.add(new int[]{bx, by, bz});
            Comparator<int[]> layer = Comparator.comparingInt(p -> topDown ? -p[1] : p[1]);
            out.sort(layer.thenComparingDouble(p -> {
                double dx = p[0] - x, dz = p[2] - z;
                return dx * dx + dz * dz;
            }));
            return out;
        }

        @Override
        public String toString() {
            return minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ;
        }
    }

    public void set(int which, int x, int y, int z) {
        if (which == 1) pos1 = new int[]{x, y, z};
        else pos2 = new int[]{x, y, z};
    }

    public void reset() {
        pos1 = pos2 = null;
    }

    /** The box, or null while a corner is missing. */
    public Box box() {
        if (pos1 == null || pos2 == null) return null;
        return new Box(pos1[0], pos1[1], pos1[2], pos2[0], pos2[1], pos2[2]);
    }

    public int[] pos1() {
        return pos1;
    }

    public int[] pos2() {
        return pos2;
    }
}
