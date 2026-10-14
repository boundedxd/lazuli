package dev.lazuli.mod.render;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.move.Digging;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.Pos;
import dev.lazuli.mod.config.LazuliConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * Path line, the input planner's predicted motion and the goal, drawn as vanilla per-tick gizmos (so they need no
 * render hooks and survive renderer changes), plus a one-line HUD status.
 */
public final class LazuliRenderer {
    private static final int MAX_NODES = 256;
    private static final double LIFT = 0.05;
    /** Path edges ahead whose breaks and placements are outlined. */
    private static final int ACTION_EDGES = 48;
    private static final int BREAK_COLOR = 0xFFFF5040;
    private static final int PLACE_COLOR = 0xFF40E0FF;

    private final PathingSession session;
    private final Settings settings;
    private final Supplier<double[]> prediction;
    private final Supplier<Goal> pendingGoal;
    private final BlockView world;

    public LazuliRenderer(PathingSession session, Settings settings, Supplier<double[]> prediction, Supplier<Goal> pendingGoal,
                          BlockView world) {
        this.world = world;
        this.session = session;
        this.settings = settings;
        this.prediction = prediction;
        this.pendingGoal = pendingGoal;
    }

    /** Call during the client tick, while vanilla collects per-tick gizmos. */
    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        PathingSession.Snapshot snap = session.snapshot();
        boolean pathing = snap.state() == PathingSession.State.PATHING;
        float width = LazuliConfig.lineWidth;
        if (pathing && settings.renderPath.get()) {
            List<Long> path = snap.path();
            int color = LazuliConfig.pathColor.getRGB();
            for (int i = 0; i + 1 < Math.min(path.size(), MAX_NODES); i++) {
                Gizmos.line(center(path.get(i)), center(path.get(i + 1)), color, width);
            }
            // blocks the path will break (red) and place (cyan)
            MoveGraph g = new MoveGraph(world, settings.maxFallHeight.get(), true, 1, settings.allowParkour.get());
            for (int i = 0; i + 1 < Math.min(path.size(), ACTION_EDGES); i++) {
                for (Digging.Action a : g.actions(path.get(i), path.get(i + 1))) {
                    long n = a.pos();
                    Gizmos.cuboid(new BlockPos(Pos.x(n), Pos.y(n), Pos.z(n)),
                        GizmoStyle.stroke(a.place() ? PLACE_COLOR : BREAK_COLOR, width));
                }
            }
        }
        if (pathing && LazuliConfig.renderPrediction) {
            double[] p = prediction.get();
            int color = LazuliConfig.predictionColor.getRGB();
            for (int i = 0; i + 5 < p.length; i += 3) {
                Gizmos.line(new Vec3(p[i], p[i + 1] + LIFT, p[i + 2]), new Vec3(p[i + 3], p[i + 4] + LIFT, p[i + 5]), color, width);
            }
        }
        // a finished or failed trip keeps its goal in the snapshot; a newer #goal must still show
        Goal goal = pathing && snap.goal() != null ? snap.goal() : pendingGoal.get();
        if (goal != null && LazuliConfig.renderGoal) drawGoal(mc, goal, width);
    }

    private static void drawGoal(Minecraft mc, Goal goal, float width) {
        int color = LazuliConfig.goalColor.getRGB();
        if (goal instanceof GoalBlock b) {
            Gizmos.cuboid(new BlockPos(b.x(), b.y(), b.z()), GizmoStyle.stroke(color, width));
        } else if (goal instanceof GoalXZ g) {
            int y0 = mc.level.getMinY(), y1 = mc.level.getMaxY();
            Gizmos.cuboid(new AABB(g.x(), y0, g.z(), g.x() + 1, y1, g.z() + 1), GizmoStyle.stroke(color, width));
        }
    }

    private static Vec3 center(long n) {
        return new Vec3(Pos.x(n) + 0.5, Pos.y(n) + LIFT, Pos.z(n) + 0.5);
    }

    public void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!LazuliConfig.showHud || mc.player == null || mc.options.hideGui) return;
        PathingSession.Snapshot snap = session.snapshot();
        if (snap.state() != PathingSession.State.PATHING) return;
        BlockPos p = mc.player.blockPosition();
        String text = "Lazuli: " + (snap.goal() == null ? "pathing" : snap.goal().describe());
        if (snap.goal() != null) text += String.format("  %.0f m", snap.goal().distance(p.getX(), p.getY(), p.getZ()));
        text += "  " + snap.path().size() + " nodes";
        Font font = mc.font;
        g.drawString(font, text, 4, 4, 0xFF000000 | LazuliConfig.pathColor.getRGB(), true);
    }
}
