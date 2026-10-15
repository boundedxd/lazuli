package dev.lazuli.core.command;

import dev.lazuli.core.goal.Goal;
import dev.lazuli.core.goal.GoalBlock;
import dev.lazuli.core.move.MoveGraph;
import dev.lazuli.core.session.PathingSession;
import dev.lazuli.core.settings.Setting;
import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.world.BlockKind;
import dev.lazuli.core.world.BlockView;
import dev.lazuli.core.world.Pos;

import dev.lazuli.core.chat.Chat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Baritone-style chat commands. Messages not starting with the configured prefix are left alone. */
public final class CommandHandler {
    public interface Context {
        /** Player feet block {x, y, z}. */
        int[] playerFeet();

        BlockView world();

        PathingSession session();

        Settings settings();

        void reply(String msg);

        void settingsChanged();

        /** Starts recording a golden trace; the reply says where it will be saved. */
        default void recordStart(String name) {
            reply(Chat.err("Recording isn't available here"));
        }

        default void recordStop() {
            reply(Chat.err("Recording isn't available here"));
        }

        /** Arms #click: the next left-click on a block sets the goal (see {@link CommandHandler#setGoal(Goal)}). */
        default void startClick() {
            reply(Chat.err("Clicking isn't available here"));
        }

        /** Opens the settings screen. */
        default void openConfig() {
            reply(Chat.err("No config screen here; use set <name> <value>"));
        }

        /**
         * Starts mining: find, walk to and break these blocks (ids like {@code diamond_ore} or
         * {@code minecraft:diamond_ore}) until {@code quantity} are broken, or every one in reach when it's 0.
         */
        default void mine(int quantity, List<String> blocks) {
            reply(Chat.err("Mining isn't available here"));
        }

        /** Stops whatever process (mining...) is running on top of pathing. */
        default void stopProcesses() {}

        /**
         * Follows entities: {@code mode} is players, entities, player (names in {@code args}) or entity (type ids in
         * {@code args}).
         */
        default void follow(String mode, List<String> args) {
            reply(Chat.err("Following isn't available here"));
        }

        /** Explores outwards from {x, z}, or from the player when null. */
        default void explore(int[] origin) {
            reply(Chat.err("Exploring isn't available here"));
        }

        /** Digs a straight tunnel the way the player faces; depth 0 means no end. */
        default void tunnel(int height, int width, int depth) {
            reply(Chat.err("Tunnelling isn't available here"));
        }

        /** Farms around the player; range 0 means the farmRange setting. */
        default void farm(int range) {
            reply(Chat.err("Farming isn't available here"));
        }

        /** Throws out {@code count} of an item (an id like {@code dirt}, or "all" for everything); negative: all of it. */
        default void drop(String item, int count) {
            reply(Chat.err("Dropping isn't available here"));
        }

        /**
         * Moves items between the inventory and a container: {@code at} {x, y, z}, or null for the nearest storage.
         * {@code item} is an id or "all"; a negative count means all of it.
         */
        default void container(boolean deposit, String item, int count, int[] at) {
            reply(Chat.err("Containers aren't available here"));
        }

        /** The block the camera is in {x, y, z}: the freecam's when it's on, otherwise the player's feet. */
        default int[] cameraFeet() {
            return playerFeet();
        }

        /** Detaches the camera to fly around (or puts it back). */
        default void toggleFreecam() {
            reply(Chat.err("Freecam isn't available here"));
        }
    }

    private final Context ctx;
    private Goal goal;
    private boolean fallback;

    public CommandHandler(Context ctx) {
        this.ctx = ctx;
    }

    public Goal goal() {
        return goal;
    }

    /** Returns true if the message was a command (and must not be sent to the server). */
    public boolean handle(String message) {
        String prefix = ctx.settings().prefix.get();
        if (!message.startsWith(prefix)) return false;
        String body = message.substring(prefix.length()).trim();
        String[] parts = body.isEmpty() ? new String[0] : body.split("\\s+");
        String cmd = parts.length == 0 ? "help" : parts[0].toLowerCase(Locale.ROOT);
        String[] args = Arrays.copyOfRange(parts, Math.min(1, parts.length), parts.length);
        try {
            run(cmd, args, prefix);
        } catch (IllegalArgumentException e) {
            ctx.reply(Chat.err(e.getMessage()));
        }
        return true;
    }

