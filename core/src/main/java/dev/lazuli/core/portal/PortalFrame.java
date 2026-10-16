package dev.lazuli.core.portal;

import dev.lazuli.core.move.Standing;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;

import java.util.ArrayList;
import java.util.List;

/**
 * The smallest nether portal: a 4 wide, 5 tall frame standing on the ground, obsidian all round except the corners
 * (any block will do there, and they hold the sides up while building), a 2x3 opening inside. {@code (x, y, z)} is
 * the bottom corner; the frame runs along +X ({@code alongX}) or +Z.
 */
public record PortalFrame(int x, int y, int z, boolean alongX) {
    public static final int WIDTH = 4, HEIGHT = 5;
    /** Obsidian a portal takes (corners not counted). */
    public static final int OBSIDIAN = 10;

    public enum Part { OBSIDIAN, CORNER, INSIDE }

    /** One cell of the frame: where, and what goes there. */
    public record Cell(int x, int y, int z, Part part) {}

    /** Where to stand while building: in front of the opening, all of it within reach. */
    public record Site(PortalFrame frame, int standX, int standY, int standZ) {}

    public Cell cell(int i, int j) {
        boolean corner = (i == 0 || i == WIDTH - 1) && (j == 0 || j == HEIGHT - 1);
        boolean inside = i > 0 && i < WIDTH - 1 && j > 0 && j < HEIGHT - 1;
        Part part = corner ? Part.CORNER : inside ? Part.INSIDE : Part.OBSIDIAN;
        return alongX ? new Cell(x + i, y + j, z, part) : new Cell(x, y + j, z + i, part);
    }

    /** Every cell, bottom row first, each row from one side to the other: the order to build in. */
    public List<Cell> cells() {
        List<Cell> out = new ArrayList<>(WIDTH * HEIGHT);
        for (int j = 0; j < HEIGHT; j++)
            for (int i = 0; i < WIDTH; i++) out.add(cell(i, j));
        return out;
    }

    /** The bottom obsidian whose top a flint and steel lights the portal from. */
    public Cell ignite() {
        return cell(1, 0);
    }

    /** The bottom of the opening: walking in there goes through. */
    public Cell entrance() {
        return cell(1, 1);
    }

    /**
     * The best place for a portal near the player standing at (px, py, pz): on solid ground, in a space that's open or
     * only needs a few blocks broken (no water or lava in it), with somewhere to stand in front. Null if none within
     * a few blocks.
     */
    public static Site find(BlockView w, int px, int py, int pz) {
        Site best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    for (int a = 0; a < 2; a++) {
                        PortalFrame f = new PortalFrame(px + dx, py + dy, pz + dz, a == 0);
                        int breaks = breaks(w, f);
                        if (breaks < 0) continue;
                        for (int side = -2; side <= 2; side += 4) {
                            // in front of the middle of the opening, two blocks out
                            Cell mid = f.cell(1, 0);
                            int sx = f.alongX ? mid.x() : mid.x() + side, sz = f.alongX ? mid.z() + side : mid.z();
                            if (!Standing.standable(w, sx, f.y, sz) || w.get(sx, f.y - 1, sz) == BlockKind.UNKNOWN) continue;
                            double score = breaks * 4 + Math.abs(sx - px) + Math.abs(sz - pz) + Math.abs(f.y - py) * 2;
                            if (score < bestScore) {
                                bestScore = score;
                                best = new Site(f, sx, f.y, sz);
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Blocks to break before building {@code f} there, or -1 if it can't go there. */
    static int breaks(BlockView w, PortalFrame f) {
        int n = 0;
        for (int i = 0; i < WIDTH; i++) {
            Cell ground = f.cell(i, 0);
            if (w.get(ground.x(), ground.y() - 1, ground.z()) != BlockKind.SOLID) return -1;
        }
        for (Cell c : f.cells()) {
            BlockKind k = w.get(c.x(), c.y(), c.z());
            switch (k) {
                case AIR -> { }
                case SOLID -> {
                    if (c.part() != Part.CORNER) n++; // a corner can stay whatever solid block it is
                }
                case OBSTACLE -> n++;
                default -> {
                    return -1; // water, lava, unknown
                }
            }
        }
        return n;
    }
}
