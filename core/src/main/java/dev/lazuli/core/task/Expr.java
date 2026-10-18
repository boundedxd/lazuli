package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Number expressions for routine variables: {@code set n = $n + 1}, {@code set want = inv.iron_ingot * 2 - 5},
 * {@code repeat $count}. Numbers, {@code $variables}, the numeric condition names (inv.free, health, chest.string...),
 * {@code + - * / %} and brackets. Dividing by zero gives 0.
 */
public final class Expr {
    private Expr() {
    }

    /** Throws IllegalArgumentException with a readable message when {@code text} isn't an expression. */
    public static void check(String text) {
        parse(text);
    }

    public static double eval(String text, Cond.World w) {
        return parse(text).eval(w);
    }

    /** The {@code $names} an expression reads. */
    public static List<String> variables(String text) {
        List<String> out = new ArrayList<>();
        for (String t : tokens(Cond.normalize(text))) if (t.startsWith("$") && t.length() > 1) out.add(t.substring(1).toLowerCase(Locale.ROOT));
        return out;
    }

    private interface Node {
        double eval(Cond.World w);
    }

    private static Node parse(String text) {
        List<String> t = tokens(Cond.normalize(text));
        if (t.isEmpty()) throw new IllegalArgumentException("missing a number or expression");
        int[] at = {0};
        Node n = sum(t, at);
        if (at[0] < t.size()) throw new IllegalArgumentException("unexpected '" + t.get(at[0]) + "' in '" + text.trim() + "'");
        return n;
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if ("+-*/%()".indexOf(c) >= 0) {
                out.add(String.valueOf(c));
                i++;
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(text.charAt(j)) && "+*/%()".indexOf(text.charAt(j)) < 0) {
                    // "$n-1" and "12-3" are sums; a '-' inside any other word (a target name) stays
                    if (text.charAt(j) == '-' && j > i && text.substring(i, j).matches("[0-9.]+|\\$\\w+")) break;
                    j++;
                }
                if (j == i) j = i + 1;
                out.add(text.substring(i, j));
                i = j;
            }
        }
        return out;
    }

    private static Node sum(List<String> t, int[] at) {
        Node left = product(t, at);
        while (at[0] < t.size() && (t.get(at[0]).equals("+") || t.get(at[0]).equals("-"))) {
            boolean plus = t.get(at[0]++).equals("+");
            Node l = left, r = product(t, at);
            left = w -> plus ? l.eval(w) + r.eval(w) : l.eval(w) - r.eval(w);
        }
        return left;
    }

    private static Node product(List<String> t, int[] at) {
        Node left = unary(t, at);
        while (at[0] < t.size() && "*/%".contains(t.get(at[0])) && t.get(at[0]).length() == 1) {
            char op = t.get(at[0]++).charAt(0);
            Node l = left, r = unary(t, at);
            left = w -> {
                double a = l.eval(w), b = r.eval(w);
                return switch (op) {
                    case '*' -> a * b;
                    case '/' -> b == 0 ? 0 : a / b;
                    default -> b == 0 ? 0 : a % b;
                };
            };
        }
        return left;
    }

    private static Node unary(List<String> t, int[] at) {
        if (at[0] >= t.size()) throw new IllegalArgumentException("the expression ends too soon (something missing after an operator)");
        String w = t.get(at[0]);
        if (w.equals("-")) {
            at[0]++;
            Node n = unary(t, at);
            return x -> -n.eval(x);
        }
        if (w.equals("(")) {
            at[0]++;
            Node n = sum(t, at);
            if (at[0] >= t.size() || !t.get(at[0]).equals(")")) throw new IllegalArgumentException("missing ) in the expression");
            at[0]++;
            return n;
        }
        if (w.length() == 1 && "+*/%)".contains(w)) throw new IllegalArgumentException("unexpected '" + w + "' in the expression");
        at[0]++;
        try {
            double d = Double.parseDouble(w);
            return x -> d;
        } catch (NumberFormatException e) {
            // a name
        }
        String lower = w.toLowerCase(Locale.ROOT);
        if (!Cond.numeric(lower))
            throw new IllegalArgumentException("'" + w + "' isn't a number, a $variable or something countable (inv.<item>, inv.free, health...)");
        return x -> Cond.number(lower, x);
    }
}