    private void run(String cmd, String[] args, String prefix) {
        switch (cmd) {
            case "goal" -> setGoal(args);
            case "path" -> startPath();
            case "goto" -> {
                if (setGoal(args)) startPath();
            }
            case "stop", "cancel" -> {
                ctx.stopProcesses();
                ctx.session().stop();
            }
            case "set" -> set(args);
            case "settings" -> {
                ctx.reply(Chat.info("Settings ({}set <name> <value> to change):", prefix));
                for (Setting<?> s : ctx.settings().all()) ctx.reply(Chat.pair(s.name(), s.get()));
            }
            case "record" -> record(args);
            case "config" -> ctx.openConfig();
            case "click" -> ctx.startClick();
            case "come" -> {
                int[] c = ctx.cameraFeet();
                ctx.stopProcesses();
                goTo(new GoalBlock(c[0], c[1], c[2]), true);
            }
            case "freecam" -> ctx.toggleFreecam();
            case "mine" -> mine(args);
            case "follow" -> follow(args);
            case "explore" -> {
                if (args.length == 0) ctx.explore(null);
                else if (args.length == 2) ctx.explore(new int[]{integer(args[0]), integer(args[1])});
                else ctx.reply(Chat.err("Usage: explore [x z]"));
            }
            case "tunnel" -> {
                if (args.length == 0) ctx.tunnel(2, 1, 0);
                else if (args.length == 3) {
                    int h = integer(args[0]), w = integer(args[1]), d = integer(args[2]);
                    if (h < 1 || w < 1 || d < 0 || h > 8 || w > 9) ctx.reply(Chat.err("Height 1-8, width 1-9, depth 0 or more"));
                    else ctx.tunnel(h, w, d);
                } else ctx.reply(Chat.err("Usage: tunnel [height width depth]"));
            }
            case "farm" -> {
                if (args.length == 0) ctx.farm(0);
                else if (args.length == 1) ctx.farm(Math.max(1, integer(args[0])));
                else ctx.reply(Chat.err("Usage: farm [range]"));
            }
            case "drop" -> {
                if (args.length == 1) ctx.drop(args[0].toLowerCase(Locale.ROOT), -1);
                else if (args.length == 2) ctx.drop(args[0].toLowerCase(Locale.ROOT), Math.max(1, integer(args[1])));
                else ctx.reply(Chat.err("Usage: drop <item|all> [count]"));
            }
            case "deposit", "withdraw" -> container(cmd.equals("deposit"), args);
            case "help" -> help(prefix);
            default -> ctx.reply(Chat.err("Unknown command: {}. Try {}help", cmd, prefix));
        }
    }

    private void help(String p) {
        ctx.reply(Chat.info("Lazuli commands:"));
        ctx.reply(Chat.usage(p + "goal [x y z | x z | y | clear] [true|false]", "set the goal (true: fall back to the nearest safe spot)"));
        ctx.reply(Chat.usage(p + "path", "go to the goal"));
        ctx.reply(Chat.usage(p + "goto <goal>", "set the goal and go"));
        ctx.reply(Chat.usage(p + "click", "left-click a block to make it the goal"));
        ctx.reply(Chat.usage(p + "come", "come to the camera (in freecam: wherever it is)"));
        ctx.reply(Chat.usage(p + "freecam", "fly the camera around; left-click a block to go there"));
        ctx.reply(Chat.usage(p + "mine [quantity] <block> [block...]", "find and mine blocks, e.g. " + p + "mine 16 diamond_ore"));
        ctx.reply(Chat.usage(p + "follow players | entities | player <name...> | entity <type...>", "keep close to someone"));
        ctx.reply(Chat.usage(p + "explore [x z]", "walk into chunks never loaded, spiralling out"));
        ctx.reply(Chat.usage(p + "tunnel [height width depth]", "dig straight ahead (default 2x1, no end)"));
        ctx.reply(Chat.usage(p + "farm [range]", "harvest and replant crops around here"));
        ctx.reply(Chat.usage(p + "drop <item|all> [count]", "throw items out of the inventory (all of them without a count)"));
        ctx.reply(Chat.usage(p + "deposit <item|all> [count] [x y z]", "put items into a container (the nearest chest, barrel...)"));
        ctx.reply(Chat.usage(p + "withdraw <item|all> [count] [x y z]", "take items out of a container"));
        ctx.reply(Chat.usage(p + "stop", "stop pathing and whatever it's doing"));
        ctx.reply(Chat.usage(p + "set <name> [value]", "show or change a setting"));
        ctx.reply(Chat.usage(p + "settings", "list every setting"));
        ctx.reply(Chat.usage(p + "config", "open the settings screen"));
        ctx.reply(Chat.usage(p + "record start <name> | stop", "record a movement trace"));
    }

