package dev.lazuli.core.settings;

import java.util.function.Function;

public final class Setting<T> {
    private final String name;
    private final T defaultValue;
    private final Function<String, T> parser;
    private volatile T value;

    Setting(String name, T defaultValue, Function<String, T> parser) {
        this.name = name;
        this.defaultValue = defaultValue;
        this.parser = parser;
        this.value = defaultValue;
    }

    public String name() {
        return name;
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
