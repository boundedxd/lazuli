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

import java.util.Arrays;
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
            reply("Recording isn't available here");
        }

        default void recordStop() {
            reply("Recording isn't available here");
        }

        /** Opens the settings screen. */
        default void openConfig() {
            reply("No config screen here; use set <name> <value>");
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
            ctx.reply(e.getMessage());
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
            case "stop", "cancel" -> ctx.session().stop();
            case "set" -> set(args);
            case "settings" -> {
                for (Setting<?> s : ctx.settings().all()) ctx.reply(s.name() + " = " + s.format());
            }
            case "record" -> record(args);
            case "config" -> ctx.openConfig();
            case "help" -> ctx.reply("Commands: goal [x y z | x z | y | clear] [true|false], path, goto <goal>, stop, "
                + "set <name> [value], settings, config, record start <name> | stop");
            default -> ctx.reply("Unknown command: " + cmd + ". Try " + prefix + "help");
        }
    }

    private void record(String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            ctx.recordStop();
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start") && args[1].matches("[A-Za-z0-9_-]+")) {
            ctx.recordStart(args[1]);
        } else {
            ctx.reply("Usage: record start <name> | record stop  (name: letters, digits, _ and -)");
        }
    }

    private boolean setGoal(String[] args) {
        int[] f = ctx.playerFeet();
        GoalParser.ParsedGoal parsed = GoalParser.parse(args, f[0], f[1], f[2]);
        if (parsed.goal() == null) {
            goal = null;
            ctx.reply("Goal cleared");
            return false;
        }
        if (parsed.goal() instanceof GoalBlock b && !parsed.fallback() && knownAndUnstandable(b)) {
            ctx.reply("Can't stand at " + b.x() + " " + b.y() + " " + b.z() + " (use true to allow nearest safe spot)");
            return false;
        }
        goal = parsed.goal();
        fallback = parsed.fallback();
        ctx.reply("Goal set: " + goal.describe() + (fallback ? " (fallback on)" : ""));
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
            ctx.reply("No goal set");
            return;
        }
        int[] f = ctx.playerFeet();
        ctx.session().start(goal, fallback, Pos.pack(f[0], f[1], f[2]));
    }

    private void set(String[] args) {
        if (args.length == 0 || args.length > 2) {
            ctx.reply("Usage: set <name> [value]");
            return;
        }
        var found = ctx.settings().byName(args[0]);
        if (found.isEmpty()) {
            ctx.reply("Unknown setting: " + args[0]);
            return;
        }
        Setting<?> s = found.get();
        if (args.length == 2) {
            s.parse(args[1]);
            ctx.settingsChanged();
        }
        ctx.reply(s.name() + " = " + s.format());
    }
}
