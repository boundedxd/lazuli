package dev.lazuli.core.control;

import dev.lazuli.core.world.Pos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The ghost view (renderGhost): for the last trip, every path it planned (replans too) and where the player really
 * went, each tick's spot marked when it was off every planned path, so a movement that went wrong shows where. Kept
 * after the trip ends, until the next one starts.
 */
public final class GhostTrail {
    /** Further than this (blocks, over the ground) from the planned path counts as off it. */
    public static final double OFF = 1.0;

    /** One tick's position, and whether it was off the plan. */
    public record Sample(double x, double y, double z, boolean off) {}

    private final int max;
    private long trip = -1;
    /** Nodes of every path planned this trip, in order seen, and the last path. */
    private final Set<Long> planned = new LinkedHashSet<>();
    private List<Long> last = List.of();
    private final List<Sample> samples = new ArrayList<>();

    /** @param max how many ticks of trail to keep (the oldest go) */
    public GhostTrail(int max) {
        this.max = Math.max(1, max);
    }

    /**
     * Each client tick. {@code pathing}: a trip is running; {@code trip}: its number; {@code path}: the path published
     * for it; (x, y, z): the player's feet.
     */
    public void tick(boolean pathing, long trip, List<Long> path, double x, double y, double z) {
        if (!pathing) return;
        if (trip != this.trip) {
            this.trip = trip;
            planned.clear();
            samples.clear();
            last = List.of();
        }
        if (!path.isEmpty() && !path.equals(last)) {
            last = List.copyOf(path);
            planned.addAll(path);
        }
        if (samples.size() >= max) samples.remove(0);
        samples.add(new Sample(x, y, z, !planned.isEmpty() && offPath(last, x, y, z)));
    }

    /** Whether (x, y, z) is further than {@link #OFF} from every segment of {@code path} (over the ground, within 2 up or down). */
    public static boolean offPath(List<Long> path, double x, double y, double z) {
        if (path.isEmpty()) return false;
        for (int i = 0; i < path.size(); i++) {
            long a = path.get(i), b = path.get(Math.min(i + 1, path.size() - 1));
            double ax = Pos.x(a) + 0.5, az = Pos.z(a) + 0.5, bx = Pos.x(b) + 0.5, bz = Pos.z(b) + 0.5;
            double lo = Math.min(Pos.y(a), Pos.y(b)) - 2, hi = Math.max(Pos.y(a), Pos.y(b)) + 2;
            if (y < lo || y > hi) continue;
            double dx = bx - ax, dz = bz - az, len = dx * dx + dz * dz;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / len));
            double px = ax + dx * t - x, pz = az + dz * t - z;
            if (px * px + pz * pz <= OFF * OFF) return false;
        }
        return true;
    }

    public List<Sample> samples() {
        return samples;
    }

    /** The trip's last planned path. */
    public List<Long> path() {
        return last;
    }

    /** Every node planned this trip that the last path doesn't use: abandoned plans. */
    public List<Long> abandoned() {
        Set<Long> keep = new LinkedHashSet<>(planned);
        last.forEach(keep::remove);
        return new ArrayList<>(keep);
    }

    public void clear() {
        trip = -1;
        planned.clear();
        samples.clear();
        last = List.of();
    }
}
