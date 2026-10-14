package dev.lazuli.core.chat;

/**
 * Chat message colours, as the game's formatting codes. Templates mark values with {@code {}}; values are shown in
 * aqua and the message's own colour resumes after each.
 */
public final class Chat {
    private static final char S = '§';
    public static final String GREEN = S + "a", RED = S + "c", GOLD = S + "6", GRAY = S + "7", AQUA = S + "b",
        YELLOW = S + "e", WHITE = S + "f", DARK_GRAY = S + "8";

    private Chat() {}

    /** Something worked. */
    public static String ok(String template, Object... values) {
        return fmt(GREEN, template, values);
    }

    /** Something failed or was refused. */
    public static String err(String template, Object... values) {
        return fmt(RED, template, values);
    }

    /** Worth noticing, not an error. */
    public static String warn(String template, Object... values) {
        return fmt(GOLD, template, values);
    }

    /** Plain information. */
    public static String info(String template, Object... values) {
        return fmt(GRAY, template, values);
    }

    /** A command's usage line: the command in yellow, what it does in gray. */
    public static String usage(String command, String what) {
        return YELLOW + command + DARK_GRAY + " - " + GRAY + what;
    }

    /** A setting or other name = value pair; booleans green or red. */
    public static String pair(String name, Object value) {
        String v = value instanceof Boolean b ? (b ? GREEN : RED) + b : AQUA + value;
        return YELLOW + name + DARK_GRAY + " = " + v;
    }

    public static String fmt(String base, String template, Object... values) {
        StringBuilder sb = new StringBuilder(base);
        int i = 0, v = 0;
        for (int j; (j = template.indexOf("{}", i)) >= 0; i = j + 2) {
            sb.append(template, i, j).append(AQUA).append(v < values.length ? values[v++] : "{}").append(base);
        }
        return sb.append(template.substring(i)).toString();
    }

    /** The text without colour codes. */
    public static String strip(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == S && i + 1 < s.length()) i++;
            else sb.append(s.charAt(i));
        }
        return sb.toString();
    }
}
