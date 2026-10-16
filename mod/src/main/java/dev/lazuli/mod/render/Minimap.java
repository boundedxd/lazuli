package dev.lazuli.mod.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.waypoint.Waypoints;
import dev.lazuli.core.world.Pos;
import dev.lazuli.mod.config.LazuliConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.List;
import java.util.function.Supplier;

/**
 * A minimap for the HUD: the top block's map colour of every column around the player, kept in a small ring cache
 * that a few hundred columns a tick refresh, painted into one texture a couple of times a second (north up, or turned
 * to the way you face), with the path, goal, waypoints and the player's arrow drawn over it as fills.
 */
final class Minimap {
    /** Blocks the cache holds each way from the player; enough for the lowest zoom. */
    private static final int REACH = 64, SIDE = 2 * REACH + 1;
    /** Blocks shown each way at zoom 1. */
    private static final double VIEW = 32;
    /** Columns re-read each tick. */
    private static final int PER_TICK = 256;
    private static final int PATH_NODES = 600;
    private static final Identifier ID = Identifier.fromNamespaceAndPath("lazuli", "minimap");

    private final PathingSession session;
    private final Settings settings;
    private Supplier<List<Waypoints.Waypoint>> waypoints = List::of;

    // the ring cache: a column lives in slot (x mod SIDE, z mod SIDE) and counts only while its key matches
    private final int[] color = new int[SIDE * SIDE], height = new int[SIDE * SIDE], keyX = new int[SIDE * SIDE], keyZ = new int[SIDE * SIDE];
    private final boolean[] known = new boolean[SIDE * SIDE];
    private int cursor, ticks;

    private DynamicTexture texture;
    private int texSize;
    /** Where the player was when the texture was painted; the overlays use it too, so both agree. */
    private double px, pz, yaw;
    private boolean ready;

    Minimap(PathingSession session, Settings settings) {
        this.session = session;
        this.settings = settings;
    }

    void waypoints(Supplier<List<Waypoints.Waypoint>> waypoints) {
        this.waypoints = waypoints;
    }

    // ---- cache ----

    /** Per client tick: refresh some columns, and now and then repaint. */
    void tick(Minecraft mc, Settings settings) {
        if (mc.player == null || mc.level == null) {
            ready = false;
            return;
        }
        int cx = mc.player.getBlockX(), cz = mc.player.getBlockZ(), py = mc.player.getBlockY();
        for (int n = 0; n < PER_TICK; n++) {
            int i = cursor++ % (SIDE * SIDE);
            if (cursor >= SIDE * SIDE) cursor = 0;
            scan(mc, cx - REACH + i % SIDE, cz - REACH + i / SIDE, py);
        }
        if (++ticks % 2 == 0) paint(mc);
    }

    private static int slot(int x, int z) {
        return Math.floorMod(z, SIDE) * SIDE + Math.floorMod(x, SIDE);
    }

    /** Reads one column into the cache. */
    private void scan(Minecraft mc, int x, int z, int playerY) {
        int s = slot(x, z);
        keyX[s] = x;
        keyZ[s] = z;
        known[s] = false;
        LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return;
        int minY = mc.level.getMinY();
        int y = mc.level.dimensionType().hasCeiling() ? playerY + 2 : chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        for (int n = 0; n < 40 && pos.getY() >= minY; n++, pos.setY(pos.getY() - 1)) {
            BlockState st = chunk.getBlockState(pos);
            if (st.isAir()) continue;
            MapColor mapColor = st.getMapColor(mc.level, pos);
            if (mapColor == MapColor.NONE) continue;
            color[s] = mapColor.col;
            height[s] = pos.getY();
            known[s] = true;
            return;
        }
    }

    /** The shaded ARGB of a cached column, or 0 when it isn't known. */
    private int sample(int x, int z) {
        int s = slot(x, z);
        if (!known[s] || keyX[s] != x || keyZ[s] != z) return 0;
        int c = color[s];
        // like a map: higher than the column to the north is lighter, lower is darker
        int n = slot(x, z - 1);
        if (known[n] && keyX[n] == x && keyZ[n] == z - 1) {
            double f = height[s] > height[n] ? 1.12 : height[s] < height[n] ? 0.82 : 1.0;
            int r = Math.min(255, (int) ((c >> 16 & 255) * f)), g = Math.min(255, (int) ((c >> 8 & 255) * f)), b = Math.min(255, (int) ((c & 255) * f));
            c = r << 16 | g << 8 | b;
        }
        return 0xFF000000 | c;
    }

