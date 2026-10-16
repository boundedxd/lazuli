package dev.lazuli.mod;

import dev.lazuli.core.settings.Settings;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commands from other players (chatControl): a listed player says or whispers a line starting with the prefix and
 * it runs as if typed here. Signed chat gives the sender; servers that send chat as plain game messages are read as
 * {@code <name> text} or {@code name whispers to you: text}.
 */
public final class ChatControl {
    private static final Pattern PUBLIC = Pattern.compile("^<([A-Za-z0-9_]{1,16})> (.*)$");
    private static final Pattern WHISPER = Pattern.compile("^([A-Za-z0-9_]{1,16}) whispers to you: (.*)$");

    private ChatControl() {}

    public static void register(Settings settings, Consumer<String> run) {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            if (sender == null) return;
            String text = signed != null ? signed.signedContent() : message.getString();
            handle(settings, run, sender.name(), text);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) return;
            String line = message.getString();
            Matcher m = PUBLIC.matcher(line);
            if (!m.matches()) m = WHISPER.matcher(line);
            if (m.matches()) handle(settings, run, m.group(1), m.group(2));
        });
    }

    private static void handle(Settings settings, Consumer<String> run, String name, String text) {
        if (!settings.chatControl.get() || text == null) return;
        var me = Minecraft.getInstance().player;
        if (me == null || name.equalsIgnoreCase(me.getGameProfile().name())) return; // own lines are handled as typed
        String prefix = settings.prefix.get();
        text = text.trim();
        if (!text.startsWith(prefix)) return;
        boolean allowed = Arrays.stream(settings.chatControlPlayers.get().split(","))
            .anyMatch(n -> !n.isEmpty() && n.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
        if (allowed) run.accept(text);
    }
}
