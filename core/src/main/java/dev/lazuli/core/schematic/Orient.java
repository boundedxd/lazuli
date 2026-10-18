package dev.lazuli.core.schematic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * How to place a block so it comes out turned the way a schematic has it, by vanilla's placement rules: which face of
 * the neighbour gets clicked (the direction from that neighbour to the new block), the hit height on a side face (top or
 * bottom half), and the way the player looks (horizontal facing, or the nearest of six). Only stairs, slabs, logs and
 * other pillars, doors, trapdoors and the common facing blocks are known; others have no rule and match by kind.
 */
public final class Orient {
    /** Same names and order as Minecraft's Direction. */
    public enum Dir {
        DOWN, UP, NORTH, SOUTH, WEST, EAST;

        public Dir opposite() {
            return switch (this) {
                case DOWN -> UP; case UP -> DOWN; case NORTH -> SOUTH; case SOUTH -> NORTH; case WEST -> EAST; case EAST -> WEST;
            };
        }

        public boolean horizontal() {
            return this != UP && this != DOWN;
        }

        public Dir clockWise() {
            return switch (this) {
                case NORTH -> EAST; case EAST -> SOUTH; case SOUTH -> WEST; case WEST -> NORTH; default -> this;
            };
        }

        public Dir counterClockWise() {
            return clockWise().opposite();
        }

        char axis() {
            return switch (this) { case DOWN, UP -> 'y'; case NORTH, SOUTH -> 'z'; default -> 'x'; };
        }

        static Dir of(String s) {
            for (Dir d : values()) if (d.name().equalsIgnoreCase(s)) return d;
            return null;
        }
    }

    public enum Half { ANY, TOP, BOTTOM }

    private static final Set<Dir> SIDES = EnumSet.of(Dir.NORTH, Dir.SOUTH, Dir.WEST, Dir.EAST);
    /** Hits this close to the middle of a side, or yaws this close to a quadrant edge, aren't trusted. */
    private static final double HIT_MARGIN = 0.05, YAW_MARGIN = 5;

    /**
     * One way of placing it: one of {@code faces} clicked, at {@code hit} height on a side face, with the player
     * looking {@code horizontal} (null: any) and nearest to {@code look} (null: any).
     */
    public record Option(Set<Dir> faces, Half hit, Dir horizontal, Dir look) {
        boolean ok(Dir face, double hitY, float yaw, float pitch) {
            if (!faces.contains(face)) return false;
            if (face.horizontal() && hit != Half.ANY && (hit == Half.TOP ? hitY <= 0.5 + HIT_MARGIN : hitY >= 0.5 - HIT_MARGIN)) return false;
            if (horizontal != null && Orient.horizontal(yaw) != horizontal) return false;
            return look == null || Orient.look(yaw, pitch) == look;
        }
    }

    /** The ways to place a block, and the properties those decide (the ones compared to see if it's right). */
    public record Rule(List<Option> options, Set<String> props, Map<String, String> want) {
        /** Clicking {@code face} at height {@code hitY} (0..1 within the block) with this rotation gives the state wanted. */
        public boolean ok(Dir face, double hitY, float yaw, float pitch) {
            for (Option o : options) if (o.ok(face, hitY, yaw, pitch)) return true;
            return false;
        }

        /** Some option can click {@code face}. */
        public boolean canClick(Dir face) {
            for (Option o : options) if (o.faces.contains(face)) return true;
            return false;
        }

        /** The placed block's properties {@code have} match the wanted ones this rule decides. */
        public boolean fits(Map<String, String> have) {
            for (String p : props) if (!want.get(p).equals(have.get(p))) return false;
            return true;
        }

        /** The block there now gets finished by placing another onto it (a single slab where a double is wanted). */
        public boolean placeOnto(Map<String, String> have) {
            return "double".equals(want.get("type")) && have.containsKey("type") && !"double".equals(have.get("type"));
        }

        /** Where to stand (side of the block) to look the way it needs, or null if any way will do. */
        public Dir standSide() {
            for (Option o : options) if (o.horizontal != null) return o.horizontal.opposite();
            for (Option o : options) if (o.look != null && o.look.horizontal()) return o.look.opposite();
            return null;
        }
    }

    private Orient() {}

