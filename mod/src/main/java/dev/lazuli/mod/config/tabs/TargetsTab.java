package dev.lazuli.mod.config.tabs;

import dev.lazuli.core.settings.Settings;
import dev.lazuli.core.target.Target;
import dev.lazuli.core.target.Targets;
import dev.lazuli.mod.config.RoutineEditor;
import dev.lazuli.mod.config.Ui;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.option.Option;
import main.walksy.lib.api.config.option.OptionDescription;
import main.walksy.lib.api.config.option.Options;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The saved targets (named chests, villagers, stations...): pick mode, the list (rename, delete, add one where you
 * stand) and for each one its kind and position, a go-there button and a move-here button.
 */
public final class TargetsTab {
    private TargetsTab() {}

    /** Set by the client: this world's targets (null outside a world), its dimension, and a way to run a chat command. */
    public static volatile Supplier<Targets> store = () -> null;
    public static volatile Supplier<String> dimension = () -> "overworld";
    public static volatile Consumer<String> command = c -> {};

    public static Category build(Settings s) {
        return Ui.tab("Targets",
            Ui.group("Picking",
                Ui.button("Pick mode on/off", () -> command.accept("#pick"),
                    "While it's on, shift+right-click a chest, villager, furnace, brewing stand... in the world to save it as a target "
                        + "(the click doesn't open it). Then use it as @name in commands and routine steps."),
                Ui.button("Forget all targets", () -> command.accept("#target clear"), "Delete every saved target of this world.")),
            Ui.group("Targets",
                Options.ofPageList("Targets", TargetsTab::names, List.of(), TargetsTab::setNames)
                    .newEntryName("target")
                    .noSpaces()
                    .onRemoved(n -> {
                        Targets t = store.get();
                        if (t != null) t.remove(n);
                    })
                    .onRenamed((from, to) -> {
                        Targets t = store.get();
                        if (t != null) {
                            try {
                                t.rename(from, to);
                            } catch (IllegalArgumentException e) {
                                command.accept("#target show " + from); // says what the target is called still
                            }
                        }
                    })
                    .options(TargetsTab::page)
                    .description(OptionDescription.ofText(() -> "Names of the saved targets. + adds one where you stand now; the name can be changed; "
                        + "open one to see where it is or send Lazuli there.")).build()));
    }

    private static List<String> names() {
        Targets t = store.get();
        List<String> out = new ArrayList<>();
        if (t != null) for (Target x : t.all()) out.add(x.name());
        return out;
    }

    /** The list edited: a name that's new becomes a block target where the player stands. */
    private static void setNames(List<String> names) {
        Targets t = store.get();
        int[] p = RoutineEditor.playerPos.get();
        if (t == null) return;
        for (String n : names) {
            if (t.get(n) != null) continue;
            if (p == null) continue;
            try {
                t.pick(n, Target.Kind.BLOCK, dimension.get(), p[0], p[1], p[2], null, "minecraft:air", "", null);
            } catch (IllegalArgumentException ignored) {
                // a name that can't be used (a routine word): the list shows it as missing
            }
        }
    }

    private static List<Option<?>> page(String name) {
        List<Option<?>> out = new ArrayList<>();
        out.add(Options.ofString("Where", () -> {
            Target t = find(name);
            return t == null ? "" : t.coords() + (t.dim().equals("overworld") ? "" : " [" + t.dim() + "]");
        }, "", v -> {})
            .availability(() -> false, "Position of the target")
            .description(OptionDescription.ofText(() -> "Where it is (x y z).")).build());
        out.add(Options.ofEnum("Kind", () -> {
            Target t = find(name);
            return t == null ? Target.Kind.BLOCK : t.kind();
        }, Target.Kind.BLOCK, k -> {
            Target t = find(name);
            Targets st = store.get();
            if (t == null || st == null) return;
            st.put(new Target(t.name(), k, t.dim(), t.x(), t.y(), t.z(), t.uuid(), t.block(), t.label()));
        }, Target.Kind.class).description(OptionDescription.ofText(() -> "What it is: chest (container), villager, station (furnace, brewing stand...), block or entity.")).build());
        out.add(Options.ofString("Detail", () -> {
            Target t = find(name);
            if (t == null) return "";
            String what = t.block() != null ? t.block().replace("minecraft:", "") : t.label();
            return what == null ? "" : what;
        }, "", v -> {})
            .availability(() -> false, "What kind of block or entity it is")
            .description(OptionDescription.ofText(() -> "The block or the profession / mob type, as it was when picked.")).build());
        out.add(Options.ofButton("Go there", () -> command.accept("#target goto " + name))
            .description(OptionDescription.ofText(() -> "Walk to it (next to it).")).build());
        out.add(Options.ofButton("Move to my position", () -> {
            Target t = find(name);
            Targets st = store.get();
            int[] p = RoutineEditor.playerPos.get();
            if (t == null || st == null || p == null) return;
            st.put(t.at(p[0], p[1], p[2]));
        }).description(OptionDescription.ofText(() -> "Put the target where you stand now (for one that moved, or was added by name).")).build());
        out.add(Options.ofButton("Copy name for routines", () -> RoutineEditor.reply.accept("Write @" + name + " in a command or routine step"))
            .description(OptionDescription.ofText(() -> "Tells you how to use it in a command or routine step.")).build());
        return out;
    }

    private static Target find(String name) {
        Targets t = store.get();
        return t == null ? null : t.get(name);
    }
}
