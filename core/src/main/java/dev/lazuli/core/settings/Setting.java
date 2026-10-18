package dev.lazuli.core.settings;

import java.util.function.Function;

public final class Setting<T> {
    private final String name;
    private final T defaultValue;
    private final Function<String, T> parser;
    private volatile T value;
    private volatile String description = "";

    Setting(String name, T defaultValue, Function<String, T> parser) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.parser = parser;
        this.value = defaultValue;
    }

    public String name() {
        return name;
    }

    /** A short explanation (shown in the config screen and by {@code #settings}); empty when none was given. */
    public String description() {
        return description;
    }

    public Setting<T> describe(String text) {
        this.description = text == null ? "" : text;
        return this;
    }

    public T get() {
        return value;
    }

    public T defaultValue() {
        return defaultValue;
    }

    public void set(T v) {
        this.value = v;
    }

    /** Throws IllegalArgumentException with a readable message on bad input. */
    public void parse(String s) {
        this.value = parser.apply(s);
    }

    public String format() {
        return String.valueOf(value);
    }
}