    // ---- texture ----

    private double scale(int size) {
        return size / (2 * VIEW / settings.minimapZoom.get());
    }

    private void paint(Minecraft mc) {
        int size = settings.minimapSize.get() - 4;
        int corner = Math.max(0, settings.hudCorner.get() - 2);
        if (texture == null || texSize != size) {
            if (texture != null) mc.getTextureManager().release(ID);
            texture = new DynamicTexture(() -> "lazuli_minimap", size, size, true);
            mc.getTextureManager().register(ID, texture);
            texSize = size;
        }
        px = mc.player.getX();
        pz = mc.player.getZ();
        yaw = Math.toRadians(mc.player.getYRot());
        boolean rotate = settings.minimapRotate.get();
        double sc = scale(size), fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        int empty = Material.Scheme.of(LazuliConfig.pathColor.getRGB()).surfaceHigh();
        NativeImage img = texture.getPixels();
        for (int j = 0; j < size; j++) {
            int cut = j < corner ? Material.inset(j, corner) : j >= size - corner ? Material.inset(size - 1 - j, corner) : 0;
            for (int i = 0; i < size; i++) {
                if (i < cut || i >= size - cut) {
                    img.setPixel(i, j, 0);
                    continue;
                }
                double sx = (i + 0.5 - size / 2.0) / sc, sy = (j + 0.5 - size / 2.0) / sc;
                double wx = rotate ? px + sx * rx - sy * fx : px + sx;
                double wz = rotate ? pz + sx * rz - sy * fz : pz + sy;
                int c = sample((int) Math.floor(wx), (int) Math.floor(wz));
                img.setPixel(i, j, c == 0 ? empty : c);
            }
        }
        texture.upload();
        ready = true;
    }

    // ---- drawing ----

    void render(GuiGraphics g, Minecraft mc) {
        if (!ready || texture == null) return;
        int total = texSize + 4;
        int x0 = g.guiWidth() - total - 4, y0 = 4;
        Material.Scheme c = Material.Scheme.of(LazuliConfig.pathColor.getRGB());
        int r = settings.hudCorner.get();
        Material.rrect(g, x0, y0, x0 + total, y0 + total, r, c.surface());
        int mx = x0 + 2, my = y0 + 2;
        g.blit(RenderPipelines.GUI_TEXTURED, ID, mx, my, 0f, 0f, texSize, texSize, texSize, texSize);

        boolean rotate = settings.minimapRotate.get();
        double sc = scale(texSize), fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double half = texSize / 2.0;
        int corner = Math.max(0, r - 2);
        if (settings.minimapPath.get()) {
            PathingSession.Snapshot snap = session.snapshot();
            if (snap.state() == PathingSession.State.PATHING) drawPath(g, snap.path(), mx, my, half, sc, rotate, rx, rz, fx, fz, corner);
            Goal goal = snap.state() == PathingSession.State.PATHING ? snap.goal() : null;
            if (goal != null) drawGoal(g, goal, snap.path(), mx, my, half, sc, rotate, rx, rz, fx, fz, corner);
            for (Waypoints.Waypoint w : waypoints.get()) {
                double[] s = toScreen(w.x() + 0.5, w.z() + 0.5, half, sc, rotate, rx, rz, fx, fz);
                int sx = (int) Math.round(s[0]), sy = (int) Math.round(s[1]);
                if (!inside(sx, sy, texSize, corner, 3)) continue;
                int col = w.name().equals(Waypoints.DEATH) ? 0xFFFF5555 : 0xFFFFD040;
                g.fill(mx + sx - 2, my + sy - 2, mx + sx + 3, my + sy + 3, 0xFF000000);
                g.fill(mx + sx - 1, my + sy - 1, mx + sx + 2, my + sy + 2, col);
            }
        }
        if (rotate) {
            // north is wherever the map has turned it to
            double nx = Math.sin(yaw) * (half - 6), ny = Math.cos(yaw) * (half - 6);
            g.drawString(mc.font, "N", (int) Math.round(mx + half + nx) - 2, (int) Math.round(my + half + ny) - 4, c.onSurface(), true);
        }
        arrow(g, (int) Math.round(mx + half), (int) Math.round(my + half), rotate ? 0 : yaw + Math.PI, c);
    }

