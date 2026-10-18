package dev.lazuli.core.probe;

import dev.lazuli.core.move.JumpEvaluator;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockPhysics;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

/** Scratch: which gap jumps onto and off partial blocks the graph and the physics accept. */
class ShapeJumpProbeTest {
    static BlockPhysics box(double... b) {
        return BlockPhysics.intern(b, 0.6f, 1, 1, 0);
    }

    static final Object[][] SHAPES = {
        {"full", BlockPhysics.FULL},
        {"slab", box(0, 0, 0, 1, 0.5, 1)},
        {"trapdoor", box(0, 0, 0, 1, 0.1875, 1)},
        {"toptrap", box(0, 0.8125, 0, 1, 1, 1)},
        {"fencepost", box(0.375, 0, 0.375, 0.625, 1.5, 0.625)},
        {"wallpost", box(0.25, 0, 0.25, 0.75, 1.5, 0.75)},
        {"panepost", box(0.4375, 0, 0.4375, 0.5625, 1, 0.5625)},
        {"opentrap", box(0, 0, 0.8125, 1, 1, 1)},
        {"chain", box(0.40625, 0, 0.40625, 0.59375, 1, 0.59375)},
    };

    @Test
    void table() {
        for (Object[] sh : SHAPES) {
            BlockPhysics p = (BlockPhysics) sh[1];
            for (int gap = 1; gap <= 3; gap++) {
                GridWorld w = new GridWorld(BlockKind.AIR).fill(-4, 0, 0, 0, 0, 0, BlockKind.SOLID);
                int sx = gap + 1;
                w.setPhysics(sx, 0, 0, p);
                long u = Pos.pack(0, 1, 0);
                // the node of the shape: own cell if half high or less, else the one above
                long v = Standing.standable(w, sx, 0, 0) ? Pos.pack(sx, 0, 0) : Pos.pack(sx, 1, 0);
                double fv = Standing.feet(w, Pos.x(v), Pos.y(v), Pos.z(v));
                MoveGraph g = new MoveGraph(w, 3, true, 1, true);
                double opt = g.optimisticCost(u, v), ev = opt < Double.POSITIVE_INFINITY ? g.evaluate(u, v) : Double.NaN;
                double back = Double.NaN;
                // off it: onto a full block the same gap further on, at about its height
                GridWorld w2 = new GridWorld(BlockKind.AIR);
                w2.setPhysics(0, 0, 0, p);
                int top = Double.isNaN(fv) ? 0 : (int) Math.floor(fv - Pos.y(v) + Pos.y(v)) ; // floor of feet
                w2.fill(gap + 1, -3, 0, gap + 4, top - 1, 0, BlockKind.SOLID);
                long u2 = Standing.standable(w2, 0, 0, 0) ? Pos.pack(0, 0, 0) : Pos.pack(0, 1, 0);
                long v2 = Pos.pack(gap + 1, top, 0);
                MoveGraph g2 = new MoveGraph(w2, 3, true, 1, true);
                double opt2 = g2.optimisticCost(u2, v2), ev2 = opt2 < Double.POSITIVE_INFINITY ? g2.evaluate(u2, v2) : Double.NaN;
                System.out.printf("%-10s gap %d  ON: node %s feet %.3f opt %.1f eval %.1f | OFF: from %s feet %.3f to %s opt %.1f eval %.1f%n",
                    sh[0], gap, Pos.toString(v), fv, opt, ev, Pos.toString(u2),
                    Standing.feet(w2, Pos.x(u2), Pos.y(u2), Pos.z(u2)), Pos.toString(v2), opt2, ev2);
            }
        }
    }
}
