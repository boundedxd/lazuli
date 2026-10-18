package dev.lazuli.mod.config;

import dev.lazuli.core.settings.Setting;
import dev.lazuli.core.task.Cond;
import dev.lazuli.core.task.CondForm;
import dev.lazuli.core.task.Forms;
import dev.lazuli.core.task.Routine;
import dev.lazuli.core.task.RoutineModel;
import dev.lazuli.core.trade.TradeForm;
import dev.lazuli.core.task.Routines;
import dev.lazuli.core.task.Step;
import main.walksy.lib.api.config.option.Option;
import main.walksy.lib.api.config.option.OptionDescription;
import main.walksy.lib.api.config.option.OptionGroup;
import main.walksy.lib.api.config.option.Options;
import main.walksy.lib.api.config.value.ListEntries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * The routine editor page: the steps as a list (pick a command to add one), each step opening an editor with real
 * widgets (command, item, count, coordinates, retries, timeout, on-fail, conditions, per-step settings). The editor
 * edits {@link Step} records and writes them back as the routine's text lines, so the file stays readable and
 * {@code #routine} commands see the same thing.
 */
public final class RoutineEditor {
    private RoutineEditor() {
    }

    // ---- hooks the client sets ---------------------------------------------------------------------------------

    /** What the running routine is doing (null: none). */
    public static volatile Supplier<String> status = () -> null;
    /** Says something in chat. */
    public static volatile Consumer<String> reply = m -> {};
    /** The player's feet (x, y, z), null when not in a world. */
    public static volatile Supplier<int[]> playerPos = () -> null;
    /** Names of saved targets (for the target pickers). */
    public static volatile Supplier<List<String>> targets = List::of;

    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, Integer> TIMES = new ConcurrentHashMap<>();

    // ---- enums shown as dropdowns --------------------------------------------------------------------------------

    /** Every command the picker knows, plus "other" for ones typed by hand. Kept equal to {@link Forms#words()}. */
    public enum Cmd {
        GOTO, FLY, MINE, GETITEM, CRAFT, SMELT, BREW, HUNT, SMITH, ENCHANT, BUCKET, DEPOSIT, WITHDRAW, DROP, FIND, TRADE,
        REPAIR, COMBINE, FARM, FISH, SORT, TUNNEL, EXPLORE, FOLLOW, BREED, BUILD, PORTAL, STRONGHOLD, SLEEP, COME, BACK,
        STOP, SET, WAIT, SAY, NOTIFY, LABEL, JUMP, SKIP, END, OTHER;

        String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Cmd of(String word) {
            for (Cmd c : values()) if (c != OTHER && c.word().equals(word)) return c;
            return OTHER;
        }
    }

    public enum Unit {
        SECONDS(20), MINUTES(1200), HOURS(72000);

        final int ticks;

        Unit(int ticks) {
            this.ticks = ticks;
        }

        static Unit best(int ticks) {
            if (ticks > 0 && ticks % HOURS.ticks == 0) return HOURS;
            if (ticks > 0 && ticks % MINUTES.ticks == 0) return MINUTES;
            return SECONDS;
        }
    }

    public enum Compare {
        AT_LEAST(">="), MORE_THAN(">"), AT_MOST("<="), LESS_THAN("<"), EXACTLY("=="), NOT_EQUAL("!=");

        final String op;

        Compare(String op) {
            this.op = op;
        }

        static Compare of(String op) {
            for (Compare c : values()) if (c.op.equals(op)) return c;
            return AT_LEAST;
        }
    }

    public enum TimeOfDay { DAY, NIGHT }

    public enum Dim { OVERWORLD, NETHER, END }

    public enum Weather { CLEAR, RAIN, THUNDER }

    public enum LastStep { FAILED, SUCCEEDED }

    public enum Match { ALL, ANY }

    /** A trade option: as the setting says, or forced on or off for this step. */
    public enum Tri {
        SETTING, ON, OFF;

        static Tri of(Boolean b) {
            return b == null ? SETTING : b ? ON : OFF;
        }

        Boolean value() {
            return this == SETTING ? null : this == ON;
        }
    }

    public enum SetOp { SET_TO, ADD_TO }

    // ---- the model the page edits ---------------------------------------------------------------------------------

    /** What the editor remembers about one step besides its text: the state of its condition editors and chosen units. */
    private static final class EditState {
        final CondState[] conds = {new CondState("if"), new CondState("unless"), new CondState("until"), new CondState("block")};
        final Map<String, Unit> units = new HashMap<>();
    }

    /** One step in the list as of now: a parsed step, or a line that doesn't parse (kept as it is until fixed). */
    private static final class Row {
        final String id;
        final Step step;
        final String raw;
        final String error;
        final CondState[] conds;
        final Map<String, Unit> units;

        Row(RoutineModel.Row m, EditState e) {
            this.id = m.id;
            this.step = m.step();
            this.raw = m.text();
            this.error = m.error();
            this.conds = e.conds;
            this.units = e.units;
        }

        String text() {
            return raw;
        }
    }

    private static final class Session {
        final String name;
        final Routines store;
        final RoutineModel model = new RoutineModel();
        final Map<String, EditState> states = new HashMap<>();
        List<String> saved = List.of();

        Session(String name, Routines store) {
            this.name = name;
            this.store = store;
        }

        String prefix() {
            return LazuliConfig.SETTINGS.prefix.get();
        }

        /** Takes in changes made elsewhere (#routine edit, another screen). */
        synchronized void sync() {
            List<String> now = store.get(name);
            if (now == null) now = List.of();
            if (now.equals(saved)) return;
            model.load(now);
            states.clear();
            saved = List.copyOf(now);
        }

        synchronized void save() {
            List<String> lines = model.lines();
            store.set(name, lines);
            saved = List.copyOf(lines);
        }

        synchronized Row row(String id) {
            sync();
            RoutineModel.Row m = model.row(id);
            return m == null ? null : new Row(m, states.computeIfAbsent(id, k -> new EditState()));
        }

        /** The ids shown in the list (steps inside a collapsed block are left out). */
        synchronized List<String> ids() {
            sync();
            return model.visibleIds();
        }

        /** The list was edited in the widget: new names are commands picked, missing ones were removed. */
        synchronized void applyNames(List<String> names) {
            sync();
            model.reconcile(names, prefix());
            save();
        }

        synchronized void edit(String id, UnaryOperator<Step> f) {
            Row r = row(id);
            if (r == null || r.step == null) return;
            model.setStep(id, f.apply(r.step));
            save();
        }

        synchronized void setText(String id, String text) {
            sync();
            model.setText(id, text);
            save();
        }

        synchronized void move(String id, int by) {
            sync();
            if (model.move(id, by)) save();
        }

        synchronized void duplicate(String id) {
            sync();
            model.duplicate(id);
            save();
        }

        /** Deletes a step; a block's header (or its else / end) with its steps, or leaving them. */
        synchronized void delete(String id, boolean keepChildren) {
            sync();
            model.delete(id, keepChildren);
            save();
        }

        synchronized void toggleCollapsed(String id) {
            sync();
            model.toggle(id);
        }

        synchronized void addElse(String id, boolean withCondition) {
            sync();
            if (model.addElse(id, withCondition) == null) reply.accept("Can't add that here (an if has one plain else, and it must be closed with end)");
            save();
        }

        List<String> labels() {
            List<String> out = new ArrayList<>();
            for (RoutineModel.Row r : model.rows()) if (r.step() != null && r.step().kind() == Step.Kind.LABEL) out.add(r.step().label());
            return out;
        }
    }

    private static Session session(String name) {
        return SESSIONS.computeIfAbsent(name.toLowerCase(Locale.ROOT), n -> new Session(name, LazuliConfig.routines));
    }

    // ---- the page ------------------------------------------------------------------------------------------------

    /** One routine's page: its steps, how many runs, Run, Stop, Check and the running status. */
    public static List<Option<?>> page(String name) {
        Session s = session(name);
        s.sync();
        ListEntries commands = new ListEntries() {
            @Override
            public List<String> values() {
                return Forms.words();
            }

            @Override
            public String label(String v) {
                Row r = s.row(v);
                if (r != null) return summary(s, r);
                return Forms.spec(v).title();
            }
        };
        Option<List<String>> steps = Options.ofPageList("Steps", s::ids, List.of(), s::applyNames)
            .entries(commands)
            .groups(id -> stepGroups(s, id))
            .description(OptionDescription.ofText(() -> "Press + to add a step (pick a command); open a step with the arrow to change it, "
                + "move it, copy it or switch it off."))
            .build();
        Option<Integer> times = Options.ofNumber("Times", () -> TIMES.getOrDefault(name, 1), 1, v -> TIMES.put(name, v))
            .range(0, 64, 1).description(OptionDescription.ofText(() -> "How many times Run goes through it (0: forever).")).build();
        Option<Runnable> run = Options.ofButton("Run", () -> LazuliConfig.runRoutine.accept(name, TIMES.getOrDefault(name, 1)))
            .description(OptionDescription.ofText(() -> "Start it now.")).build();
        Option<Runnable> stop = Options.ofButton("Stop", () -> LazuliConfig.stopAction.run())
            .description(OptionDescription.ofText(() -> "Stop whatever's running.")).build();
        Option<Runnable> check = Options.ofButton("Check", () -> check(s))
            .description(OptionDescription.ofText(() -> "Look for mistakes (unknown commands, missing labels, bad settings); the answer goes to chat.")).build();
        Option<String> state = Options.ofString("Status", () -> {
            String st = status.get();
            return st == null ? "Not running" : st;
        }, "", v -> {})
            .availability(() -> false, "This is just the running status")
            .description(OptionDescription.ofText(() -> "What the running routine is doing now.")).build();
        return List.of(steps, times, run, stop, check, state);
    }

    private static void check(Session s) {
        s.sync();
        List<String> problems = Routine.check(s.saved, dev.lazuli.core.command.CommandHandler::isCommand, null);
        if (problems.isEmpty()) reply.accept(s.name + ": " + s.saved.size() + " steps, no problems");
        for (String p : problems) reply.accept(s.name + ": " + p);
    }

    /** "3. Get item: string 64   @retry 3". */
    private static String summary(Session s, Row r) {
        int n = s.model.indexOf(r.id) + 1;
        String indent = "| ".repeat(s.model.depth(r.id));
        if (r.step == null) return n + ". " + indent + "(doesn't parse) " + r.raw;
        Step st = r.step;
        String block = blockSummary(s, r);
        if (block != null) return n + ". " + indent + block;
        Forms.Spec spec = Forms.spec(st.word());
        String args = st.args();
        String title = Forms.stepWord(st.word()) || Forms.words().contains(st.word()) ? spec.title() : st.word();
        String mods = st.text().substring(st.command().length()).trim();
        return n + ". " + indent + (st.enabled() ? "" : "(off) ") + title + (args.isEmpty() ? "" : ": " + args) + (mods.isEmpty() ? "" : "   " + mods);
    }

    /** The list line of a block statement (if, else, while, repeat, end, break, continue, set, add); null for other steps. */
    private static String blockSummary(Session s, Row r) {
        Step st = r.step;
        String mark = s.model.isHeader(r.id) ? (s.model.collapsed(r.id) ? "[+] " : "[-] ") : "";
        String more = s.model.collapsed(r.id) ? "  (" + s.model.childCount(r.id) + " steps hidden)" : "";
        return switch (st.kind()) {
            case IF -> mark + "If " + st.args() + more;
            case ELSE -> st.args().isEmpty() ? "Else" : "Else if " + st.args().replaceFirst("(?i)^if\\s*", "");
            case WHILE -> mark + "While " + st.args() + more;
            case REPEAT -> mark + "Repeat " + st.args() + " times" + more;
            case END -> s.model.isBlockEnd(r.id) ? "End" : null;
            case BREAK -> "Break";
            case CONTINUE -> "Continue";
            case SET -> "Set $" + st.variable() + " = " + st.expression();
            case ADD -> "Add " + st.expression() + " to $" + st.variable();
            default -> null;
        };
    }

    // ---- one step's editor --------------------------------------------------------------------------------------------

    private static List<OptionGroup> stepGroups(Session s, String id) {
        BooleanSupplier alive = () -> s.row(id) != null;
        BooleanSupplier parsedAny = () -> {
            Row r = s.row(id);
            return r != null && r.step != null;
        };
        // an ordinary command step (not an if / while / set... or the end of a block): it gets the command, failure, timing, condition and settings groups
        BooleanSupplier parsed = () -> {
            Row r = s.row(id);
            return r != null && r.step != null && !control(s, r);
        };
        BooleanSupplier broken = () -> {
            Row r = s.row(id);
            return r != null && r.step == null;
        };
        List<OptionGroup> out = new ArrayList<>();

        // a line that doesn't parse: only its text, and why
        out.add(OptionGroup.builder("This step doesn't parse")
            .option(Options.ofString("Step text", () -> {
                Row r = s.row(id);
                return r == null ? "" : r.raw;
            }, "", v -> setRaw(s, id, v)).visibility(broken::getAsBoolean)
                .description(OptionDescription.ofText(() -> {
                    Row r = s.row(id);
                    return r == null || r.error == null ? "" : r.error;
                })).build())
            .build());

        // block statements: condition, count, variable, else, collapse
        out.addAll(blockGroups(s, id, parsedAny));

        // command and its arguments
        List<Option<?>> cmd = new ArrayList<>();
        cmd.add(Options.ofEnum("Command", () -> {
            Row r = s.row(id);
            return r == null || r.step == null ? Cmd.OTHER : Cmd.of(r.step.word());
        }, Cmd.OTHER, v -> setWord(s, id, v), Cmd.class).visibility(parsed::getAsBoolean)
            .description(OptionDescription.ofText(() -> "What this step does.")).build());
        cmd.addAll(argumentOptions(s, id, parsed));
        out.add(grp("Command", cmd));

        // failure
        List<Option<?>> fail = new ArrayList<>();
        fail.add(intOption("Retry", 0, 1000, () -> stepOf(s, id, st -> st.retry(), 0), v -> s.edit(id, st -> st.withRetry(v)), parsed,
            "Try again this many times if it fails or runs out of time."));
        fail.addAll(duration(s, id, "Timeout", parsed, st -> st.timeoutTicks(), (st, t) -> st.withTimeout(t),
            "Give up on one go after this long (0: no limit). A job with no end, like farm, simply ends then."));
        fail.add(Options.ofEnum("If it still fails", () -> stepOf(s, id, Step::onFail, Step.OnFail.CONTINUE), Step.OnFail.CONTINUE,
            v -> s.edit(id, st -> st.withOnFail(v, st.failLabel().isEmpty() ? firstLabel(s) : st.failLabel())), Step.OnFail.class)
            .visibility(parsed::getAsBoolean).description(OptionDescription.ofText(() ->
                "Continue: go on with the next step. Stop: end the routine. Skip rest: start the next round. Goto: jump to a label.")).build());
        fail.add(labelPicker("Go to label", s, id,
            () -> parsed.getAsBoolean() && stepOf(s, id, Step::onFail, Step.OnFail.CONTINUE) == Step.OnFail.GOTO));
        out.add(grp("When it fails", fail));

        // repeating and timing
        List<Option<?>> timing = new ArrayList<>();
        timing.add(intOption("Times", 1, 100000, () -> stepOf(s, id, Step::times, 1), v -> s.edit(id, st -> st.withTimes(v)), parsed,
            "Run the step this many times in a row."));
        timing.addAll(duration(s, id, "Wait before", parsed, st -> st.beforeTicks(), (st, t) -> st.withWaits(t, st.afterTicks()),
            "Pause this long before the step."));
        timing.addAll(duration(s, id, "Wait after", parsed, st -> st.afterTicks(), (st, t) -> st.withWaits(st.beforeTicks(), t),
            "Pause this long after the step."));
        out.add(grp("Repeating and timing", timing));

        // conditions
        String[] titles = {"Only run if", "Skip it when", "Repeat until"};
        String[] helps = {"The step is skipped unless this holds.", "The step is skipped when this holds.",
            "The step runs again and again until this holds (a job with no end is stopped then)."};
        for (int k = 0; k < 3; k++) out.add(conditionGroup(s, id, k, titles[k], helps[k], parsed));

        // per-step settings
        out.add(settingsGroup(s, id, parsed));

        // the step itself
        BooleanSupplier structure = () -> {
            Row r = s.row(id);
            return r != null && (s.model.isHeader(id) || s.model.isElse(id) || s.model.isBlockEnd(id));
        };
        BooleanSupplier movable = () -> {
            Row r = s.row(id);
            return r != null && !s.model.isElse(id) && !s.model.isBlockEnd(id);
        };
        out.add(OptionGroup.builder("Step")
            .option(Options.ofBoolean("Enabled", () -> stepOf(s, id, Step::enabled, true), true, v -> s.edit(id, st -> st.withEnabled(v)))
                .visibility(parsed::getAsBoolean).description(OptionDescription.ofText(() -> "A switched-off step stays in the routine but is skipped.")).build())
            .option(button("Move up", id, s, movable, () -> s.move(id, -1)))
            .option(button("Move down", id, s, movable, () -> s.move(id, 1)))
            .option(button("Duplicate", id, s, movable, () -> s.duplicate(id)))
            .option(button("Delete", id, s, () -> alive.getAsBoolean() && !structure.getAsBoolean(), () -> s.delete(id, false)))
            .option(button("Delete block, keep its steps", id, s, structure, () -> s.delete(id, true)))
            .option(button("Delete block and its steps", id, s, structure, () -> s.delete(id, false)))
            .option(Options.ofString("Step text", () -> {
                Row r = s.row(id);
                return r == null ? "" : r.text();
            }, "", v -> setRaw(s, id, v)).visibility(parsedAny::getAsBoolean)
                .description(OptionDescription.ofText(() -> "The step as the routines file has it. Advanced: edit it by hand if you like.")).build())
            .build());
        return out;
    }

    private static OptionGroup grp(String title, List<Option<?>> options) {
        OptionGroup.Builder b = OptionGroup.builder(title);
        for (Option<?> o : options) b.option(o);
        return b.build();
    }

    private static Option<Runnable> button(String label, String id, Session s, BooleanSupplier alive, Runnable action) {
        return Options.ofButton(label, action).visibility(alive::getAsBoolean).build();
    }

    private static void setRaw(Session s, String id, String text) {
        Row r = s.row(id);
        if (r == null || text == null || text.isBlank()) return;
        String t = text.trim();
        try {
            Step.parse(t);
        } catch (IllegalArgumentException e) {
            reply.accept("Step not changed: " + e.getMessage());
            return;
        }
        s.setText(id, t);
    }

    private static <T> T stepOf(Session s, String id, java.util.function.Function<Step, T> f, T none) {
        Row r = s.row(id);
        return r == null || r.step == null ? none : f.apply(r.step);
    }

    private static String firstLabel(Session s) {
        List<String> l = s.labels();
        return l.isEmpty() ? "label" : l.get(0);
    }

    private static Option<Integer> intOption(String name, int min, int max, Supplier<Integer> get, Consumer<Integer> set,
                                             BooleanSupplier visible, String help) {
        return Options.ofNumber(name, () -> Math.max(min, Math.min(max, get.get())), min, v -> set.accept(Math.max(min, Math.min(max, v))))
            .range(min, max, 1).visibility(visible::getAsBoolean).description(OptionDescription.ofText(() -> help)).build();
    }

    /** A time as a number and a unit (seconds, minutes, hours), stored as ticks. */
    private static List<Option<?>> duration(Session s, String id, String label, BooleanSupplier visible,
                                            java.util.function.ToIntFunction<Step> get, java.util.function.BiFunction<Step, Integer, Step> set,
                                            String help) {
        Supplier<Unit> unit = () -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return Unit.SECONDS;
            return r.units.computeIfAbsent(label, k -> Unit.best(get.applyAsInt(r.step)));
        };
        Option<Integer> number = Options.ofNumber(label, () -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return 0;
            return Math.min(1440, Math.round(get.applyAsInt(r.step) / (float) unit.get().ticks));
        }, 0, v -> s.edit(id, st -> set.apply(st, v * unit.get().ticks))).range(0, 1440, 1)
            .visibility(visible::getAsBoolean).description(OptionDescription.ofText(() -> help)).build();
        Option<Unit> units = Options.ofEnum(label + " unit", unit, Unit.SECONDS, u -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return;
            int ticks = get.applyAsInt(r.step);
            r.units.put(label, u);
            // the same time in the new unit, as near as it goes
            s.edit(id, st -> set.apply(st, Math.round(ticks / (float) u.ticks) * u.ticks));
        }, Unit.class).visibility(visible::getAsBoolean).description(OptionDescription.ofText(() -> help)).build();
        return List.of(number, units);
    }

    private static Option<List<String>> labelPicker(String name, Session s, String id, BooleanSupplier visible) {
        ListEntries labels = new ListEntries() {
            @Override
            public List<String> values() {
                return s.labels();
            }
        };
        return Options.ofSelectionList(name, () -> {
            String l = stepOf(s, id, Step::failLabel, "");
            return l.isEmpty() ? List.of() : List.of(l);
        }, List.of(), v -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return;
            String cur = r.step.failLabel();
            String pick = v.stream().filter(x -> !x.equals(cur)).findFirst().orElse(v.isEmpty() ? cur : v.get(v.size() - 1));
            s.edit(id, st -> st.withOnFail(Step.OnFail.GOTO, pick));
        }, labels).visibility(visible::getAsBoolean)
            .description(OptionDescription.ofText(() -> "A 'label' step in this routine (add one with + and pick Label).")).build();
    }

    // ---- command arguments ----------------------------------------------------------------------------------------------

    private static String word(Session s, String id) {
        Row r = s.row(id);
        return r == null || r.step == null ? "" : r.step.word();
    }

    private static Forms.Layout layout(Session s, String id) {
        String w = word(s, id);
        if (w.isEmpty()) return Forms.Layout.RAW;
        Row r = s.row(id);
        if (r.step.kind() == Step.Kind.JUMP && w.equals("goto")) return Forms.Layout.LABEL;
        return Forms.spec(w).layout();
    }

    private static void setArgs(Session s, String id, String args) {
        s.edit(id, st -> {
            String c = st.command();
            int sp = c.indexOf(' ');
            String head = sp < 0 ? c : c.substring(0, sp);
            return st.withCommand(args == null || args.isBlank() ? head : head + " " + args.trim());
        });
    }

    private static void setWord(Session s, String id, Cmd cmd) {
        if (cmd == Cmd.OTHER) return;
        Row r = s.row(id);
        if (r == null || r.step == null || r.step.word().equals(cmd.word())) return;
        Forms.Layout before = Forms.spec(r.step.word()).layout(), after = Forms.spec(cmd.word()).layout();
        String args = before == after && before != Forms.Layout.RAW ? r.step.args() : "";
        String command = args.isEmpty() ? Forms.defaultCommand(s.prefix(), cmd.word()) : Forms.command(s.prefix(), cmd.word(), args);
        s.edit(id, st -> st.withCommand(command));
    }

    private static List<Option<?>> argumentOptions(Session s, String id, BooleanSupplier parsed) {
        List<Option<?>> out = new ArrayList<>();
        BooleanSupplier command = () -> parsed.getAsBoolean() && r(s, id).kind() == Step.Kind.COMMAND;
        // the text of the arguments, for anything the fields can't say
        out.add(Options.ofString("Arguments", () -> {
            Row r = s.row(id);
            return r == null || r.step == null ? "" : r.step.args();
        }, "", v -> setArgs(s, id, v)).visibility(() -> parsed.getAsBoolean() && (command.getAsBoolean() || layout(s, id) == Forms.Layout.RAW))
            .description(OptionDescription.ofText(() -> "The command's arguments as text. The fields below fill this in for the common ones.")).build());

        ListEntries targetNames = new ListEntries() {
            @Override
            public List<String> values() {
                return targets.get();
            }
        };

        // item and count
        BooleanSupplier itemLike = () -> parsed.getAsBoolean() && switch (layout(s, id)) {
            case ITEM_COUNT, ITEM_ALL_COUNT -> Forms.parseItem(plain(s, id)) != null;
            default -> false;
        };
        BooleanSupplier allLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.ITEM_ALL_COUNT
            && Forms.parseItem(plain(s, id)) != null;
        BooleanSupplier allOn = () -> allLike.getAsBoolean() && Forms.parseItem(plain(s, id)).item().equals("all");
        out.add(Options.ofBoolean("Everything", () -> allOn.getAsBoolean(), false, v -> {
            var a = Forms.parseItem(plain(s, id));
            if (a == null) return;
            setPlain(s, id, Forms.compose(new Forms.ItemArg(v ? "all" : "", v ? 0 : a.count(), a.target())));
        }).visibility(allLike::getAsBoolean).description(OptionDescription.ofText(() -> "Every item in the inventory, not one kind.")).build());
        out.add(single("Item", ListEntries.items(), () -> {
            var a = Forms.parseItem(plain(s, id));
            return a == null || a.item().isEmpty() || a.item().equals("all") ? "" : withNamespace(a.item());
        }, pick -> {
            var a = Forms.parseItem(plain(s, id));
            if (a == null) return;
            setPlain(s, id, Forms.compose(new Forms.ItemArg(stripNamespace(pick), a.count(), a.target())));
        }, () -> itemLike.getAsBoolean() && !allOn.getAsBoolean(), "Which item (search by name)."));
        out.add(intOption("Count", 0, 2304, () -> {
            var a = Forms.parseItem(plain(s, id));
            return a == null ? 0 : a.count();
        }, v -> {
            var a = Forms.parseItem(plain(s, id));
            if (a == null) return;
            setPlain(s, id, Forms.compose(new Forms.ItemArg(a.item(), v, a.target())));
        }, () -> itemLike.getAsBoolean() && !allOn.getAsBoolean(), "How many (0: not given, the command's own default)."));

        BooleanSupplier targetLike = () -> itemLike.getAsBoolean() && Forms.takesTarget(word(s, id));
        out.add(single("Work at target", targetNames, () -> {
            var a = Forms.parseItem(plain(s, id));
            return a == null ? "" : a.target();
        }, pick -> {
            var a = Forms.parseItem(plain(s, id));
            if (a == null) return;
            setPlain(s, id, Forms.compose(new Forms.ItemArg(a.item(), a.count(), pick)));
        }, targetLike, "A saved target (Targets page): the chest to use, the brewing stand to brew at... Without one it uses the nearest."));
        out.add(Options.ofButton("Use the nearest instead", () -> {
            var a = Forms.parseItem(plain(s, id));
            if (a != null) setPlain(s, id, Forms.compose(new Forms.ItemArg(a.item(), a.count(), "")));
        }).visibility(() -> targetLike.getAsBoolean() && !Forms.parseItem(plain(s, id)).target().isEmpty())
            .description(OptionDescription.ofText(() -> "Stop using the target.")).build());

        // mine: block and count
        BooleanSupplier blockLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.BLOCK_COUNT
            && Forms.parseBlock(plain(s, id)) != null;
        out.add(single("Block", ListEntries.blocks(), () -> {
            var a = Forms.parseBlock(plain(s, id));
            return a == null || a.item().isEmpty() ? "" : withNamespace(a.item());
        }, pick -> {
            var a = Forms.parseBlock(plain(s, id));
            if (a == null) return;
            setPlain(s, id, Forms.composeBlock(new Forms.ItemArg(stripNamespace(pick), a.count(), a.target())));
        }, blockLike, "Which block to mine."));
        out.add(intOption("Blocks to mine", 0, 2304, () -> {
            var a = Forms.parseBlock(plain(s, id));
            return a == null ? 0 : a.count();
        }, v -> {
            var a = Forms.parseBlock(plain(s, id));
            if (a == null) return;
            setPlain(s, id, Forms.composeBlock(new Forms.ItemArg(a.item(), v, a.target())));
        }, blockLike, "How many (0: keep mining until stopped)."));

        // a number
        BooleanSupplier numLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.NUMBER && Forms.parseNumber(plain(s, id)) >= 0;
        out.add(Options.ofNumber("Amount", () -> Math.max(0, Forms.parseNumber(plain(s, id))), 0,
            v -> setPlain(s, id, v <= 0 ? "" : String.valueOf(v))).range(0, 1440, 1).visibility(numLike::getAsBoolean)
            .description(OptionDescription.ofText(() -> numberHelp(s, id))).build());

        // coordinates or a target
        BooleanSupplier whereLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.COORDS
            && Forms.parseWhere(plain(s, id)) != null;
        BooleanSupplier atTarget = () -> whereLike.getAsBoolean() && Forms.parseWhere(plain(s, id)).xyz() == null;
        BooleanSupplier atCoords = () -> whereLike.getAsBoolean() && !atTarget.getAsBoolean();
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            final int axis = i;
            out.add(Options.ofNumber(axes[i], () -> {
                var w = Forms.parseWhere(plain(s, id));
                return w == null || w.xyz() == null ? 0 : w.xyz()[axis];
            }, 0, v -> {
                var w = Forms.parseWhere(plain(s, id));
                if (w == null || w.xyz() == null) return;
                int[] xyz = w.xyz().clone();
                xyz[axis] = v;
                setPlain(s, id, Forms.compose(new Forms.Where(xyz, "")));
            }).range(axis == 1 ? -64 : -30000000, axis == 1 ? 320 : 30000000, 1).visibility(atCoords::getAsBoolean)
                .description(OptionDescription.ofText(() -> "Block coordinate.")).build());
        }
        out.add(Options.ofButton("Use my position", () -> {
            int[] p = playerPos.get();
            if (p == null) {
                reply.accept("Not in a world: no position to take");
                return;
            }
            setPlain(s, id, Forms.compose(new Forms.Where(p.clone(), "")));
        }).visibility(atCoords::getAsBoolean).description(OptionDescription.ofText(() -> "Fill in the coordinates with where you stand now.")).build());
        out.add(single("Target", targetNames, () -> {
            var w = Forms.parseWhere(plain(s, id));
            return w == null || w.xyz() != null ? "" : w.target();
        }, pick -> setPlain(s, id, "@" + pick), whereLike,
            "A saved target (see the Targets page); the command goes to its position. Pick one to use it instead of the coordinates."));
        out.add(Options.ofButton("Use coordinates instead", () -> {
            int[] p = playerPos.get();
            setPlain(s, id, Forms.compose(new Forms.Where(p == null ? new int[]{0, 64, 0} : p.clone(), "")));
        }).visibility(atTarget::getAsBoolean).description(OptionDescription.ofText(() -> "Stop using the target; go to coordinates.")).build());

        // duration (wait), text (say, notify), label (label, jump)
        BooleanSupplier waitLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.DURATION;
        out.add(Options.ofNumber("Wait", () -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return 0;
            try {
                return Math.min(1440, Math.round(Step.parseDuration(r.step.args()) / (float) waitUnit(r).ticks));
            } catch (IllegalArgumentException e) {
                return 0;
            }
        }, 0, v -> {
            Row r = s.row(id);
            if (r != null) setPlain(s, id, Step.durationText(v * waitUnit(r).ticks));
        }).range(0, 1440, 1).visibility(waitLike::getAsBoolean).description(OptionDescription.ofText(() -> "How long to wait.")).build());
        out.add(Options.ofEnum("Wait unit", () -> {
            Row r = s.row(id);
            return r == null || r.step == null ? Unit.SECONDS : waitUnit(r);
        }, Unit.SECONDS, u -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return;
            int ticks;
            try {
                ticks = Step.parseDuration(r.step.args());
            } catch (IllegalArgumentException e) {
                ticks = 0;
            }
            r.units.put("wait", u);
            setPlain(s, id, Step.durationText(Math.round(ticks / (float) u.ticks) * u.ticks));
        }, Unit.class).visibility(waitLike::getAsBoolean).description(OptionDescription.ofText(() -> "Seconds, minutes or hours.")).build());
        BooleanSupplier textLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.TEXT;
        out.add(Options.ofString("Text", () -> stepOf(s, id, Step::args, ""), "", v -> {
            if (v != null && !v.isBlank()) setPlain(s, id, v);
        }).visibility(textLike::getAsBoolean).description(OptionDescription.ofText(() ->
            "What to say. Starting with / sends a command; a plain line is a chat message (or, for Tell me, a message to you).")).build());
        BooleanSupplier labelLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.LABEL;
        out.add(Options.ofString("Label name", () -> stepOf(s, id, Step::label, ""), "", v -> {
            if (v != null && !v.isBlank() && !v.contains(" ")) setPlain(s, id, v.trim());
        }).visibility(labelLike::getAsBoolean).description(OptionDescription.ofText(() -> "One word. 'Jump' goes to the label of this name.")).build());
        out.addAll(commandOptions(s, id, parsed, targetNames));
        return out;
    }

    // ---- a command's own options: trade, at x y z, dry ... -----------------------------------------------------------

    /** The arguments without the tail words ({@code at x y z}, {@code dry}...) that have their own fields. */
    private static String plain(Session s, String id) {
        Row r = s.row(id);
        if (r == null || r.step == null) return "";
        return Forms.splitExtras(r.step.word(), r.step.args()).rest();
    }

    /** Writes the plain arguments back, keeping the tail words. */
    private static void setPlain(Session s, String id, String rest) {
        editExtras(s, id, e -> e.withRest(rest == null ? "" : rest));
    }

    private static void editExtras(Session s, String id, UnaryOperator<Forms.Extras> f) {
        Row r = s.row(id);
        if (r == null || r.step == null) return;
        Forms.Extras e = Forms.splitExtras(r.step.word(), r.step.args());
        setArgs(s, id, Forms.joinExtras(f.apply(e)));
    }

    private static Forms.Extras extras(Session s, String id) {
        Row r = s.row(id);
        return r == null || r.step == null ? new Forms.Extras("", null, false, false, "") : Forms.splitExtras(r.step.word(), r.step.args());
    }

    private static TradeForm trade(Session s, String id) {
        return TradeForm.parse(plain(s, id));
    }

    private static void editTrade(Session s, String id, Consumer<TradeForm> f) {
        TradeForm t = trade(s, id);
        if (t == null) return;
        f.accept(t);
        setPlain(s, id, t.text());
    }

    private static Option<Tri> tri(Session s, String id, String name, String help, BooleanSupplier visible,
                                   java.util.function.Function<TradeForm, Boolean> get, java.util.function.BiConsumer<TradeForm, Boolean> set) {
        return Options.ofEnum(name, () -> {
            TradeForm t = trade(s, id);
            return t == null ? Tri.SETTING : Tri.of(get.apply(t));
        }, Tri.SETTING, v -> editTrade(s, id, t -> set.accept(t, v.value())), Tri.class).visibility(visible::getAsBoolean)
            .description(OptionDescription.ofText(() -> help + " Setting: the value in the Survival tab. On / Off: this step only.")).build();
    }

    /** A number that is either the setting's (no value here) or set for this step. */
    private static List<Option<?>> override(Session s, String id, String name, String help, BooleanSupplier visible, double min, double max,
                                            double def, boolean whole, java.util.function.Function<TradeForm, Number> get,
                                            java.util.function.BiConsumer<TradeForm, Number> set) {
        BooleanSupplier on = () -> visible.getAsBoolean() && trade(s, id) != null && get.apply(trade(s, id)) != null;
        List<Option<?>> out = new ArrayList<>();
        out.add(Options.ofBoolean(name + ": set for this step", on::getAsBoolean, false, v -> editTrade(s, id,
            t -> set.accept(t, v ? (whole ? (Number) (int) def : (Number) def) : null))).visibility(visible::getAsBoolean)
            .description(OptionDescription.ofText(() -> help + " Off: the setting decides.")).build());
        if (whole) {
            out.add(Options.ofNumber(name, () -> {
                TradeForm t = trade(s, id);
                Number n = t == null ? null : get.apply(t);
                return n == null ? (int) def : Math.max((int) min, Math.min((int) max, n.intValue()));
            }, (int) def, v -> editTrade(s, id, t -> set.accept(t, v))).range((int) min, (int) max, 1).visibility(on::getAsBoolean)
                .description(OptionDescription.ofText(() -> help)).build());
        } else {
            out.add(Options.ofNumber(name, () -> {
                TradeForm t = trade(s, id);
                Number n = t == null ? null : get.apply(t);
                return n == null ? def : Math.max(min, Math.min(max, n.doubleValue()));
            }, def, v -> editTrade(s, id, t -> set.accept(t, v))).range(min, max, 0.1).visibility(on::getAsBoolean)
                .description(OptionDescription.ofText(() -> help)).build());
        }
        return out;
    }

    private static List<Option<?>> commandOptions(Session s, String id, BooleanSupplier parsed, ListEntries targetNames) {
        List<Option<?>> out = new ArrayList<>();

        // trade
        BooleanSupplier tradeLike = () -> parsed.getAsBoolean() && layout(s, id) == Forms.Layout.TRADE && trade(s, id) != null;
        out.add(Options.ofBoolean("Sell (for emeralds)", () -> tradeLike.getAsBoolean() && trade(s, id).sell, false,
            v -> editTrade(s, id, t -> t.sell = v)).visibility(tradeLike::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Off: buy what's named below. On: sell it to villagers.")).build());
        out.add(Options.ofString("Item or enchantment", () -> tradeLike.getAsBoolean() ? trade(s, id).item : "", "",
            v -> editTrade(s, id, t -> t.item = v == null ? "" : v.trim().replace(" ", ""))).visibility(tradeLike::getAsBoolean)
            .description(OptionDescription.ofText(() -> "What to trade for: an item (string) or an enchantment (sharpness).")).build());
        out.add(single("Pick an item", ListEntries.items(), () -> {
            if (!tradeLike.getAsBoolean()) return "";
            String it = trade(s, id).item;
            return it.isEmpty() ? "" : withNamespace(it);
        }, pick -> editTrade(s, id, t -> t.item = stripNamespace(pick)), tradeLike, "Fills in the item above."));
        out.add(intOption("Amount", 0, 2304, () -> tradeLike.getAsBoolean() ? trade(s, id).count : 0,
            v -> editTrade(s, id, t -> t.count = v), tradeLike, "How many (0: not given, the command's own default; an enchantment: its level)."));
        ListEntries professions = new ListEntries() {
            @Override
            public List<String> values() {
                return TradeForm.PROFESSIONS;
            }
        };
        out.add(single("Villager profession", professions, () -> tradeLike.getAsBoolean() ? trade(s, id).with : "",
            pick -> editTrade(s, id, t -> t.with = pick), tradeLike, "Only trade with villagers of this profession (none picked: any)."));
        out.add(Options.ofButton("Any profession", () -> editTrade(s, id, t -> t.with = ""))
            .visibility(() -> tradeLike.getAsBoolean() && !trade(s, id).with.isEmpty()).description(OptionDescription.ofText(() -> "Stop limiting to a profession.")).build());
        out.add(single("Villager target", targetNames, () -> tradeLike.getAsBoolean() ? trade(s, id).target : "",
            pick -> editTrade(s, id, t -> t.target = pick), tradeLike, "A saved target (Targets page): go to this villager."));
        out.add(Options.ofButton("Any villager", () -> editTrade(s, id, t -> t.target = ""))
            .visibility(() -> tradeLike.getAsBoolean() && !trade(s, id).target.isEmpty()).description(OptionDescription.ofText(() -> "Stop using the target.")).build());
        out.add(tri(s, id, "Only known villagers", "Never walk to a villager whose trades aren't known.", tradeLike, t -> t.onlyKnown, (t, v) -> t.onlyKnown = v));
        out.add(tri(s, id, "Look at unseen villagers", "May walk to villagers not seen yet when no known one can do it.", tradeLike, t -> t.scan, (t, v) -> t.scan = v));
        out.add(tri(s, id, "Wait for restock", "Wait for villagers to restock their trades.", tradeLike, t -> t.waitRestock, (t, v) -> t.waitRestock = v));
        out.add(tri(s, id, "Wait for a day", "Wait a whole day for restocks.", tradeLike, t -> t.waitDay, (t, v) -> t.waitDay = v));
        out.add(tri(s, id, "Stay put (don't walk)", "Trade only with villagers within reach, never walk.", tradeLike, t -> t.stay, (t, v) -> t.stay = v));
        out.addAll(override(s, id, "Max trades", "Stop after this many trades (0: no limit).", tradeLike, 0, 100000, 64, true,
            t -> t.max, (t, n) -> t.max = n == null ? null : n.intValue()));
        out.addAll(override(s, id, "Search range", "How far to look for villagers, in blocks.", tradeLike, 1, 256, 32, true,
            t -> t.range, (t, n) -> t.range = n == null ? null : n.intValue()));
        out.addAll(override(s, id, "Minimum rate", "Skip trades that give less than this many items per item paid.", tradeLike, 0, 64, 1, false,
            t -> t.minRate, (t, n) -> t.minRate = n == null ? null : n.doubleValue()));
        out.add(Options.ofString("Keeping", () -> {
            if (!tradeLike.getAsBoolean()) return "";
            StringBuilder b = new StringBuilder();
            for (TradeForm.Keep k : trade(s, id).keep) b.append(b.length() > 0 ? ", " : "").append(k.item()).append(k.count() < 0 ? " (all)" : " " + k.count());
            return b.length() == 0 ? "nothing extra" : b.toString();
        }, "", v -> {}).availability(() -> false, "This lists what is kept; change it with the options below")
            .visibility(tradeLike::getAsBoolean).description(OptionDescription.ofText(() -> "Items never paid with (on top of the Survival tab's list).")).build());
        out.add(intOption("Keep amount", 0, 2304, () -> KEEP_AMOUNT.getOrDefault(id, 0), v -> KEEP_AMOUNT.put(id, v), tradeLike,
            "How many of the item to keep (0: never pay with it at all). Then pick the item below."));
        out.add(single("Keep an item", ListEntries.items(), () -> "", pick -> editTrade(s, id, t -> {
            int n = KEEP_AMOUNT.getOrDefault(id, 0);
            String item = stripNamespace(pick);
            t.keep.removeIf(k -> k.item().equals(item));
            t.keep.add(new TradeForm.Keep(item, n == 0 ? -1 : n));
        }), tradeLike, "Adds the item to what's kept."));
        out.add(Options.ofButton("Clear keep list", () -> editTrade(s, id, t -> t.keep.clear()))
            .visibility(() -> tradeLike.getAsBoolean() && !trade(s, id).keep.isEmpty()).description(OptionDescription.ofText(() -> "Remove this step's keep items.")).build());

        // stations: work at a position
        BooleanSupplier atLike = () -> parsed.getAsBoolean() && Forms.takesAt(word(s, id));
        BooleanSupplier atOn = () -> atLike.getAsBoolean() && extras(s, id).at() != null;
        out.add(Options.ofBoolean("Work at a position", atOn::getAsBoolean, false, v -> {
            int[] p = playerPos.get();
            editExtras(s, id, e -> e.withAt(v ? (p == null ? new String[]{"~", "~", "~"} : new String[]{String.valueOf(p[0]), String.valueOf(p[1]), String.valueOf(p[2])}) : null));
        }).visibility(atLike::getAsBoolean).description(OptionDescription.ofText(() -> "Use the table, furnace or stand at these coordinates (or pick a target above). Off: the nearest one.")).build());
        String[] axes = {"At X", "At Y", "At Z"};
        for (int i = 0; i < 3; i++) {
            final int axis = i;
            out.add(Options.ofNumber(axes[i], () -> {
                String[] a = extras(s, id).at();
                if (a == null) return 0;
                try {
                    return Integer.parseInt(a[axis]);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }, 0, v -> editExtras(s, id, e -> {
                String[] a = e.at() == null ? new String[]{"0", "0", "0"} : e.at().clone();
                a[axis] = String.valueOf(v);
                return e.withAt(a);
            })).range(axis == 1 ? -64 : -30000000, axis == 1 ? 320 : 30000000, 1).visibility(atOn::getAsBoolean)
                .description(OptionDescription.ofText(() -> "Block coordinate of the station.")).build());
        }
        out.add(Options.ofButton("Use my position for the station", () -> {
            int[] p = playerPos.get();
            if (p == null) {
                reply.accept("Not in a world: no position to take");
                return;
            }
            editExtras(s, id, e -> e.withAt(new String[]{String.valueOf(p[0]), String.valueOf(p[1]), String.valueOf(p[2])}));
        }).visibility(atOn::getAsBoolean).description(OptionDescription.ofText(() -> "Fill in the coordinates with where you stand now.")).build());

        // getitem: dry, deposit, then
        BooleanSupplier getLike = () -> parsed.getAsBoolean() && word(s, id).equals("getitem");
        out.add(Options.ofBoolean("Only show the plan (dry)", () -> getLike.getAsBoolean() && extras(s, id).dry(), false,
            v -> editExtras(s, id, e -> e.withDry(v))).visibility(getLike::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Print what it would do and do nothing.")).build());
        out.add(Options.ofBoolean("Deposit afterwards", () -> getLike.getAsBoolean() && extras(s, id).deposit(), false,
            v -> editExtras(s, id, e -> e.withDeposit(v))).visibility(getLike::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Put the items in a chest when done.")).build());
        out.add(Options.ofString("Then go to (back or a waypoint)", () -> getLike.getAsBoolean() ? extras(s, id).then() : "", "",
            v -> editExtras(s, id, e -> e.withThen(v == null ? "" : v.trim().replace(" ", "")))).visibility(getLike::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Leave empty to stay where it ended; 'back' returns to where it started.")).build());
        return out;
    }

    private static final Map<String, Integer> KEEP_AMOUNT = new ConcurrentHashMap<>();

    // ---- block statements -----------------------------------------------------------------------------------------------

    /** True for the steps that shape the routine rather than run a command: if, else, while, repeat, break, continue, set, add, and a block's end. */
    private static boolean control(Session s, Row r) {
        if (r.step == null) return false;
        return switch (r.step.kind()) {
            case IF, ELSE, WHILE, REPEAT, BREAK, CONTINUE, SET, ADD -> true;
            case END -> s.model.isBlockEnd(r.id);
            default -> false;
        };
    }

    /** Writes a block statement's text after checking it parses (so a typo doesn't break the routine). */
    private static void setChecked(Session s, String id, String command) {
        try {
            Step.parse(command);
        } catch (IllegalArgumentException e) {
            reply.accept("Not changed: " + e.getMessage());
            return;
        }
        s.edit(id, st -> st.withCommand(command));
    }

    private static List<OptionGroup> blockGroups(Session s, String id, BooleanSupplier parsedAny) {
        java.util.function.Supplier<Step.Kind> kind = () -> {
            Row r = s.row(id);
            return r == null || r.step == null ? Step.Kind.COMMAND : r.step.kind();
        };
        BooleanSupplier hasCond = () -> parsedAny.getAsBoolean() && (kind.get() == Step.Kind.IF || kind.get() == Step.Kind.WHILE
            || kind.get() == Step.Kind.ELSE && !stepOf(s, id, Step::args, "").isEmpty());
        BooleanSupplier isIf = () -> parsedAny.getAsBoolean() && kind.get() == Step.Kind.IF;
        BooleanSupplier plainElse = () -> parsedAny.getAsBoolean() && kind.get() == Step.Kind.ELSE && stepOf(s, id, Step::args, "").isEmpty();
        BooleanSupplier elseIf = () -> parsedAny.getAsBoolean() && kind.get() == Step.Kind.ELSE && !stepOf(s, id, Step::args, "").isEmpty();
        BooleanSupplier header = () -> parsedAny.getAsBoolean() && s.model.isHeader(id);
        BooleanSupplier repeat = () -> parsedAny.getAsBoolean() && kind.get() == Step.Kind.REPEAT;
        BooleanSupplier setLike = () -> parsedAny.getAsBoolean() && (kind.get() == Step.Kind.SET || kind.get() == Step.Kind.ADD);
        List<OptionGroup> out = new ArrayList<>();
        out.add(conditionGroup(s, id, 3, "Condition", "The block runs when this holds (an else if: when the ones before didn't and this does).", hasCond));
        List<Option<?>> block = new ArrayList<>();
        block.add(Options.ofButton("Collapse / expand", () -> s.toggleCollapsed(id)).visibility(header::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Hide or show the steps inside this block in the list.")).build());
        block.add(Options.ofButton("Add an else", () -> s.addElse(id, false)).visibility(isIf::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Steps to run when the condition doesn't hold.")).build());
        block.add(Options.ofButton("Add an else if", () -> s.addElse(id, true)).visibility(isIf::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Another condition to try when the ones before didn't hold.")).build());
        block.add(Options.ofButton("Make it an else if", () -> setChecked(s, id, "else if night")).visibility(plainElse::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Give this else a condition of its own.")).build());
        block.add(Options.ofButton("Make it a plain else", () -> setChecked(s, id, "else")).visibility(elseIf::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Drop the condition: this runs when nothing before did.")).build());
        block.add(Options.ofNumber("Times", () -> {
            String e = stepOf(s, id, Step::expression, "1");
            try {
                return Math.max(0, Math.min(1000000, Integer.parseInt(e.trim())));
            } catch (NumberFormatException ex) {
                return 1;
            }
        }, 3, v -> setChecked(s, id, "repeat " + v)).range(0, 1000000, 1)
            .visibility(() -> repeat.getAsBoolean() && stepOf(s, id, Step::expression, "").trim().matches("[0-9]+"))
            .description(OptionDescription.ofText(() -> "How many times the steps inside run.")).build());
        block.add(Options.ofString("Times (number or expression)", () -> stepOf(s, id, Step::expression, ""), "", v -> {
            if (v != null && !v.isBlank()) setChecked(s, id, "repeat " + v.trim());
        }).visibility(repeat::getAsBoolean).description(OptionDescription.ofText(() -> "A number, a variable ($count) or a sum like $n * 2 + 1.")).build());
        block.add(Options.ofEnum("Operation", () -> kind.get() == Step.Kind.ADD ? SetOp.ADD_TO : SetOp.SET_TO, SetOp.SET_TO, v -> {
            String var = stepOf(s, id, Step::variable, "count");
            String expr = stepOf(s, id, Step::expression, "1");
            setChecked(s, id, v == SetOp.SET_TO ? "set " + var + " = " + expr : "add " + var + " " + expr);
        }, SetOp.class).visibility(setLike::getAsBoolean).description(OptionDescription.ofText(() -> "Set the variable to a value, or add to what it has.")).build());
        block.add(Options.ofString("Variable name", () -> stepOf(s, id, Step::variable, ""), "", v -> {
            if (v == null || v.isBlank()) return;
            String var = v.trim().replaceFirst("^\\$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
            if (var.isEmpty()) return;
            String expr = stepOf(s, id, Step::expression, "1");
            setChecked(s, id, kind.get() == Step.Kind.ADD ? "add " + var + " " + expr : "set " + var + " = " + expr);
        }).visibility(setLike::getAsBoolean).description(OptionDescription.ofText(() -> "Letters, numbers and _. Use it as $name in conditions and step arguments.")).build());
        block.add(Options.ofString("Value (number or expression)", () -> stepOf(s, id, Step::expression, ""), "", v -> {
            if (v == null || v.isBlank()) return;
            String var = stepOf(s, id, Step::variable, "count");
            setChecked(s, id, kind.get() == Step.Kind.ADD ? "add " + var + " " + v.trim() : "set " + var + " = " + v.trim());
        }).visibility(setLike::getAsBoolean).description(OptionDescription.ofText(() -> "A number, $other, inv.<item>, health..., with + - * / and brackets.")).build());
        out.add(grp("Block", block));
        return out;
    }

    private static Unit waitUnit(Row r) {
        return r.units.computeIfAbsent("wait", k -> {
            try {
                return Unit.best(Step.parseDuration(r.step.args()));
            } catch (IllegalArgumentException e) {
                return Unit.SECONDS;
            }
        });
    }

    private static String numberHelp(Session s, String id) {
        String w = word(s, id);
        return w.isEmpty() ? "" : Forms.spec(w).numberLabel() + " (0: the command's default)";
    }

    private static Step r(Session s, String id) {
        Row r = s.row(id);
        return r == null || r.step == null ? Step.of("#stop") : r.step;
    }

    private static String withNamespace(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String stripNamespace(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** One pick out of a searchable catalogue; adding another replaces the first. */
    private static Option<List<String>> single(String name, ListEntries entries, Supplier<String> get, Consumer<String> set,
                                               BooleanSupplier visible, String help) {
        return Options.ofSelectionList(name, () -> {
            String cur = get.get();
            return cur.isEmpty() ? List.of() : List.of(cur);
        }, List.of(), v -> {
            String cur = get.get();
            String pick = v.stream().filter(x -> !x.equals(cur)).findFirst().orElse(null);
            if (pick != null) set.accept(pick);
        }, entries).visibility(visible::getAsBoolean).description(OptionDescription.ofText(() -> help)).build();
    }

    // ---- conditions -------------------------------------------------------------------------------------------------------

    /** The clauses of one condition (if, unless, until) of a step, with ids for the list. */
    private static final class CondState {
        final String which;
        CondForm form = new CondForm();
        boolean flat = true;
        final List<String> ids = new ArrayList<>();
        final Map<String, CondForm.Clause> byId = new LinkedHashMap<>();
        /** The step's condition as of the last push or reload: when it differs, something else changed it. */
        Cond seen;
        boolean loaded;
        int counter;

        CondState(String which) {
            this.which = which;
        }

        Cond get(Step st) {
            return switch (which) {
                case "if" -> st.ifCond();
                case "unless" -> st.unlessCond();
                case "block" -> {
                    try {
                        yield st.blockCond();
                    } catch (RuntimeException e) {
                        yield null;
                    }
                }
                default -> st.untilCond();
            };
        }

        Step put(Step st, Cond c) {
            if (which.equals("block")) {
                // an if / else if / while always keeps a condition
                if (c == null) return st;
                return st.withCommand((st.kind() == Step.Kind.ELSE ? "else if" : st.word()) + " " + c.text());
            }
            return switch (which) {
                case "if" -> st.withConds(c, st.unlessCond(), st.untilCond());
                case "unless" -> st.withConds(st.ifCond(), c, st.untilCond());
                default -> st.withConds(st.ifCond(), st.unlessCond(), c);
            };
        }

        void sync(Step st) {
            Cond cur = get(st);
            if (loaded && Objects.equals(cur, seen)) return;
            loaded = true;
            seen = cur;
            form = CondForm.of(cur);
            flat = form != null;
            if (form == null) form = new CondForm();
            ids.clear();
            byId.clear();
            for (CondForm.Clause c : form.clauses) {
                String id = which + "-" + (++counter);
                ids.add(id);
                byId.put(id, c);
            }
        }
    }

    private static CondState cs(Session s, String id, int k) {
        Row r = s.row(id);
        if (r == null || r.step == null) return new CondState("if");
        CondState c = r.conds[k];
        c.sync(r.step);
        return c;
    }

    /** Writes the clauses into the step; one that isn't complete yet waits (the step keeps its old condition). */
    private static void push(Session s, String id, int k) {
        Row r = s.row(id);
        if (r == null || r.step == null) return;
        CondState c = r.conds[k];
        c.form.clauses.clear();
        for (String cid : c.ids) c.form.clauses.add(c.byId.get(cid));
        try {
            Cond cond = c.form.toCond();
            c.seen = cond;
            s.edit(id, st -> c.put(st, cond));
        } catch (IllegalArgumentException e) {
            // not complete yet: nothing to write
        }
    }

    private static OptionGroup conditionGroup(Session s, String id, int k, String title, String help, BooleanSupplier parsed) {
        BooleanSupplier flat = () -> parsed.getAsBoolean() && cs(s, id, k).flat;
        ListEntries subjects = new ListEntries() {
            @Override
            public List<String> values() {
                List<String> out = new ArrayList<>();
                for (CondForm.Subject x : CondForm.Subject.values()) out.add(x.name());
                return out;
            }

            @Override
            public String label(String v) {
                CondState c = cs(s, id, k);
                CondForm.Clause cl = c.byId.get(v);
                if (cl != null) return clauseText(cl);
                try {
                    return CondForm.Subject.valueOf(v).title;
                } catch (IllegalArgumentException e) {
                    return v;
                }
            }
        };
        Option<List<String>> clauses = Options.ofPageList("Conditions", () -> flat.getAsBoolean() ? List.copyOf(cs(s, id, k).ids) : List.of(),
            List.of(), names -> {
                CondState c = cs(s, id, k);
                List<String> next = new ArrayList<>();
                for (String n : names) {
                    if (c.byId.containsKey(n)) {
                        next.add(n);
                        continue;
                    }
                    CondForm.Subject sub;
                    try {
                        sub = CondForm.Subject.valueOf(n);
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    CondForm.Clause cl = CondForm.Clause.of(sub);
                    String cid = c.which + "-" + (++c.counter);
                    c.byId.put(cid, cl);
                    next.add(cid);
                }
                c.byId.keySet().retainAll(next);
                c.ids.clear();
                c.ids.addAll(next);
                push(s, id, k);
            })
            .entries(subjects).options(cid -> clauseOptions(s, id, k, cid)).visibility(flat::getAsBoolean)
            .description(OptionDescription.ofText(() -> help + " Press + to add a clause.")).build();
        Option<Match> match = Options.ofEnum("Match", () -> cs(s, id, k).form.all ? Match.ALL : Match.ANY, Match.ALL, v -> {
            CondState c = cs(s, id, k);
            c.form.all = v == Match.ALL;
            push(s, id, k);
        }, Match.class).visibility(() -> flat.getAsBoolean() && cs(s, id, k).ids.size() > 1)
            .description(OptionDescription.ofText(() -> "All: every clause must hold. Any: one is enough.")).build();
        Option<String> text = Options.ofString("Condition text", () -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return "";
            Cond c = cs(s, id, k).get(r.step);
            return c == null ? "" : c.text();
        }, "", v -> {
            Row r = s.row(id);
            if (r == null || r.step == null) return;
            Cond c;
            try {
                c = v == null || v.isBlank() ? null : Cond.parse(v);
            } catch (IllegalArgumentException e) {
                reply.accept("Condition not changed: " + e.getMessage());
                return;
            }
            CondState st = cs(s, id, k);
            s.edit(id, x -> st.put(x, c));
        }).visibility(parsed::getAsBoolean).description(OptionDescription.ofText(() -> help
            + " Advanced: type it, e.g. inv.free>4 and not night (and, or, not, brackets).")).build();
        return OptionGroup.builder(title).option(clauses).option(match).option(text).build();
    }

    private static String clauseText(CondForm.Clause c) {
        try {
            String what = c.subject.title;
            if (c.subject.needsName()) what += " " + (c.subject == CondForm.Subject.VARIABLE ? "$" : "") + c.item;
            String rest = "";
            if (c.subject.numeric()) rest = " " + Compare.of(c.op).name().toLowerCase(Locale.ROOT).replace('_', ' ') + " " + c.number;
            else if (c.subject.percentOnly()) rest = " " + c.number + "%";
            else if (!c.subject.words().isEmpty()) rest = " " + c.word;
            return (c.negate ? "not " : "") + what + rest;
        } catch (RuntimeException e) {
            return c.subject.title;
        }
    }

    /** Common effect names for the effect dropdown. */
    private static final List<String> EFFECTS = List.of("speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health",
        "instant_damage", "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance", "water_breathing", "invisibility",
        "blindness", "night_vision", "hunger", "weakness", "poison", "wither", "health_boost", "absorption", "saturation", "glowing",
        "levitation", "luck", "unluck", "slow_falling", "conduit_power", "dolphins_grace", "bad_omen", "hero_of_the_village", "darkness");

    private static List<Option<?>> clauseOptions(Session s, String id, int k, String cid) {
        BooleanSupplier alive = () -> cs(s, id, k).byId.containsKey(cid);
        java.util.function.Function<Void, CondForm.Clause> cl = v -> cs(s, id, k).byId.get(cid);
        java.util.function.Function<CondForm.NameKind, BooleanSupplier> nameIs = kind -> () -> alive.getAsBoolean() && cl.apply(null).subject.nameKind() == kind;
        BooleanSupplier needsItem = nameIs.apply(CondForm.NameKind.ITEM);
        BooleanSupplier target = nameIs.apply(CondForm.NameKind.TARGET);
        BooleanSupplier numeric = () -> alive.getAsBoolean() && cl.apply(null).subject.numeric();
        BooleanSupplier hasNumber = () -> alive.getAsBoolean() && (cl.apply(null).subject.numeric() || cl.apply(null).subject.percentOnly());
        java.util.function.Function<CondForm.Subject, BooleanSupplier> is = sub -> () -> alive.getAsBoolean() && cl.apply(null).subject == sub;
        List<Option<?>> out = new ArrayList<>();
        out.add(Options.ofBoolean("Not", () -> alive.getAsBoolean() && cl.apply(null).negate, false, v -> {
            cl.apply(null).negate = v;
            push(s, id, k);
        }).visibility(alive::getAsBoolean).description(OptionDescription.ofText(() -> "Turns the clause around: holds when it does not.")).build());
        out.add(Options.ofEnum("What", () -> alive.getAsBoolean() ? cl.apply(null).subject : CondForm.Subject.INV_ITEM, CondForm.Subject.INV_ITEM, v -> {
            CondForm.Clause c = cl.apply(null);
            c.subject = v;
            c.reset();
            push(s, id, k);
        }, CondForm.Subject.class).visibility(alive::getAsBoolean)
            .description(OptionDescription.ofText(() -> "What to look at: items, health, time, weather, distance to a target, variables, the last step...")).build());
        out.add(single("Item", ListEntries.items(), () -> alive.getAsBoolean() && cl.apply(null).subject.needsItem() ? withNamespace(cl.apply(null).item) : "", pick -> {
            cl.apply(null).item = stripNamespace(pick);
            push(s, id, k);
        }, needsItem, "Which item."));
        ListEntries targetNames = new ListEntries() {
            @Override
            public List<String> values() {
                return targets.get();
            }
        };
        out.add(single("Target", targetNames, () -> {
            if (!alive.getAsBoolean()) return "";
            String it = cl.apply(null).item;
            return it.startsWith("@") ? it.substring(1) : "";
        }, pick -> {
            cl.apply(null).item = "@" + pick;
            push(s, id, k);
        }, target, "A saved target (Targets page) to measure the distance to."));
        out.add(Options.ofString("Or coordinates (x,y,z)", () -> {
            if (!alive.getAsBoolean()) return "";
            String it = cl.apply(null).item;
            return it.startsWith("@") ? "" : it;
        }, "", v -> {
            if (v == null || v.isBlank()) return;
            cl.apply(null).item = v.trim().replace(" ", "");
            push(s, id, k);
        }).visibility(target::getAsBoolean).description(OptionDescription.ofText(() -> "Write x,y,z (no spaces) to measure to a place instead of a target.")).build());
        out.add(Options.ofButton("Use my position", () -> {
            int[] p = playerPos.get();
            if (p == null) {
                reply.accept("Not in a world: no position to take");
                return;
            }
            cl.apply(null).item = p[0] + "," + p[1] + "," + p[2];
            push(s, id, k);
        }).visibility(target::getAsBoolean).description(OptionDescription.ofText(() -> "Measure to where you stand now.")).build());
        ListEntries effects = new ListEntries() {
            @Override
            public List<String> values() {
                return EFFECTS;
            }
        };
        out.add(single("Effect", effects, () -> alive.getAsBoolean() && cl.apply(null).subject == CondForm.Subject.HAS_EFFECT ? cl.apply(null).item : "", pick -> {
            cl.apply(null).item = pick;
            push(s, id, k);
        }, is.apply(CondForm.Subject.HAS_EFFECT), "Which potion effect."));
        out.add(Options.ofString("Variable name", () -> alive.getAsBoolean() && cl.apply(null).subject == CondForm.Subject.VARIABLE ? cl.apply(null).item : "", "", v -> {
            if (v == null || v.isBlank()) return;
            cl.apply(null).item = v.trim().replaceFirst("^\\$", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
            push(s, id, k);
        }).visibility(is.apply(CondForm.Subject.VARIABLE)::getAsBoolean).description(OptionDescription.ofText(() -> "A variable set with Set variable (no $ needed).")).build());
        out.add(Options.ofEnum("Comparison", () -> alive.getAsBoolean() ? Compare.of(cl.apply(null).op) : Compare.AT_LEAST, Compare.AT_LEAST, v -> {
            cl.apply(null).op = v.op;
            push(s, id, k);
        }, Compare.class).visibility(numeric::getAsBoolean).build());
        out.add(Options.ofNumber("Value", () -> {
            if (!alive.getAsBoolean()) return 0;
            CondForm.Clause c = cl.apply(null);
            return Math.max(c.subject == CondForm.Subject.Y_LEVEL ? -64 : 0, Math.min(c.subject.max(), c.number));
        }, 0, v -> {
            cl.apply(null).number = v;
            push(s, id, k);
        }).range(-64, 100000, 1).visibility(hasNumber::getAsBoolean)
            .description(OptionDescription.ofText(() -> "The number to compare with (for Random chance: the percent).")).build());
        out.add(Options.ofEnum("Time", () -> alive.getAsBoolean() && cl.apply(null).word.equals("day") ? TimeOfDay.DAY : TimeOfDay.NIGHT,
            TimeOfDay.NIGHT, v -> {
                cl.apply(null).word = v.name().toLowerCase(Locale.ROOT);
                push(s, id, k);
            }, TimeOfDay.class).visibility(is.apply(CondForm.Subject.TIME)::getAsBoolean).build());
        out.add(Options.ofEnum("Dimension", () -> {
            if (!alive.getAsBoolean()) return Dim.OVERWORLD;
            return switch (cl.apply(null).word) {
                case "nether" -> Dim.NETHER;
                case "end" -> Dim.END;
                default -> Dim.OVERWORLD;
            };
        }, Dim.OVERWORLD, v -> {
            cl.apply(null).word = v.name().toLowerCase(Locale.ROOT);
            push(s, id, k);
        }, Dim.class).visibility(is.apply(CondForm.Subject.DIMENSION)::getAsBoolean).build());
        out.add(Options.ofEnum("Weather", () -> {
            if (!alive.getAsBoolean()) return Weather.CLEAR;
            return switch (cl.apply(null).word) {
                case "rain" -> Weather.RAIN;
                case "thunder" -> Weather.THUNDER;
                default -> Weather.CLEAR;
            };
        }, Weather.CLEAR, v -> {
            cl.apply(null).word = v.name().toLowerCase(Locale.ROOT);
            push(s, id, k);
        }, Weather.class).visibility(is.apply(CondForm.Subject.WEATHER)::getAsBoolean).build());
        out.add(Options.ofEnum("Result", () -> alive.getAsBoolean() && cl.apply(null).word.equals("succeeded") ? LastStep.SUCCEEDED : LastStep.FAILED,
            LastStep.FAILED, v -> {
                cl.apply(null).word = v.name().toLowerCase(Locale.ROOT);
                push(s, id, k);
            }, LastStep.class).visibility(is.apply(CondForm.Subject.LAST_STEP)::getAsBoolean)
            .description(OptionDescription.ofText(() -> "How the step that ran just before went (after its retries).")).build());
        return out;
    }

    // ---- settings that hold for one step ---------------------------------------------------------------------------------------

    private static OptionGroup settingsGroup(Session s, String id, BooleanSupplier parsed) {
        List<String> all = new ArrayList<>();
        for (Setting<?> st : LazuliConfig.SETTINGS.all()) all.add(st.name());
        all.sort(String::compareToIgnoreCase);
        ListEntries names = new ListEntries() {
            @Override
            public List<String> values() {
                return all;
            }

            @Override
            public String label(String v) {
                Row r = s.row(id);
                String val = r == null || r.step == null ? null : r.step.with().get(v);
                return prettyName(v) + (val == null ? "" : " = " + val);
            }
        };
        Option<List<String>> list = Options.ofPageList("Settings for this step", () -> {
            Row r = s.row(id);
            return r == null || r.step == null ? List.of() : List.copyOf(r.step.with().keySet());
        }, List.of(), picked -> s.edit(id, st -> {
            Map<String, String> next = new LinkedHashMap<>();
            for (String n : picked) {
                String have = st.with().get(n);
                if (have != null) {
                    next.put(n, have);
                } else {
                    var set = LazuliConfig.SETTINGS.byName(n);
                    if (set.isPresent()) next.put(set.get().name(), set.get().format());
                }
            }
            return st.withSettings(next);
        })).entries(names).options(name -> settingValue(s, id, name)).visibility(parsed::getAsBoolean)
            .description(OptionDescription.ofText(() -> "Settings that change only while this step runs and go back afterwards. "
                + "Press + and search for one.")).build();
        return OptionGroup.builder("Settings for this step").option(list).build();
    }

    private static List<Option<?>> settingValue(Session s, String id, String name) {
        var found = LazuliConfig.SETTINGS.byName(name);
        if (found.isEmpty()) return List.of();
        Setting<?> set = found.get();
        BooleanSupplier alive = () -> {
            Row r = s.row(id);
            return r != null && r.step != null && r.step.with().containsKey(set.name());
        };
        Supplier<String> value = () -> {
            Row r = s.row(id);
            String v = r == null || r.step == null ? null : r.step.with().get(set.name());
            return v == null ? set.format() : v;
        };
        Consumer<String> write = v -> s.edit(id, st -> {
            Map<String, String> next = new LinkedHashMap<>(st.with());
            next.put(set.name(), v);
            return st.withSettings(next);
        });
        Object def = set.defaultValue();
        String label = prettyName(set.name());
        if (def instanceof Boolean) {
            return List.of(Options.ofBoolean(label, () -> value.get().equalsIgnoreCase("true"), (Boolean) def, v -> write.accept(String.valueOf(v)))
                .visibility(alive::getAsBoolean).build());
        }
        if (def instanceof Integer d) {
            int min = set.min() == null ? -1000000 : set.min().intValue(), max = set.max() == null ? 1000000 : set.max().intValue();
            return List.of(Options.ofNumber(label, () -> {
                try {
                    return Math.max(min, Math.min(max, Integer.parseInt(value.get())));
                } catch (NumberFormatException e) {
                    return d;
                }
            }, d, v -> write.accept(String.valueOf(v))).range(min, max, 1).visibility(alive::getAsBoolean).build());
        }
        if (def instanceof Double d) {
            double min = set.min() == null ? -1e6 : set.min().doubleValue(), max = set.max() == null ? 1e6 : set.max().doubleValue();
            double step = max - min > 50 ? 1.0 : 0.1;
            return List.of(Options.ofNumber(label, () -> {
                try {
                    return Math.max(min, Math.min(max, Double.parseDouble(value.get())));
                } catch (NumberFormatException e) {
                    return d;
                }
            }, d, v -> write.accept(String.valueOf(v))).range(min, max, step).visibility(alive::getAsBoolean).build());
        }
        return List.of(Options.ofString(label, value, set.format(), v -> {
            if (v != null && !v.isBlank()) write.accept(v.trim());
        }).visibility(alive::getAsBoolean).description(OptionDescription.ofText(() -> "A setting that is text; typed as in #set.")).build());
    }

    /** "mineDropJunk" -> "Mine drop junk". */
    private static String prettyName(String camel) {
        String spaced = camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return spaced.isEmpty() ? camel : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    /** Test hook: how many steps the session for a routine holds. */
    static int stepCount(String name) {
        Session s = session(name);
        s.sync();
        return s.model.size();
    }
}
