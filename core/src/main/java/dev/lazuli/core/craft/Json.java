package dev.lazuli.core.craft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Just enough JSON for the game's data files: objects are Maps, arrays Lists, numbers Doubles. */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value();
        j.space();
        if (j.i != text.length()) throw j.error("trailing text");
        return v;
    }

    private Object value() {
        space();
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> word("true", Boolean.TRUE);
            case 'f' -> word("false", Boolean.FALSE);
            case 'n' -> word("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> out = new LinkedHashMap<>();
        i++;
        space();
        if (peek('}')) return out;
        do {
            space();
            String key = string();
            space();
            expect(':');
            out.put(key, value());
            space();
        } while (peek(','));
        expect('}');
        return out;
    }

    private List<Object> array() {
        List<Object> out = new ArrayList<>();
        i++;
        space();
        if (peek(']')) return out;
        do out.add(value()); while (skipSpaceAnd(','));
        space();
        expect(']');
        return out;
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c != '\\') {
                b.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> b.append(e);
            }
        }
        expect('"');
        return b.toString();
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character");
        return Double.parseDouble(s.substring(start, i));
    }

    private Object word(String w, Object v) {
        if (!s.startsWith(w, i)) throw error("unexpected character");
        i += w.length();
        return v;
    }

    private void space() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private boolean skipSpaceAnd(char c) {
        space();
        return peek(c);
    }

    private boolean peek(char c) {
        if (i < s.length() && s.charAt(i) == c) {
            i++;
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!peek(c)) throw error("expected " + c);
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("Bad JSON at " + i + ": " + what);
    }
}
