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
            case "mine" -> mine(args);
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
        ctx.reply(Chat.usage(p + "mine [quantity] <block> [block...]", "find and mine blocks, e.g. " + p + "mine 16 diamond_ore"));
        ctx.reply(Chat.usage(p + "stop", "stop pathing and mining"));
        ctx.reply(Chat.usage(p + "set <name> [value]", "show or change a setting"));
        ctx.reply(Chat.usage(p + "settings", "list every setting"));
        ctx.reply(Chat.usage(p + "config", "open the settings screen"));
        ctx.reply(Chat.usage(p + "record start <name> | stop", "record a movement trace"));
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
