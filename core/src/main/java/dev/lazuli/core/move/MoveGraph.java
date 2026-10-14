package dev.lazuli.core.move;

import dev.lazuli.core.planner.Graph;
import dev.lazuli.core.planner.LazyDStarLite;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import java.util.function.LongConsumer;

/** Rule-based maneuvers: flat walk/sprint (cardinal + diagonal), ascend one block, descend/fall up to maxFall. */
public final class MoveGraph implements Graph {
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private static final double INF = LazyDStarLite.INF;

    private final BlockView w;
    private final int maxFall;
    private final double flat;

    public MoveGraph(BlockView world, int maxFall, boolean sprint) {
        this.w = world;
        this.maxFall = maxFall;
        this.flat = sprint ? Costs.SPRINT_ONE : Costs.WALK_ONE;
    }

    public static boolean passable(BlockKind k) {
        return k == BlockKind.AIR || k == BlockKind.UNKNOWN;
    }

    public static boolean floor(BlockKind k) {
        return k == BlockKind.SOLID || k == BlockKind.UNKNOWN;
    }

    public static boolean isStandable(BlockView w, int x, int y, int z) {
        return floor(w.get(x, y - 1, z)) && passable(w.get(x, y, z)) && passable(w.get(x, y + 1, z));
    }

    private boolean open2(int x, int y, int z) {
        return passable(w.get(x, y, z)) && passable(w.get(x, y + 1, z));
    }

    @Override
    public boolean isNode(long u) {
        return isStandable(w, Pos.x(u), Pos.y(u), Pos.z(u));
    }

    @Override
    public double optimisticCost(long u, long v) {
        int ux = Pos.x(u), uy = Pos.y(u), uz = Pos.z(u);
        int dx = Pos.x(v) - ux, dy = Pos.y(v) - uy, dz = Pos.z(v) - uz;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx == 0 && dz == 0)) return INF;
        if (!isNode(u) || !isNode(v)) return INF;
        boolean diag = dx != 0 && dz != 0;
        if (dy == 0) {
            if (diag && !(open2(ux + dx, uy, uz) && open2(ux, uy, uz + dz))) return INF;
            return diag ? flat * Costs.SQRT2 : flat;
        }
        if (diag) return INF;
        if (dy == 1) {
            return passable(w.get(ux, uy + 2, uz)) ? flat + Costs.JUMP_PENALTY : INF;
        }
        if (dy < 0 && -dy <= maxFall) {
            for (int y = Pos.y(v) + 2; y <= uy + 1; y++) {
                if (!passable(w.get(ux + dx, y, uz + dz))) return INF;
            }
            return flat + Costs.fallTicks(-dy);
        }
        return INF;
    }

    @Override
    public void successors(long u, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -maxFall; dy <= 1; dy++) out.accept(Pos.offset(u, d[0], dy, d[1]));
        }
    }

    @Override
    public void predecessors(long v, LongConsumer out) {
        for (int[] d : DIRS) {
            for (int dy = -1; dy <= maxFall; dy++) out.accept(Pos.offset(v, d[0], dy, d[1]));
        }
    }

    @Override
    public double heuristic(long a, long b) {
        int dx = Math.abs(Pos.x(a) - Pos.x(b)), dz = Math.abs(Pos.z(a) - Pos.z(b));
        return flat * (Math.max(dx, dz) + (Costs.SQRT2 - 1) * Math.min(dx, dz));
    }

    @Override
    public int horizontalReach() {
        return 1;
    }

    @Override
    public int verticalReach() {
        return maxFall + 2;
    }
}
