package dev.lazuli.core.probe;

import dev.lazuli.core.course.Course;
import dev.lazuli.core.course.Courses;
import dev.lazuli.core.move.Interaction;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.move.Standing;
import dev.lazuli.core.testutil.GridWorld;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.Pos;
import org.junit.jupiter.api.Test;

class WaterProbeTest {
    @Test
    void probe() {
        Course c = Courses.find("water");
        GridWorld w = new GridWorld(BlockKind.AIR);
        for (Course.Fill f : c.fills()) for (int x = f.x0(); x <= f.x1(); x++) for (int y = f.y0(); y <= f.y1(); y++) for (int z = f.z0(); z <= f.z1(); z++) {
            if (f.m() == Course.Material.AIR) w.set(x, y, z, BlockKind.AIR); else w.setPhysics(x, y, z, f.m().physics());
        }
        MoveGraph g = new MoveGraph(w, 3, true, 1.2, true, Interaction.NONE);
        System.out.println("PROBE kinds " + w.get(8, 0, 0) + " " + w.get(8, 1, 0) + " swim " + g.swims(Pos.pack(8, 0, 0)) + " node " + g.isNode(Pos.pack(8, 0, 0)));
        long[][] e = {{7, 1, 0, 8, 0, 0}, {8, 0, 0, 9, 0, 0}, {14, 0, 0, 15, 1, 0}};
        for (long[] a : e) {
            long u = Pos.pack((int) a[0], (int) a[1], (int) a[2]), v = Pos.pack((int) a[3], (int) a[4], (int) a[5]);
            StringBuilder sb = new StringBuilder();
            g.successors(u, x -> { if (x == v) sb.append("succ "); });
            g.predecessors(v, x -> { if (x == u) sb.append("pred "); });
            System.out.println("PROBE " + Pos.toString(u) + " -> " + Pos.toString(v) + " cost " + g.optimisticCost(u, v) + " " + sb + " feet(u)=" + Standing.feet(w, (int) a[0], (int) a[1], (int) a[2]));
        }
    }
}