    /** Block position to pixels from the map's top left, given the way the map is turned. */
    private double[] toScreen(double wx, double wz, double half, double sc, boolean rotate, double rx, double rz, double fx, double fz) {
        double dx = wx - px, dz = wz - pz;
        double sx = rotate ? dx * rx + dz * rz : dx;
        double sy = rotate ? -(dx * fx + dz * fz) : dz;
        return new double[]{half + sx * sc, half + sy * sc};
    }

    /** Is a dot of the given radius at (sx, sy) wholly on the map, corners cut included? */
    private static boolean inside(int sx, int sy, int size, int corner, int rad) {
        if (sx < rad || sy < rad || sx > size - rad || sy > size - rad) return false;
        int row = Math.min(sy, size - 1 - sy);
        return row >= corner || (sx >= Material.inset(row, corner) + rad && sx <= size - Material.inset(row, corner) - rad);
    }

    private void drawPath(GuiGraphics g, List<Long> path, int mx, int my, double half, double sc, boolean rotate,
                          double rx, double rz, double fx, double fz, int corner) {
        int col = 0xFF000000 | LazuliConfig.pathColor.getRGB();
        int from = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) { // start at the node nearest the player
            long n = path.get(i);
            double dx = Pos.x(n) + 0.5 - px, dz = Pos.z(n) + 0.5 - pz, d = dx * dx + dz * dz;
            if (d < bd) {
                bd = d;
                from = i;
            }
        }
        double[] prev = null;
        for (int i = from; i < Math.min(path.size(), from + PATH_NODES); i++) {
            long n = path.get(i);
            double[] cur = toScreen(Pos.x(n) + 0.5, Pos.z(n) + 0.5, half, sc, rotate, rx, rz, fx, fz);
            if (prev != null) {
                int steps = (int) Math.max(1, Math.ceil(Math.hypot(cur[0] - prev[0], cur[1] - prev[1])));
                for (int k = 1; k <= steps; k++) {
                    int sx = (int) Math.round(prev[0] + (cur[0] - prev[0]) * k / steps), sy = (int) Math.round(prev[1] + (cur[1] - prev[1]) * k / steps);
                    if (inside(sx, sy, texSize, corner, 1)) g.fill(mx + sx - 1, my + sy - 1, mx + sx + 1, my + sy + 1, col);
                }
            }
            prev = cur;
        }
    }

    private void drawGoal(GuiGraphics g, Goal goal, List<Long> path, int mx, int my, double half, double sc, boolean rotate,
                          double rx, double rz, double fx, double fz, int corner) {
        double gx, gz;
        if (goal instanceof GoalBlock b) {
            gx = b.x() + 0.5;
            gz = b.z() + 0.5;
        } else if (goal instanceof GoalXZ z) {
            gx = z.x() + 0.5;
            gz = z.z() + 0.5;
        } else if (!path.isEmpty()) {
            long n = path.get(path.size() - 1);
            gx = Pos.x(n) + 0.5;
            gz = Pos.z(n) + 0.5;
        } else return;
        double[] s = toScreen(gx, gz, half, sc, rotate, rx, rz, fx, fz);
        double dx = s[0] - half, dy = s[1] - half, len = Math.hypot(dx, dy), lim = half - 5;
        if (len > lim) { // off the map: stick it to the edge in its direction
            s[0] = half + dx / len * lim;
            s[1] = half + dy / len * lim;
        }
        int sx = (int) Math.round(s[0]), sy = (int) Math.round(s[1]);
        int col = 0xFF000000 | LazuliConfig.goalColor.getRGB();
        g.fill(mx + sx - 3, my + sy - 3, mx + sx + 4, my + sy + 4, 0xFF000000);
        g.fill(mx + sx - 2, my + sy - 2, mx + sx + 3, my + sy + 3, col);
        g.fill(mx + sx - 1, my + sy - 1, mx + sx + 2, my + sy + 2, 0xFF000000);
    }

    /** The player: a small triangle pointing along {@code angle} (0 = up, clockwise), with a dark outline. */
    private static void arrow(GuiGraphics g, int cx, int cy, double angle, Material.Scheme c) {
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().rotate((float) angle);
        for (int k = 0; k < 9; k++) g.fill(-(k / 2) - 1, -5 + k, k / 2 + 2, -4 + k, 0xFF000000);
        for (int k = 0; k < 7; k++) g.fill(-(k / 2), -4 + k, k / 2 + 1, -3 + k, 0xFFFFFFFF);
        g.pose().popMatrix();
    }
}
