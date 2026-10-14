package dev.lazuli.core.settings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class Settings {
    private final Map<String, Setting<?>> byName = new LinkedHashMap<>();

    public final Setting<Boolean> allowSprint = bool("allowSprint", true);
    public final Setting<Integer> maxFallHeight = integer("maxFallHeight", 3, 0, 20);
    public final Setting<Boolean> cancelOnUserInput = bool("cancelOnUserInput", true);
    public final Setting<Double> rotationSpeed = decimal("rotationSpeed", 30.0, 1.0, 180.0);
    public final Setting<Integer> evalHorizon = integer("evalHorizon", 32, 1, 4096);
    public final Setting<Boolean> renderPath = bool("renderPath", true);
    public final Setting<String> prefix = register(new Setting<>("prefix", "#", s -> {
        if (s.isEmpty() || s.contains(" ")) throw new IllegalArgumentException("prefix must be non-empty and contain no spaces");
        return s;
    }));

    public Collection<Setting<?>> all() {
        return byName.values();
    }

    public Optional<Setting<?>> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Setting<?> s : byName.values()) out.put(s.name(), s.format());
        return out;
    }

    /** Applies stored values, ignoring unknown names and invalid values. */
    public void load(Map<String, String> values) {
        for (Map.Entry<String, String> e : values.entrySet()) {
            byName(e.getKey()).ifPresent(s -> {
                try {
                    s.parse(e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // keep the default
                }
            });
        }
    }

    private <T> Setting<T> register(Setting<T> s) {
        byName.put(s.name().toLowerCase(Locale.ROOT), s);
        return s;
    }

    private Setting<Boolean> bool(String name, boolean def) {
        return register(new Setting<>(name, def, s -> {
            if (s.equalsIgnoreCase("true")) return true;
            if (s.equalsIgnoreCase("false")) return false;
            throw new IllegalArgumentException(name + " must be true or false");
        }));
    }

    private Setting<Integer> integer(String name, int def, int min, int max) {
        return register(new Setting<>(name, def, s -> {
            int v;
            try {
                v = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a whole number");
            }
            if (v < min || v > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            return v;
        }));
    }

    private Setting<Double> decimal(String name, double def, double min, double max) {
        return register(new Setting<>(name, def, s -> {
            double v;
            try {
                v = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " must be a number");
            }
            if (!(v >= min && v <= max)) throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
            return v;
        }));
    }
}