    /** The rule for placing {@code id} with properties {@code want}, or null if its way round isn't known (or doesn't matter). */
    public static Rule rule(String id, Map<String, String> want) {
        String name = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        List<Option> opts = new ArrayList<>();
        Set<String> props = new java.util.LinkedHashSet<>();
        Dir facing = Dir.of(want.getOrDefault("facing", ""));
        String axis = want.get("axis");

        if (name.endsWith("_slab") && want.containsKey("type")) {
            props.add("type");
            switch (want.get("type")) {
                case "top" -> { opts.add(new Option(EnumSet.of(Dir.DOWN), Half.ANY, null, null)); opts.add(new Option(SIDES, Half.TOP, null, null)); }
                case "bottom" -> { opts.add(new Option(EnumSet.of(Dir.UP), Half.ANY, null, null)); opts.add(new Option(SIDES, Half.BOTTOM, null, null)); }
                default -> opts.add(new Option(EnumSet.allOf(Dir.class), Half.ANY, null, null));
            }
        } else if (name.endsWith("_stairs") && facing != null && facing.horizontal()) {
            props.add("facing");
            boolean top = "top".equals(want.get("half"));
            if (want.containsKey("half")) props.add("half");
            opts.add(new Option(EnumSet.of(top ? Dir.DOWN : Dir.UP), Half.ANY, facing, null));
            opts.add(new Option(SIDES, top ? Half.TOP : Half.BOTTOM, facing, null));
        } else if (name.endsWith("_trapdoor") && facing != null && facing.horizontal()) {
            props.add("facing");
            boolean top = "top".equals(want.get("half"));
            if (want.containsKey("half")) props.add("half");
            opts.add(new Option(EnumSet.of(facing), top ? Half.TOP : Half.BOTTOM, null, null));
            opts.add(new Option(EnumSet.of(top ? Dir.DOWN : Dir.UP), Half.ANY, facing.opposite(), null));
        } else if (axis != null && axis.length() == 1 && !want.containsKey("facing")) {
            // logs, stems, pillars, basalt, hay, chains, bone blocks...: the clicked face's axis
            props.add("axis");
            Set<Dir> faces = EnumSet.noneOf(Dir.class);
            for (Dir d : Dir.values()) if (d.axis() == axis.charAt(0)) faces.add(d);
            if (faces.isEmpty()) return null;
            opts.add(new Option(faces, Half.ANY, null, null));
        } else if (facing != null && !want.containsKey("face")) {
            props.add("facing");
            Option o = facingOption(name, facing);
            if (o == null) return null;
            opts.add(o);
        } else {
            return null;
        }
        return new Rule(List.copyOf(opts), Set.copyOf(props), Map.copyOf(want));
    }

    private static Option facingOption(String name, Dir facing) {
        Set<Dir> any = EnumSet.allOf(Dir.class);
        // the player's way: doors, gates, beds, campfires
        if (name.endsWith("_door") || name.endsWith("_fence_gate") || name.endsWith("_bed") || name.endsWith("campfire"))
            return facing.horizontal() ? new Option(any, Half.ANY, facing, null) : null;
        if (name.endsWith("anvil")) return facing.horizontal() ? new Option(any, Half.ANY, facing.counterClockWise(), null) : null;
        // where they look: observers face that way, pistons/dispensers/droppers/barrels/crafters back at the player
        if (name.equals("observer")) return new Option(any, Half.ANY, null, facing);
        if (name.endsWith("piston") || name.equals("dispenser") || name.equals("dropper") || name.equals("barrel")
            || name.equals("crafter") || name.endsWith("command_block"))
            return new Option(any, Half.ANY, null, facing.opposite());
        // the clicked face
        if (name.equals("end_rod") || name.equals("lightning_rod") || name.endsWith("amethyst_bud") || name.equals("amethyst_cluster")
            || name.endsWith("wall_torch") || name.equals("ladder"))
            return new Option(EnumSet.of(facing), Half.ANY, null, null);
        if (name.equals("hopper"))
            return new Option(facing == Dir.DOWN ? EnumSet.of(Dir.UP, Dir.DOWN) : EnumSet.of(facing.opposite()), Half.ANY, null, null);
        // furnaces, chests, pumpkins, glazed terracotta, repeaters, lecterns...: facing the player
        if (facing.horizontal() && !name.endsWith("_sign") && !name.endsWith("_banner") && !name.endsWith("_head")
            && !name.endsWith("_skull") && !name.contains("grindstone") && !name.contains("bell"))
            return new Option(any, Half.ANY, facing.opposite(), null);
        return null;
    }

    /** Vanilla's horizontal facing for a yaw, or null within a few degrees of a quadrant edge. */
    public static Dir horizontal(float yaw) {
        double q = yaw / 90.0 + 0.5;
        double frac = q - Math.floor(q);
        if (Math.min(frac, 1 - frac) * 90 < YAW_MARGIN) return null;
        return switch ((int) Math.floor(q) & 3) {
            case 0 -> Dir.SOUTH; case 1 -> Dir.WEST; case 2 -> Dir.NORTH; default -> Dir.EAST;
        };
    }

    /** The nearest of the six directions to where the player looks (vanilla's nearest looking direction). */
    public static Dir look(float yaw, float pitch) {
        double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
        double x = -Math.sin(y) * Math.cos(p), up = -Math.sin(p), z = Math.cos(y) * Math.cos(p);
        double ax = Math.abs(x), ay = Math.abs(up), az = Math.abs(z);
        if (ay >= ax && ay >= az) return up > 0 ? Dir.UP : Dir.DOWN;
        if (ax >= az) return x > 0 ? Dir.EAST : Dir.WEST;
        return z > 0 ? Dir.SOUTH : Dir.NORTH;
    }
}
