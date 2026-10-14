package dev.lazuli.core.command;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.goal.GoalXZ;
import dev.lazuli.core.goal.GoalYLevel;

import java.util.Arrays;

public final class GoalParser {
    /** {@code goal == null} means "clear the goal". */
    public record ParsedGoal(Goal goal, boolean fallback) {}

    public static final String USAGE = "Usage: goal [x y z | x z | y | clear] [true|false]";

    private GoalParser() {}

    public static ParsedGoal parse(String[] args, int px, int py, int pz) {
        boolean fallback = false;
        int n = args.length;
        if (n > 0 && (args[n - 1].equalsIgnoreCase("true") || args[n - 1].equalsIgnoreCase("false"))) {
            fallback = args[n - 1].equalsIgnoreCase("true");
            n--;
        }
        String[] a = Arrays.copyOf(args, n);
        if (n == 1 && a[0].equalsIgnoreCase("clear")) return new ParsedGoal(null, fallback);
        return switch (n) {
            case 0 -> new ParsedGoal(new GoalBlock(px, py, pz), fallback);
            case 1 -> new ParsedGoal(new GoalYLevel(coord(a[0], py)), fallback);
            case 2 -> new ParsedGoal(new GoalXZ(coord(a[0], px), coord(a[1], pz)), fallback);
            case 3 -> new ParsedGoal(new GoalBlock(coord(a[0], px), coord(a[1], py), coord(a[2], pz)), fallback);
            default -> throw new IllegalArgumentException(USAGE);
        };
    }

    private static int coord(String tok, int playerValue) {
        try {
            if (tok.startsWith("~")) {
                return playerValue + (tok.length() == 1 ? 0 : Integer.parseInt(tok.substring(1)));
            }
            return Integer.parseInt(tok);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a coordinate: " + tok);
        }
    }
}