    /** follow players | entities | player <name...> | entity <type...>; a bare name means that player. */
    private void follow(String[] args) {
        if (args.length == 0) {
            ctx.reply(Chat.err("Usage: follow players | entities | player <name...> | entity <type...>"));
            return;
        }
        String m = args[0].toLowerCase(Locale.ROOT);
        List<String> rest = List.of(Arrays.copyOfRange(args, 1, args.length));
        switch (m) {
            case "players", "entities" -> ctx.follow(m, List.of());
            case "player", "entity" -> {
                if (rest.isEmpty()) ctx.reply(Chat.err("Usage: follow {} <{}...>", m, m.equals("player") ? "name" : "type"));
                else ctx.follow(m, rest.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
            }
            default -> ctx.follow("player", List.of(args)); // follow Steve
        }
    }

    /** deposit|withdraw <item|all> [count] [x y z]; coordinates may be relative (~). */
    private void container(boolean deposit, String[] args) {
        if (args.length != 1 && args.length != 2 && args.length != 4 && args.length != 5) {
            ctx.reply(Chat.err("Usage: {} <item|all> [count] [x y z]", deposit ? "deposit" : "withdraw"));
            return;
        }
        int count = args.length == 2 || args.length == 5 ? Math.max(1, integer(args[1])) : -1;
        int[] at = null;
        if (args.length >= 4) {
            int[] f = ctx.playerFeet();
            int o = args.length - 3;
            at = new int[]{coord(args[o], f[0]), coord(args[o + 1], f[1]), coord(args[o + 2], f[2])};
        }
        ctx.container(deposit, args[0].toLowerCase(Locale.ROOT), count, at);
    }

    /** A coordinate: a number, or ~ / ~n relative to {@code here}. */
    private static int coord(String s, int here) {
        if (s.startsWith("~")) return here + (s.length() == 1 ? 0 : integer(s.substring(1)));
        return integer(s);
    }

    private static int integer(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: " + s);
        }
    }

    /** Baritone's form: mine [quantity] <block> [block...]. */
    private void mine(String[] args) {
        int quantity = 0;
        int from = 0;
        if (args.length > 0 && args[0].matches("\\d+")) {
            quantity = Integer.parseInt(args[0]);
            from = 1;
        }
        if (from >= args.length) {
            ctx.reply(Chat.err("Usage: mine [quantity] <block> [block...]"));
            return;
        }
        List<String> blocks = new ArrayList<>();
        for (int i = from; i < args.length; i++) blocks.add(args[i].toLowerCase(Locale.ROOT));
        ctx.mine(quantity, blocks);
    }

    private void record(String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            ctx.recordStop();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start") && args[1].matches("[A-Za-z0-9_-]+")) {
            ctx.recordStart(args[1]);
        } else {
            ctx.reply(Chat.err("Usage: record start <name> | record stop  (name: letters, digits, _ and -)"));
        }
    }

    /** Sets the goal and goes (freecam clicks, #come); {@code fallback}: the nearest safe spot will do. */
    public void goTo(Goal g, boolean fallback) {
        goal = g;
        this.fallback = fallback;
        ctx.reply(Chat.ok("Going to {}", goal.describe()));
        startPath();
    }

    /** Sets the goal directly (from #click). */
    public void setGoal(Goal g) {
        goal = g;
        fallback = false;
        ctx.reply(Chat.ok("Goal set: {}", goal.describe()));
    }

    private boolean setGoal(String[] args) {
        int[] f = ctx.playerFeet();
        GoalParser.ParsedGoal parsed = GoalParser.parse(args, f[0], f[1], f[2]);
        if (parsed.goal() == null) {
            goal = null;
            ctx.reply(Chat.ok("Goal cleared"));
            return false;
        }
        if (parsed.goal() instanceof GoalBlock b && !parsed.fallback() && knownAndUnstandable(b)) {
            ctx.reply(Chat.err("Can't stand at {} (use true to allow the nearest safe spot)", b.x() + " " + b.y() + " " + b.z()));
            return false;
        }
        goal = parsed.goal();
        fallback = parsed.fallback();
        ctx.reply(Chat.ok("Goal set: {}" + (fallback ? " (fallback on)" : ""), goal.describe()));
        return true;
    }

    private boolean knownAndUnstandable(GoalBlock b) {
        BlockView w = ctx.world();
        // breaking and placing can make almost any spot standable; the planner decides those
        Settings s = ctx.settings();
        if (s.allowBreak.get() || s.allowPlace.get()) {
            return w.get(b.x(), b.y(), b.z()) == BlockKind.DANGER || w.get(b.x(), b.y() + 1, b.z()) == BlockKind.DANGER;
        }
        for (int dy = -1; dy <= 1; dy++) {
            if (w.get(b.x(), b.y() + dy, b.z()) == BlockKind.UNKNOWN) return false;
        }
        return !MoveGraph.isStandable(w, b.x(), b.y(), b.z());
    }

    private void startPath() {
        if (goal == null) {
            ctx.reply(Chat.err("No goal set"));
            return;
        }
        int[] f = ctx.playerFeet();
        ctx.session().start(goal, fallback, Pos.pack(f[0], f[1], f[2]));
    }

    private void set(String[] args) {
        if (args.length == 0 || args.length > 2) {
            ctx.reply(Chat.err("Usage: set <name> [value]"));
            return;
        }
        var found = ctx.settings().byName(args[0]);
        if (found.isEmpty()) {
            ctx.reply(Chat.err("Unknown setting: {}", args[0]));
            return;
        }
        Setting<?> s = found.get();
        if (args.length == 2) {
            s.parse(args[1]);
            ctx.settingsChanged();
        }
        ctx.reply(Chat.pair(s.name(), s.get()));
    }
}
