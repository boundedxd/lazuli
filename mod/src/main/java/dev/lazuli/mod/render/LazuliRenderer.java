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

    // ---- HUD ----

    private static final int PAD = 4, BAR_W = 120, BAR_H = 3, LINE = 11;
    private static final String SPIN = "|/-\\";
    private List<dev.lazuli.mod.process.Process> processes = List.of();
    /** The trip the HUD measures progress on, and how far its goal was when it began. */
    private long hudTrip = -1;
    private double tripStartDist;
    private double speed, lastX = Double.NaN, lastZ;
    private PathingSession.State lastState = PathingSession.State.IDLE;
    private String flash;
    private int flashTicks, hudTicks;

    /** The processes whose status the HUD shows. */
    public void processes(List<dev.lazuli.mod.process.Process> processes) {
        this.processes = processes;
    }

    /** Per client tick: speed, trip progress and the "arrived" / "failed" flash. */
    public void hudTick(Minecraft mc) {
        hudTicks++;
        if (flashTicks > 0) flashTicks--;
        if (mc.player == null) return;
        double x = mc.player.getX(), z = mc.player.getZ();
        double v = Double.isNaN(lastX) ? 0 : Math.hypot(x - lastX, z - lastZ) * 20;
        speed = speed * 0.85 + v * 0.15; // smoothed, blocks a second
        lastX = x;
        lastZ = z;
        PathingSession.Snapshot snap = session.snapshot();
        if (snap.state() == PathingSession.State.PATHING && snap.goal() != null && snap.trip() != hudTrip) {
            hudTrip = snap.trip();
            BlockPos b = mc.player.blockPosition();
            tripStartDist = Math.max(1, snap.goal().distance(b.getX(), b.getY(), b.getZ()));
        }
        if (snap.state() != lastState) {
            if (snap.state() == PathingSession.State.ARRIVED) flash("Arrived", 60);
            else if (snap.state() == PathingSession.State.FAILED) flash("No path", 80);
            lastState = snap.state();
        }
    }

    private void flash(String text, int ticks) {
        flash = text;
        flashTicks = ticks;
    }

    public void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!LazuliConfig.showHud || mc.player == null || mc.options.hideGui) return;
        PathingSession.Snapshot snap = session.snapshot();
        boolean pathing = snap.state() == PathingSession.State.PATHING, planning = session.planning();
        int accent = 0xFF000000 | LazuliConfig.pathColor.getRGB();
        // rows: text, color, bar progress (negative: none), bar color
        List<Object[]> rows = new java.util.ArrayList<>();
        for (dev.lazuli.mod.process.Process p : processes) {
            String st = p.active() ? p.status() : null;
            if (st != null) rows.add(new Object[]{st, 0xFFFFFFFF, LazuliConfig.hudBars ? p.progress() : -1, 0xFF60E070});
        }
        BlockPos b = mc.player.blockPosition();
        if (pathing && snap.goal() != null) {
            double dist = snap.goal().distance(b.getX(), b.getY(), b.getZ());
            String goal = snap.goal().describe();
            if (goal.length() > 40) goal = goal.substring(0, 38) + "..";
            rows.add(new Object[]{"→ " + goal, 0xFFD0D0D0, -1.0, 0});
            String line = String.format("%.0f m", dist);
            if (LazuliConfig.hudStats) {
                line += String.format("  %.1f m/s", speed);
                if (speed > 0.5) line += "  ~" + time(dist / speed);
                line += "  " + snap.path().size() + " nodes";
            }
            double prog = snap.trip() == hudTrip ? Math.max(0, Math.min(1, 1 - dist / tripStartDist)) : -1;
            rows.add(new Object[]{line, 0xFFA0A0A0, LazuliConfig.hudBars ? prog : -1, accent});
        }
        if (dev.lazuli.mod.process.Upkeep.eating()) rows.add(new Object[]{"Eating", 0xFFFFC040, -1.0, 0});
        String title;
        if (planning) title = "Lazuli  planning " + SPIN.charAt(hudTicks / 3 % 4);
        else if (pathing) title = "Lazuli  pathing";
        else if (!rows.isEmpty()) title = "Lazuli  working";
        else if (flashTicks > 0) title = "Lazuli  " + flash;
        else return;

        Font font = mc.font;
        int w = font.width(title);
        int h = LINE;
        for (Object[] r : rows) {
            w = Math.max(w, font.width((String) r[0]));
            h += LINE + ((double) r[2] >= 0 ? BAR_H + 2 : 0);
        }
        w = Math.max(w, BAR_W);
        int x0 = PAD, y0 = PAD;
        g.fill(x0, y0, x0 + w + 2 * PAD, y0 + h + 2 * PAD - 2, 0x90000000);
        g.fill(x0, y0, x0 + 2, y0 + h + 2 * PAD - 2, accent); // accent strip
        int x = x0 + PAD + 1, y = y0 + PAD;
        g.drawString(font, title, x, y, accent, true);
        y += LINE;
        for (Object[] r : rows) {
            g.drawString(font, (String) r[0], x, y, (int) r[1], true);
            y += LINE;
            double prog = (double) r[2];
            if (prog >= 0) {
                int bw = Math.max(BAR_W, w);
                g.fill(x, y - 1, x + bw, y - 1 + BAR_H, 0x60FFFFFF);
                g.fill(x, y - 1, x + (int) Math.round(bw * prog), y - 1 + BAR_H, (int) r[3]);
                y += BAR_H + 2;
            }
        }
    }

    /** 75 -> "1m15s", 9 -> "9s". */
    private static String time(double seconds) {
        int s = (int) Math.round(seconds);
        return s >= 60 ? s / 60 + "m" + String.format("%02d", s % 60) + "s" : s + "s";
    }
}
