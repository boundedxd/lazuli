package dev.lazuli.mod.config;

import dev.lazuli.core.settings.Setting;
import dev.lazuli.core.settings.Settings;
import main.walksy.lib.api.config.option.Category;
import main.walksy.lib.api.config.option.Option;
import main.walksy.lib.api.config.option.OptionDescription;
import main.walksy.lib.api.config.option.OptionGroup;
import main.walksy.lib.api.config.option.Options;
import main.walksy.lib.api.config.value.ListEntries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The small toolkit every config tab is written with: one widget per {@link Setting} (label from the setting's name,
 * the description kept on the setting so {@code #settings <filter>} shows it too), groups and tabs. Every setting that
 * gets a widget is noted, so a setting forgotten in the GUI shows up in the log ({@link #missing}).
 */
public final class Ui {
    private Ui() {}

    /** Names of the settings that have a widget. */
    private static final Set<String> USED = new HashSet<>();

    /** Labels that read better than the name made into words. */
    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("tradeScanUnknown", "Scan strangers"),
        Map.entry("tradeOnlyKnown", "Only known villagers"),
        Map.entry("tradeStayOpen", "Keep screen open"),
        Map.entry("tradeMaxEmeralds", "Max emeralds per trade"),
        Map.entry("tradeRestockMinutes", "Restock wait (min)"),
        Map.entry("tradeMaxPerRun", "Max trades per run"),
        Map.entry("tradeMinRate", "Min items per item paid"),
        Map.entry("tradeNeverSell", "Never sell"),
        Map.entry("tradeSellOnly", "Sell only"),
        Map.entry("tradeKeepItems", "Keep items"),
        Map.entry("tradeStayPut", "Don't walk"),
        Map.entry("tradeWaitRestock", "Wait for restocks"),
        Map.entry("tradeWaitDay", "Wait up to a day"),
        Map.entry("allowInventory", "Move tools to hotbar"),
        Map.entry("openInventory", "Open inventory for clicks"),
        Map.entry("mineSneak", "Sneak while breaking"),
        Map.entry("makeRoomDropJunk", "Throw junk out"),
        Map.entry("makeRoomDeposit", "Deposit non-essentials"),
        Map.entry("mineDropJunk", "Mine: throw junk out"),
        Map.entry("perServerMemory", "Memory per server"),
        Map.entry("evalHorizon", "Evaluation horizon"),
        Map.entry("unstickRestart", "Restart planner when stuck"),
        Map.entry("renderPath", "Show path"),
        Map.entry("renderGhost", "Ghost view"),
        Map.entry("renderWaypoints", "Show waypoints"),
        Map.entry("hudCard", "Material status card"),
        Map.entry("minimap", "Show minimap"));

    // ---- one widget per setting ----

    public static Option<Boolean> bool(Setting<Boolean> s, String description) {
        USED.add(s.name());
        s.describe(description);
        return Options.ofBoolean(label(s), s::get, s.defaultValue(), s::set)
            .description(OptionDescription.ofText(() -> description)).build();
    }

    public static Option<Integer> num(Setting<Integer> s, int min, int max, int step, String description) {
        USED.add(s.name());
        s.describe(description);
        return Options.ofNumber(label(s), s::get, s.defaultValue(), s::set)
            .range(min, max, step).description(OptionDescription.ofText(() -> description)).build();
    }

    public static Option<Double> dec(Setting<Double> s, double min, double max, double step, String description) {
        USED.add(s.name());
        s.describe(description);
        return Options.ofNumber(label(s), s::get, s.defaultValue(), s::set)
            .range(min, max, step).description(OptionDescription.ofText(() -> description)).build();
    }

    /** A free text setting (a comma list, a prefix...); a value the setting refuses keeps the previous one. */
    public static Option<String> text(Setting<String> s, String description) {
        USED.add(s.name());
        s.describe(description);
        return Options.ofString(label(s), s::get, s.defaultValue(), v -> {
            try {
                s.parse(v);
            } catch (IllegalArgumentException ignored) {
                // keep the previous value
            }
        }).description(OptionDescription.ofText(() -> description)).build();
    }

    /** A comma-separated id setting as a picker list (ids get their namespace so the picker knows them). */
    public static Option<List<String>> idList(Setting<String> s, ListEntries entries, boolean reorderable, String description) {
        USED.add(s.name());
        s.describe(description);
        var b = Options.ofSelectionList(label(s), () -> ids(s.get()), ids(s.defaultValue()),
            v -> s.parse(String.join(",", v)), entries);
        if (reorderable) b = b.reorderable();
        return b.description(OptionDescription.ofText(() -> description)).build();
    }

    /** A comma-separated list of player names, picked from who's online or typed. */
    public static Option<List<String>> nameList(Setting<String> s, ListEntries entries, String description) {
        USED.add(s.name());
        s.describe(description);
        return Options.ofSelectionList(label(s), () -> names(s.get()), names(s.defaultValue()),
            v -> s.parse(String.join(",", v)), entries).description(OptionDescription.ofText(() -> description)).build();
    }

    public static Option<Runnable> button(String label, Runnable action, String description) {
        return Options.ofButton(label, action).description(OptionDescription.ofText(() -> description)).build();
    }

    // ---- layout ----

    /** A section, open. */
    public static OptionGroup group(String name, Option<?>... options) {
        return build(name, true, options);
    }

    /** A section, folded away until opened (rare options, one section per process...). */
    public static OptionGroup folded(String name, Option<?>... options) {
        return build(name, false, options);
    }

    private static OptionGroup build(String name, boolean open, Option<?>... options) {
        OptionGroup.Builder b = OptionGroup.builder(name).expanded(open);
        for (Option<?> o : options) b.option(o);
        return b.build();
    }

    public static Category tab(String name, OptionGroup... groups) {
        Category.Builder b = Category.builder(name);
        for (OptionGroup g : groups) b.group(g);
        return b.build();
    }

    // ---- bookkeeping ----

    /** Settings with no widget (nothing in the config screen changes them). */
    public static List<String> missing(Settings settings) {
        List<String> out = new ArrayList<>();
        for (Setting<?> s : settings.all()) if (!USED.contains(s.name())) out.add(s.name());
        return out;
    }

    /** "allowSprint" -> "Allow sprint" (or the nicer label above). */
    private static String label(Setting<?> s) {
        String l = LABELS.get(s.name());
        return l != null ? l : pretty(s.name());
    }

    public static String pretty(String camel) {
        String spaced = camel.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    public static List<String> names(String csv) {
        return java.util.Arrays.stream(csv.split(",")).filter(x -> !x.isBlank()).toList();
    }

    public static List<String> ids(String csv) {
        return java.util.Arrays.stream(csv.split(",")).filter(x -> !x.isBlank())
            .map(x -> x.contains(":") ? x : "minecraft:" + x).toList();
    }
}
