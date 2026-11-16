package dev.lazuli.mod;

import com.google.gson.JsonObject;
import dev.lazuli.core.mcp.ScreenBounds;
import dev.lazuli.mod.mixin.EditBoxAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-thread-only access to live widgets, including nested server-dialog controls. */
final class McpScreens {
    private Screen inspected;
    private long version;
    private List<Entry> entries = List.of();
    private record Entry(AbstractWidget widget, int left, int top, int right, int bottom, boolean enabled, String label) {
        boolean visible() { return widget.visible && new ScreenBounds(left, top, right, bottom).visible(); }
    }

    Map<String, Object> inspect(Screen screen) {
        inspected = screen;
        entries = collect(screen);
        version++;
        List<Map<String, Object>> controls = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            AbstractWidget w = entry.widget();
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("id", i);
            control.put("type", w.getClass().getSimpleName());
            control.put("label", entry.label());
            control.put("active", entry.enabled());
            control.put("visible", entry.visible());
            control.put("x", w.getX());
            control.put("y", w.getY());
            control.put("width", w.getWidth());
            control.put("height", w.getHeight());
            if (entry.visible()) control.put("clickPoint", Map.of(
                "x", (entry.left() + entry.right()) / 2.0, "y", (entry.top() + entry.bottom()) / 2.0));
            if (w instanceof EditBox field) {
                control.put("value", field.getValue());
                control.put("editable", ((EditBoxAccessor) field).lazuli$editable());
                control.put("maxLength", ((EditBoxAccessor) field).lazuli$maxLength());
            }
            if (w instanceof MultiLineEditBox field) control.put("value", field.getValue());
            if (w instanceof Checkbox checkbox) control.put("checked", checkbox.selected());
            controls.add(control);
        }
        return Map.of("snapshot", version, "dialog", screen instanceof DialogScreen,
            "width", screen.width, "height", screen.height, "controls", controls);
    }

    Map<String, Object> click(Minecraft mc, JsonObject args) {
        Screen screen = checked(mc, args);
        Entry entry = control(screen, args);
        if (!entry.enabled() || !entry.visible()) throw new IllegalArgumentException("Control is disabled or outside the visible scroll area; scroll and inspect_gui again");
        double x = (entry.left() + entry.right()) / 2.0, y = (entry.top() + entry.bottom()) / 2.0;
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));
        invalidate(); // Actions can replace the entire dialog, even synchronously.
        boolean handled = screen.mouseClicked(event, false);
        if (mc.screen == screen) screen.mouseReleased(event);
        return Map.of("handled", handled, "inspectAgain", true);
    }

    Map<String, Object> text(Minecraft mc, JsonObject args) {
        Screen screen = checked(mc, args);
        Entry entry = control(screen, args);
        if (!entry.enabled() || !entry.visible()) throw new IllegalArgumentException("Text input is disabled or outside the visible scroll area");
        if (!args.has("text") || !args.get("text").isJsonPrimitive() || !args.getAsJsonPrimitive("text").isString())
            throw new IllegalArgumentException("text must be a string");
        String value = args.get("text").getAsString();
        if (entry.widget() instanceof EditBox field) {
            if (!((EditBoxAccessor) field).lazuli$editable()) throw new IllegalArgumentException("Text input is read-only");
            if (value.length() > ((EditBoxAccessor) field).lazuli$maxLength()) throw new IllegalArgumentException("Text exceeds the field's maximum length");
            invalidate();
            field.setValue(value); // Runs the native responder used by dialog input substitution.
            return Map.of("value", field.getValue(), "inspectAgain", true);
        }
        if (entry.widget() instanceof MultiLineEditBox field) {
            invalidate();
            field.setValue(value);
            return Map.of("value", field.getValue(), "inspectAgain", true);
        }
        throw new IllegalArgumentException("Selected control is not a text input");
    }

    Map<String, Object> scroll(Minecraft mc, JsonObject args) {
        Screen screen = checked(mc, args);
        Entry entry = control(screen, args);
        if (!entry.visible()) throw new IllegalArgumentException("Choose a visible control in the area to scroll");
        double amount = args.has("amount") ? args.get("amount").getAsDouble() : -3;
        if (!Double.isFinite(amount) || Math.abs(amount) > 20) throw new IllegalArgumentException("amount must be finite and between -20 and 20");
        double x = (entry.left() + entry.right()) / 2.0, y = (entry.top() + entry.bottom()) / 2.0;
        invalidate();
        boolean handled = screen.mouseScrolled(x, y, 0, amount);
        return Map.of("handled", handled, "inspectAgain", true);
    }

    private Screen checked(Minecraft mc, JsonObject args) {
        if (mc.screen == null || mc.screen != inspected || !args.has("snapshot") || args.get("snapshot").getAsLong() != version)
            throw new IllegalArgumentException("GUI snapshot expired; call inspect_gui again");
        List<Entry> current = collect(mc.screen);
        if (current.size() != entries.size()) throw new IllegalArgumentException("GUI controls changed; call inspect_gui again");
        for (int i = 0; i < current.size(); i++) {
            if (!current.get(i).equals(entries.get(i))) throw new IllegalArgumentException("GUI layout changed; call inspect_gui again");
        }
        return mc.screen;
    }

    private Entry control(Screen screen, JsonObject args) {
        if (!args.has("control")) throw new IllegalArgumentException("control id from inspect_gui is required");
        int id = args.get("control").getAsInt();
        if (id < 0 || id >= entries.size()) throw new IllegalArgumentException("Unknown control id; inspect_gui again");
        return entries.get(id);
    }

    private void invalidate() { inspected = null; entries = List.of(); }

    private static List<Entry> collect(Screen screen) {
        List<Entry> out = new ArrayList<>();
        walk(screen, 0, 0, screen.width, screen.height, true, out, new IdentityHashMap<>());
        return out;
    }

    private static void walk(GuiEventListener node, int left, int top, int right, int bottom, boolean enabled,
                             List<Entry> out, IdentityHashMap<GuiEventListener, Boolean> visited) {
        if (visited.put(node, true) != null) return;
        if (node instanceof AbstractWidget w) {
            ScreenBounds clipped = new ScreenBounds(left, top, right, bottom)
                .clip(new ScreenBounds(w.getX(), w.getY(), w.getRight(), w.getBottom()));
            left = clipped.left(); top = clipped.top(); right = clipped.right(); bottom = clipped.bottom();
            enabled = enabled && w.active && w.visible;
            out.add(new Entry(w, left, top, right, bottom, enabled, w.getMessage().getString()));
        }
        if (node instanceof ContainerEventHandler container)
            for (GuiEventListener child : container.children()) walk(child, left, top, right, bottom, enabled, out, visited);
    }
}
