package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.List;

/**
 * The block structure of a routine's steps: which {@code end} closes which {@code if} / {@code while} / {@code repeat},
 * where the next {@code else} of a chain is, which loop a {@code break} leaves. The steps stay a flat list (labels and
 * jumps keep working); this only says how the blocks nest. An {@code end} with no open block is the old step that ends
 * the routine.
 */
public final class Program {
    private final List<Step> steps;
    /** For an if / else if: the next clause (else if, else or end); -1 otherwise. */
    private final int[] next;
    /** For a header or else clause: the closing end. */
    private final int[] end;
    /** For a closing end or else clause: the header that opened the block. */
    private final int[] head;
    /** For break / continue: the loop they belong to. */
    private final int[] loop;
    private final boolean[] closer;
    private final int[] depth;

    private Program(List<Step> steps) {
        this.steps = steps;
        int n = steps.size();
        next = new int[n];
        end = new int[n];
        head = new int[n];
        loop = new int[n];
        closer = new boolean[n];
        depth = new int[n];
        java.util.Arrays.fill(next, -1);
        java.util.Arrays.fill(end, -1);
        java.util.Arrays.fill(head, -1);
        java.util.Arrays.fill(loop, -1);
    }

    private static final class Open {
        final int idx;
        final Step.Kind kind;
        int last;
        boolean sawElse;
        final List<Integer> clauses = new ArrayList<>();

        Open(int idx, Step.Kind kind) {
            this.idx = idx;
            this.kind = kind;
            this.last = idx;
        }
    }

    /**
     * Works out the structure. {@code lines} gives each step's line number in the routine's text (null: the step's
     * position); mistakes are added to {@code problems} as "step N: ..." and the program is as far as it got.
     */
    public static Program build(List<Step> steps, int[] lines, List<String> problems) {
        Program p = new Program(List.copyOf(steps));
        List<Open> stack = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            int at = lines == null ? i + 1 : lines[i];
            p.depth[i] = stack.size();
            switch (s.kind()) {
                case IF, WHILE, REPEAT -> stack.add(new Open(i, s.kind()));
                case ELSE -> {
                    Open o = stack.isEmpty() ? null : stack.get(stack.size() - 1);
                    if (o == null || o.kind != Step.Kind.IF) {
                        problems.add("step " + at + ": '" + s.text() + "' without an open 'if' (else belongs right after the if's steps)");
                    } else if (o.sawElse) {
                        problems.add("step " + at + ": '" + s.text() + "' after the 'else' of the same if");
                    } else {
                        p.next[o.last] = i;
                        o.last = i;
                        o.clauses.add(i);
                        p.head[i] = o.idx;
                        p.depth[i] = stack.size() - 1;
                        if (s.args().isEmpty()) o.sawElse = true;
                    }
                }
                case END -> {
                    if (stack.isEmpty()) break; // the old step: ends the routine
                    Open o = stack.remove(stack.size() - 1);
                    p.depth[i] = stack.size();
                    if (s.hasModifiers()) problems.add("step " + at + ": the 'end' of a block can't take @modifiers");
                    p.closer[i] = true;
                    p.head[i] = o.idx;
                    p.end[o.idx] = i;
                    if (o.kind == Step.Kind.IF) p.next[o.last] = i;
                    for (int c : o.clauses) p.end[c] = i;
                }
                case BREAK, CONTINUE -> {
                    int found = -1;
                    for (int k = stack.size() - 1; k >= 0 && found < 0; k--) {
                        if (stack.get(k).kind != Step.Kind.IF) found = stack.get(k).idx;
                    }
                    if (found < 0) problems.add("step " + at + ": '" + s.word() + "' outside a loop (it belongs inside a while or repeat)");
                    p.loop[i] = found;
                }
                default -> { }
            }
        }
        for (Open o : stack) {
            Step s = steps.get(o.idx);
            int at = lines == null ? o.idx + 1 : lines[o.idx];
            problems.add("step " + at + ": '" + s.text() + "' is never closed (add an 'end' line after its steps)");
        }
        return p;
    }

    /** Checks the structure; throws IllegalArgumentException with the first problem ("step N: ..."). */
    public static Program of(List<Step> steps, int[] lines) {
        List<String> problems = new ArrayList<>();
        Program p = build(steps, lines, problems);
        if (!problems.isEmpty()) throw new IllegalArgumentException(problems.get(0));
        return p;
    }

    public List<Step> steps() {
        return steps;
    }

    public int size() {
        return steps.size();
    }

    /** True when step {@code i} is an {@code end} that closes a block (not the one that ends the routine). */
    public boolean closes(int i) {
        return closer[i];
    }

    /** True for steps that open a block: if, while, repeat. */
    public boolean opens(int i) {
        Step.Kind k = steps.get(i).kind();
        return k == Step.Kind.IF || k == Step.Kind.WHILE || k == Step.Kind.REPEAT;
    }

    /** The next clause of an if chain (else if, else or the end), -1 when none. */
    public int nextClause(int i) {
        return next[i];
    }

    /** The end closing the block that step {@code i} opens or continues, -1 when none. */
    public int endOf(int i) {
        return end[i];
    }

    /** The header that opened the block an end or else belongs to. */
    public int headOf(int i) {
        return head[i];
    }

    /** The loop header a break or continue leaves. */
    public int loopOf(int i) {
        return loop[i];
    }

    /** How many blocks deep step {@code i} sits (an else, and an end, at the level of their header). */
    public int depth(int i) {
        return depth[i];
    }
}
