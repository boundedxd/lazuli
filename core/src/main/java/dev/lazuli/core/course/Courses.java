package dev.lazuli.core.course;

import java.util.ArrayList;
import java.util.List;

import static dev.lazuli.core.course.Course.Material.*;

/**
 * The test courses, each in its own lane ({@link #LANE} blocks apart along z). Everything floats: there is nothing
 * under a gap but a fall far deeper than any safe drop, so a bridge course can only be crossed by bridging.
 */
public final class Courses {
    public static final int LANE = 24;
    /** Every course fits inside x -8..70, y -16..16, z -8..15 of its lane. */
    public static final int MIN_X = -8, MAX_X = 70, MIN_Y = -16, MAX_Y = 16, MIN_Z = -8, MAX_Z = 15;

    private Courses() {}

    public static List<Course> all() {
        List<Course> out = new ArrayList<>();
        Course.Builder b;

        b = new Course.Builder().ground(-2, 60);
        out.add(course("sprint", "60 blocks of flat ground: sprint-jump all the way", b, 0, 1, 0, 58, 1, 0, 1, false, false));

        b = new Course.Builder().ground(-2, 4).ground(6, 8).ground(11, 13).ground(17, 19)
            .ground(21, 23).ground(25, 27).fill(25, 1, -2, 27, 1, 2, STONE) // 1-gap up onto a step
            .ground(30, 35)                                                    // 2-gap back down
            .ground(37, 41, 2, 4)                                              // 1-gap diagonally
            .ground(45, 52, 2, 4);                                             // 3-gap
        out.add(course("parkour", "1, 2 and 3 block gaps, a jump up, a jump down, a diagonal: no building", b,
            0, 1, 0, 50, 1, 3, 1, false, false));

        b = new Course.Builder().ground(-2, 40).fill(4, 3, -2, 36, 3, 2, STONE);
        out.add(course("headhitter", "a 2-high corridor: sprint-jumps that bonk the ceiling", b, 0, 1, 0, 38, 1, 0, 1, false, false));

        b = new Course.Builder().ground(-2, 2).ground(14, 18);
        out.add(course("bridge", "11 blocks of void: bridge straight across", b, 0, 1, 0, 16, 1, 0, 1, false, true));

        b = new Course.Builder().ground(-2, 2, -2, 2).ground(8, 12, 9, 13);
        out.add(course("bridge-corner", "an island off to the side: bridge with a turn", b, 0, 1, 0, 10, 1, 11, 1, false, true));

        b = new Course.Builder().ground(-2, 2).fill(8, 3, -1, 10, 3, 1, STONE);
        out.add(course("bridge-up", "an island 3 blocks higher across the void: bridge and tower", b, 0, 1, 0, 9, 4, 0, 1, false, true));

        b = new Course.Builder().ground(-2, 14).fill(5, 1, -2, 14, 7, 2, STONE);
        out.add(course("tower", "a 7-high cliff: pillar up beside it", b, 0, 1, 0, 10, 8, 0, 1, false, true));

        b = new Course.Builder().ground(-2, 4);
        out.add(course("tower-air", "a goal in mid-air 5 blocks up: pillar straight to it", b, 0, 1, 0, 0, 6, 0, 1, false, true, false));

        b = new Course.Builder().ground(-2, 8).fill(0, -2, 0, 0, 0, 0, AIR);
        out.add(course("pit", "start at the bottom of a 3-deep shaft: tower out", b, 0, -2, 0, 5, 1, 0, -2, false, true));

        b = new Course.Builder().ground(-2, 30).fill(5, 1, -2, 24, 6, 2, STONE);
        out.add(course("tunnel", "20 blocks of stone in the way: tunnel through", b, 0, 1, 0, 28, 1, 0, 1, true, false));

        b = new Course.Builder().ground(-2, 8).fill(-2, -12, -2, 8, -4, 2, STONE);
        out.add(course("mine-down", "a goal 9 blocks down inside stone: mine straight down", b, 0, 1, 0, 4, -8, 0, -8, true, false));

        b = new Course.Builder().ground(-2, 22).fill(5, 1, -2, 16, 2, 2, STONE).fill(5, 3, -2, 16, 5, 2, GRAVEL);
        out.add(course("gravel", "stone under gravel: never mine what holds gravel up", b, 0, 1, 0, 20, 1, 0, 1, true, true));

        b = new Course.Builder().ground(-2, 30, -4, 4).fill(6, 0, -4, 24, 0, 2, LAVA);
        out.add(course("lava", "a lava lake with a 2-wide path along its edge", b, 0, 1, 3, 28, 1, 0, 1, false, false));

        b = new Course.Builder().ground(-2, 22);
        for (int i = 0; i < 6; i++) {
            int x = 4 + 2 * i;
            if (i > 0) b.fill(x, 1, -2, x + 1, i, 2, STONE);
            b.fill(x, i + 1, -2, x, i + 1, 2, SLAB).fill(x + 1, i + 1, -2, x + 1, i + 1, 2, STONE);
        }
        b.fill(16, 1, -2, 22, 6, 2, STONE);
        out.add(course("stairs", "slab and block steps up 6 blocks", b, 0, 1, 0, 19, 7, 0, 1, false, false));

        b = new Course.Builder().ground(-2, 24).fill(8, -2, -2, 14, 0, 2, WATER);
        out.add(course("water", "a 7-wide water channel", b, 0, 1, 0, 20, 1, 0, 1, false, true));

        b = new Course.Builder().ground(-2, 40).fill(-2, 0, -2, 40, 0, 2, ICE).fill(18, -3, -2, 19, 0, 2, AIR);
        out.add(course("ice", "sprinting on ice, with a 2-gap on it", b, 0, 1, 0, 36, 1, 0, 1, false, false));

        b = new Course.Builder().ground(-2, 18, -2, 12).fill(4, 1, -2, 4, 3, 9, STONE).fill(8, 1, 1, 8, 3, 12, STONE)
            .fill(12, 1, -2, 12, 3, 9, STONE);
        out.add(course("maze", "walls to walk around", b, 0, 1, 0, 16, 1, 0, 1, false, false));

        b = new Course.Builder().ground(-6, 6, -4, 4).fill(-4, 1, -3, 4, 6, 3, STONE).fill(-1, 1, -1, 1, 2, 1, AIR);
        out.add(course("dig-out", "sealed in a room under 4 blocks of stone: dig out to the top", b, 0, 1, 0, 3, 7, 0, 1, true, true));
        return out;
    }

