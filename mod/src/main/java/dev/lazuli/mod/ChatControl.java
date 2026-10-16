package dev.lazuli.mod;

import dev.lazuli.core.chat.Chat;
import dev.lazuli.core.settings.Settings;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Commands from other players (chatControl): a listed player says or whispers a line starting with the prefix and
 * it runs as if typed here. Signed chat gives the sender; servers that send chat as plain lines ({@code <name> text},
 * {@code [Rank] name » text}, {@code name whispers to you: text}...) are read by the first online player's name in
 * the line, with only punctuation between it and the command (so nobody can type someone else's name to pass).
 */
public final class ChatControl {
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{3,16}");
    private static final Pattern WHISPER = Pattern.compile("\\s*(whispers( to you)?|->\\s*me|→\\s*me)?\\s*[:»>\\]|)\\s-]*\\s*",
        Pattern.CASE_INSENSITIVE);

    private ChatControl() {}

    public static void register(Settings settings, Consumer<String> run, Consumer<String> chat) {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) -> {
            if (sender == null) return;
            String text = signed != null ? signed.signedContent() : message.getString();
            handle(settings, run, chat, sender.name(), text);
        });
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !settings.chatControl.get()) return;
            String line = message.getString();
            int at = line.indexOf(settings.prefix.get());
            if (at <= 0) return;
            String head = line.substring(0, at);
            Set<String> online = online();
            Matcher m = WORD.matcher(head);
            while (m.find()) {
                if (!online.contains(m.group().toLowerCase(Locale.ROOT))) continue;
                // the first player named: the sender, if only punctuation (or "whispers to you:") follows
                if (WHISPER.matcher(head.substring(m.end())).matches()) handle(settings, run, chat, m.group(), line.substring(at));
                return;
            }
        });
    }

    private static Set<String> online() {
        Set<String> out = new HashSet<>();
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null) for (var info : conn.getOnlinePlayers()) out.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        return out;
    }

    private static void handle(Settings settings, Consumer<String> run, Consumer<String> chat, String name, String text) {
        if (!settings.chatControl.get() || text == null) return;
        var me = Minecraft.getInstance().player;
        if (me == null || name.equalsIgnoreCase(me.getGameProfile().name())) return; // own lines are handled as typed
        text = text.trim();
        if (!text.startsWith(settings.prefix.get())) return;
        boolean allowed = Arrays.stream(settings.chatControlPlayers.get().split(","))
            .anyMatch(n -> !n.isBlank() && n.trim().equalsIgnoreCase(name));
        if (!allowed) {
            chat.accept(Chat.warn("Ignored {} from {} (not in Chat control players)", text, name));
            return;
        }
        chat.accept(Chat.info("{}: {}", name, text));
        run.accept(text);
    }
}
