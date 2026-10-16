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
        this.minimap = new Minimap(session, settings);
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
        var sel = selection.get();
        if (sel != null && LazuliConfig.renderSelection) {
            int[] c1 = sel.pos1(), c2 = sel.pos2();
            if (c1 != null) Gizmos.cuboid(new BlockPos(c1[0], c1[1], c1[2]), GizmoStyle.stroke(0xFF55FFFF, width));
            if (c2 != null) Gizmos.cuboid(new BlockPos(c2[0], c2[1], c2[2]), GizmoStyle.stroke(0xFFFF55FF, width));
            var b = sel.box();
            if (b != null) {
                Gizmos.cuboid(new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1),
                    GizmoStyle.stroke(0xFF55FFFF, width));
            }
        }
        for (var w : waypoints.get()) {
            BlockPos b = new BlockPos(w.x(), w.y(), w.z());
            boolean death = w.name().equals(dev.lazuli.core.waypoint.Waypoints.DEATH);
            int color = death ? 0xFFFF5555 : 0xFFFFD040;
            Gizmos.cuboid(b, GizmoStyle.stroke(color, width));
            Gizmos.line(new Vec3(w.x() + 0.5, w.y() + 1, w.z() + 0.5), new Vec3(w.x() + 0.5, w.y() + 64, w.z() + 0.5), color, width);
            int dist = (int) Math.sqrt(mc.player.blockPosition().distSqr(b));
            Gizmos.billboardTextOverBlock(w.name() + " (" + dist + "m)", b, 0, color, 1.0f);
        }
    }

    private Supplier<List<dev.lazuli.core.waypoint.Waypoints.Waypoint>> waypoints = List::of;
    private Supplier<dev.lazuli.core.area.Selection> selection = () -> null;

    /** #sel's corners and box, to draw. */
    public void selection(Supplier<dev.lazuli.core.area.Selection> selection) {
        this.selection = selection;
    }

    /** The waypoints to draw (this dimension's, or none when they're switched off). */
    public void waypoints(Supplier<List<dev.lazuli.core.waypoint.Waypoints.Waypoint>> waypoints) {
        this.waypoints = waypoints;
        minimap.waypoints(waypoints);
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
        if (settings.minimap.get()) minimap.tick(mc, settings);
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
        if (settings.hudCard.get()) renderCard(g, mc);
        else renderPlain(g, mc);
    }

    /** The minimap, its own HUD element. */
    public void renderMinimap(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui || !settings.minimap.get()) return;
        minimap.render(g, mc);
    }

    /** The status as a Material card: title, status lines, progress bar and chips. Nothing when idle. */
    private void renderCard(GuiGraphics g, Minecraft mc) {
        PathingSession.Snapshot snap = session.snapshot();
        boolean pathing = snap.state() == PathingSession.State.PATHING, planning = session.planning();
        Material.Scheme c = Material.Scheme.of(LazuliConfig.pathColor.getRGB());
        BlockPos b = mc.player.blockPosition();
        String title = null;
        List<String> lines = new java.util.ArrayList<>();
        double bar = -1;
        for (dev.lazuli.mod.process.Process p : processes) {
            if (!p.active()) continue;
            String st = p.status();
            if (title == null) title = processName(p);
            if (st != null && lines.size() < 3) lines.add(st);
            if (bar < 0 && LazuliConfig.hudBars) bar = p.progress();
        }
        boolean job = title != null;
        double dist = pathing && snap.goal() != null ? snap.goal().distance(b.getX(), b.getY(), b.getZ()) : 0;
        if (pathing && !job && snap.goal() != null) lines.add("Heading to " + clip(snap.goal().describe(), 32));
        if (dev.lazuli.mod.process.Upkeep.eating()) lines.add("Eating");
        if (!job) {
            if (planning) title = "Planning";
            else if (pathing) title = "Pathing";
            else if (flashTicks > 0) title = flash;
            else if (!lines.isEmpty()) title = "Working";
            else return;
        }
        if (bar < 0 && LazuliConfig.hudBars) {
            if (pathing && job) bar = -2; // pathing under a job with no figure of its own: indeterminate
            else if (pathing && snap.trip() == hudTrip) bar = Math.max(0, Math.min(1, 1 - dist / tripStartDist));
            else if (planning || pathing) bar = -2;
        }
        List<String> chips = new java.util.ArrayList<>();
        if (pathing && !snap.path().isEmpty()) {
            chips.add(blocksLeft(snap.path(), mc) + " left");
            if (LazuliConfig.hudStats && speed > 0.5) chips.add(String.format("%.1f m/s  ~%s", speed, time(dist / speed)));
        }
        if (pathing && snap.goal() != null) chips.add(goalChip(snap.goal()));

        Font font = mc.font;
        int r = settings.hudCorner.get();
        int chipsW = 0;
        for (String ch : chips) chipsW += font.width(ch) + 8 + 4;
        int w = Math.max(font.width(title) + 4, 120);
        for (String l : lines) w = Math.max(w, font.width(l));
        w = Math.min(Math.max(w, chipsW - 4), 230);
        int pad = 8, x0 = PAD, y0 = PAD;
        int h = pad + 10 + (lines.isEmpty() ? 0 : 2 + lines.size() * LINE) + (bar != -1 ? 4 + 4 : 0) + (chips.isEmpty() ? 0 : 6 + 12) + pad;
        Material.rrect(g, x0, y0, x0 + w + 2 * pad, y0 + h, r, c.surface());
        int x = x0 + pad, y = y0 + pad;
        g.drawString(font, title, x, y, c.primary(), false);
        y += 10;
        if (!lines.isEmpty()) y += 2;
        for (String l : lines) {
            g.drawString(font, font.plainSubstrByWidth(l, w), x, y, c.onSurfaceVariant(), false);
            y += LINE;
        }
        if (bar != -1) {
            y += 4;
            Material.linear(g, c, x, y, w, 4, bar < 0 ? -1 : bar, System.currentTimeMillis() / 1000f);
            y += 4;
        }
        if (!chips.isEmpty()) {
            y += 6;
            int cx = x;
            for (String ch : chips) {
                if (cx + font.width(ch) + 8 > x + w) break;
                cx += Material.chip(g, font, c, cx, y, ch, r) + 4;
            }
        }
    }

    /** "Mine" for MineProcess, "Get Item" for GetItemProcess. */
    private static String processName(dev.lazuli.mod.process.Process p) {
        String n = p.getClass().getSimpleName().replaceAll("Process$", "");
        n = n.replaceAll("([a-z])([A-Z])", "$1 $2");
        return n.isEmpty() ? "Working" : n;
    }

    private static String clip(String s, int max) {
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }

    private static String goalChip(Goal goal) {
        if (goal instanceof GoalBlock b) return b.x() + " " + b.y() + " " + b.z();
        if (goal instanceof GoalXZ z) return z.x() + " " + z.z();
        return clip(goal.describe(), 24);
    }

    /** Path nodes from the one nearest the player to the end. */
    private static int blocksLeft(List<Long> path, Minecraft mc) {
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int best = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            long n = path.get(i);
            double dx = Pos.x(n) + 0.5 - px, dy = Pos.y(n) - py, dz = Pos.z(n) + 0.5 - pz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return path.size() - 1 - best;
    }

    private final Minimap minimap;

    private void renderPlain(GuiGraphics g, Minecraft mc) {
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