    private static Course course(String name, String what, Course.Builder b, int sx, int sy, int sz, int gx, int gy, int gz,
                                 int lowest, boolean needsBreak, boolean needsPlace) {
        return course(name, what, b, sx, sy, sz, gx, gy, gz, lowest, needsBreak, needsPlace, true);
    }

    /** @param marker put a gold block under the goal (not for goals in mid-air) */
    private static Course course(String name, String what, Course.Builder b, int sx, int sy, int sz, int gx, int gy, int gz,
                                 int lowest, boolean needsBreak, boolean needsPlace, boolean marker) {
        if (marker) b.set(gx, gy - 1, gz, GOAL);
        return new Course(name, what, List.copyOf(b.fills), new int[]{sx, sy, sz}, new int[]{gx, gy, gz}, lowest,
            needsBreak, needsPlace);
    }

    /** The course with this name or 1-based number, or null. */
    public static Course find(String key) {
        List<Course> all = all();
        try {
            int n = Integer.parseInt(key);
            return n >= 1 && n <= all.size() ? all.get(n - 1) : null;
        } catch (NumberFormatException e) {
            for (Course c : all) if (c.name().equalsIgnoreCase(key)) return c;
            return null;
        }
    }

    /** Lane index of a course (its z offset is index * LANE). */
    public static int lane(Course c) {
        List<Course> all = all();
        for (int i = 0; i < all.size(); i++) if (all.get(i).name().equals(c.name())) return i;
        return -1;
    }
}
