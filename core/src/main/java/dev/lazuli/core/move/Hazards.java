package dev.lazuli.core.move;

import java.util.ArrayList;
import java.util.List;

/**
 * Things to keep away from (hostile mobs), as extra cost on the cells near them: {@code cost} ticks right at one,
 * fading to nothing at {@code radius}. The path then goes around them when that's not much longer.
 */
public final class Hazards {
    /** One mob: where it is, how far its cost reaches (blocks), and how much it adds right at it (ticks). */
    public record Hazard(int x, int y, int z, double radius, double cost) {}

    public static final Hazards NONE = new Hazards(List.of());

    private final List<Hazard> list;

    private Hazards(List<Hazard> list) {
        this.list = list;
    }

    public static Hazards of(List<Hazard> list) {
        return list.isEmpty() ? NONE : new Hazards(List.copyOf(list));
    }

    public List<Hazard> list() {
        return list;
    }

    public boolean isEmpty() {
        return list.isEmpty();
    }

    /** Extra ticks for standing at (x, y, z). */
    public double cost(int x, int y, int z) {
        double c = 0;
        for (Hazard h : list) {
            double dx = x - h.x, dz = z - h.z, dy = (y - h.y) * 1.5; // a mob two floors up matters less
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < h.radius) c += h.cost * (1 - d / h.radius);
        }
        return c;
    }

    /** Boxes (minX, minY, minZ, maxX, maxY, maxZ) around each hazard, where costs depend on it. */
    public List<int[]> boxes() {
        List<int[]> out = new ArrayList<>(list.size());
        for (Hazard h : list) {
            int r = (int) Math.ceil(h.radius);
            out.add(new int[]{h.x - r, h.y - r, h.z - r, h.x + r, h.y + r, h.z + r});
        }
        return out;
    }

    /** Whether the two would price every cell the same (so swapping them needs no repair). */
    public boolean sameAs(Hazards o) {
        return list.equals(o.list);
    }
}
