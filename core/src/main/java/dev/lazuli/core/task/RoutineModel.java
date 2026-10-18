package dev.lazuli.core.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the routine editor edits: the steps as rows with ids, and their block structure (if / else / while / repeat ... end)
 * as the screen needs it: depth for indenting, collapsible blocks, and block-aware move, duplicate and delete. A block
 * moves, copies and goes as one; its steps can be kept when it is deleted. Pure, so it is tested without the game.
 */
public final class RoutineModel {
    /** One line of the routine: parsed, or as typed when it doesn't parse. */
    public static final class Row {
        public final String id;
        private String raw;
        private Step step;
        private String error;

        Row(String id, String line) {
            this.id = id;
            set(line);
        }

        void set(String line) {
            raw = line;
            try {
                step = Step.parse(line);
                error = null;
            } catch (IllegalArgumentException e) {
                step = null;
                error = e.getMessage();
            }
        }

        /** The parsed step, null when the line doesn't parse. */
        public Step step() {
            return step;
        }

        public String error() {
            return error;
        }

        public String text() {
            return step != null ? step.text() : raw;
        }

        public void setStep(Step s) {
            step = s;
            raw = s.text();
            error = null;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> collapsed = new HashSet<>();
    private int counter;

    // the structure, worked out again after every change
    private int[] depth = new int[0];
    /** For headers and else rows: the closing end (-1: none). */
    private int[] close = new int[0];
    /** For else rows and ends: the header they belong to. */
    private int[] head = new int[0];
    private boolean[] closer = new boolean[0];

    public RoutineModel() {
    }

    public RoutineModel(List<String> lines) {
        load(lines);
    }

    /** Replaces the rows with these lines (blank ones dropped); ids start over. Collapsed state is kept for ids that stay. */
    public void load(List<String> lines) {
        rows.clear();
        for (String l : lines) if (!l.isBlank()) rows.add(new Row("step-" + (++counter), l));
        collapsed.clear();
        scan();
    }

    public List<String> lines() {
        List<String> out = new ArrayList<>();
        for (Row r : rows) out.add(r.text());
        return out;
    }

    public List<Row> rows() {
        return List.copyOf(rows);
    }

    public int size() {
        return rows.size();
    }

    public Row row(String id) {
        int i = indexOf(id);
        return i < 0 ? null : rows.get(i);
    }

    public int indexOf(String id) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).id.equals(id)) return i;
        return -1;
    }

    /** Sets a row's text (it may stay unparsed); the structure is looked at again. */
    public void setText(String id, String text) {
        Row r = row(id);
        if (r == null) return;
        r.set(text.trim());
        scan();
    }

    /** A row's step was edited (the record changed). */
    public void setStep(String id, Step s) {
        Row r = row(id);
        if (r == null) return;
        r.setStep(s);
        scan();
    }

    /** Everything wrong with the routine as it stands (parse errors, unclosed blocks, unknown names...). */
    public List<String> problems(java.util.function.Predicate<String> knownCommand, java.util.function.Predicate<String> knownTarget) {
        List<String> lines = new ArrayList<>();
        for (Row r : rows) lines.add(r.text());
        return Routine.check(lines, knownCommand, knownTarget);
    }

    // ---- structure ---------------------------------------------------------------------------------------------

    private void scan() {
        int n = rows.size();
        depth = new int[n];
        close = new int[n];
        head = new int[n];
        closer = new boolean[n];
        java.util.Arrays.fill(close, -1);
        java.util.Arrays.fill(head, -1);
        List<Integer> stack = new ArrayList<>();
        java.util.Map<Integer, List<Integer>> clauses = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            Step s = rows.get(i).step;
            Step.Kind k = s == null ? Step.Kind.COMMAND : s.kind();
            depth[i] = stack.size();
            switch (k) {
                case IF, WHILE, REPEAT -> stack.add(i);
                case ELSE -> {
                    if (!stack.isEmpty() && rows.get(stack.get(stack.size() - 1)).step.kind() == Step.Kind.IF) {
                        int h = stack.get(stack.size() - 1);
                        head[i] = h;
                        depth[i] = stack.size() - 1;
                        clauses.computeIfAbsent(h, x -> new ArrayList<>()).add(i);
                    }
                }
                case END -> {
                    if (!stack.isEmpty()) {
                        int h = stack.remove(stack.size() - 1);
                        depth[i] = stack.size();
                        closer[i] = true;
                        head[i] = h;
                        close[h] = i;
                        for (int c : clauses.getOrDefault(h, List.of())) close[c] = i;
                    }
                }
                default -> { }
            }
        }
    }

    private Step.Kind kind(int i) {
        Step s = rows.get(i).step;
        return s == null ? Step.Kind.COMMAND : s.kind();
    }

    /** How many blocks deep a row sits (an else or end at the level of its if). */
    public int depth(String id) {
        int i = indexOf(id);
        return i < 0 ? 0 : depth[i];
    }

    /** True for the row that opens a block (if, while, repeat). */
    public boolean isHeader(String id) {
        int i = indexOf(id);
        if (i < 0) return false;
        Step.Kind k = kind(i);
        return k == Step.Kind.IF || k == Step.Kind.WHILE || k == Step.Kind.REPEAT;
    }

    public boolean isElse(String id) {
        int i = indexOf(id);
        return i >= 0 && kind(i) == Step.Kind.ELSE;
    }

    /** True for an end that closes a block. */
    public boolean isBlockEnd(String id) {
        int i = indexOf(id);
        return i >= 0 && closer[i];
    }

    /** The whole block of a header: [first, last] row indexes, header to end; just the row when it isn't closed. */
    private int[] range(int i) {
        return close[i] >= 0 ? new int[]{i, close[i]} : new int[]{i, i};
    }

    /** The steps inside a block (not its if / else / end lines); 0 for a row that isn't a header. */
    public int childCount(String id) {
        int i = indexOf(id);
        if (i < 0 || !isHeader(id)) return 0;
        int[] r = range(i);
        int n = 0;
        for (int k = r[0] + 1; k < r[1]; k++) if (head[k] != i) n++;
        return n;
    }

    // ---- collapsing --------------------------------------------------------------------------------------------

    public boolean collapsed(String id) {
        return collapsed.contains(id) && isHeader(id);
    }

    public void collapse(String id, boolean on) {
        if (!isHeader(id)) return;
        if (on) collapsed.add(id);
        else collapsed.remove(id);
    }

    public void toggle(String id) {
        collapse(id, !collapsed(id));
    }

    /** Ids of the rows shown: the ones inside a collapsed block are left out (its header stays). */
    public List<String> visibleIds() {
        List<String> out = new ArrayList<>();
        int hiddenUntil = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (i <= hiddenUntil) continue;
            Row r = rows.get(i);
            out.add(r.id);
            if (collapsed.contains(r.id) && isHeader(r.id) && close[i] >= 0) hiddenUntil = close[i];
        }
        return out;
    }

    // ---- adding ------------------------------------------------------------------------------------------------

    /** The lines a picked word adds: a block word gives the header and its end. */
    public static List<String> template(String prefix, String word) {
        String first = Forms.defaultCommand(prefix, word);
        return Forms.blockWord(word) ? List.of(first, "end") : List.of(first);
    }

    /**
     * Adds lines after a row (null: at the end). After a header they go inside it (unless it's collapsed); after an
     * else too. Returns the id of the first new row.
     */
    public String insertAfter(String anchorId, List<String> lines) {
        int at = rows.size();
        int a = anchorId == null ? -1 : indexOf(anchorId);
        if (a >= 0) {
            at = a + 1;
            if (isHeader(anchorId) && collapsed(anchorId) && close[a] >= 0) at = close[a] + 1;
        }
        String first = null;
        for (String l : lines) {
            Row r = new Row("step-" + (++counter), l);
            if (first == null) first = r.id;
            rows.add(at++, r);
        }
        scan();
        return first;
    }

    /** Adds an else (or else if) to the if that {@code ifId} belongs to (null: the last if that has no plain else); null when none fits. */
    public String addElse(String ifId, boolean withCondition) {
        int h = -1;
        if (ifId != null) {
            int i = indexOf(ifId);
            if (i >= 0 && kind(i) == Step.Kind.IF) h = i;
            else if (i >= 0 && head[i] >= 0 && kind(head[i]) == Step.Kind.IF) h = head[i];
        } else {
            for (int i = rows.size() - 1; i >= 0 && h < 0; i--) if (kind(i) == Step.Kind.IF && close[i] >= 0 && (withCondition || plainElse(i) < 0)) h = i;
        }
        if (h < 0 || close[h] < 0) return null;
        int plain = plainElse(h);
        if (plain >= 0 && !withCondition) return null;
        int at = plain >= 0 ? plain : close[h];
        Row r = new Row("step-" + (++counter), withCondition ? "else if night" : "else");
        rows.add(at, r);
        scan();
        return r.id;
    }

    private int plainElse(int ifIdx) {
        for (int c = ifIdx + 1; c < rows.size() && c <= close[ifIdx]; c++) {
            if (head[c] == ifIdx && kind(c) == Step.Kind.ELSE && rows.get(c).step.args().isEmpty()) return c;
        }
        return -1;
    }

    // ---- duplicate, move, delete ------------------------------------------------------------------------------

    /** Copies a row after itself; a header copies its whole block. Else and end rows aren't copied. Returns the first new id. */
    public String duplicate(String id) {
        int i = indexOf(id);
        if (i < 0 || isElse(id) || isBlockEnd(id)) return null;
        int[] r = isHeader(id) ? range(i) : new int[]{i, i};
        List<String> copy = new ArrayList<>();
        for (int k = r[0]; k <= r[1]; k++) copy.add(rows.get(k).text());
        String first = null;
        int at = r[1] + 1;
        for (String l : copy) {
            Row nr = new Row("step-" + (++counter), l);
            if (first == null) first = nr.id;
            rows.add(at++, nr);
        }
        scan();
        return first;
    }

    /** The first and last row of the unit (a single step, or a whole block) that starts at {@code i}; null for an else / end. */
    private int[] unitAt(int i) {
        if (i < 0 || i >= rows.size()) return null;
        Step.Kind k = kind(i);
        if (k == Step.Kind.ELSE || closer[i]) return null;
        if (k == Step.Kind.IF || k == Step.Kind.WHILE || k == Step.Kind.REPEAT) return range(i);
        return new int[]{i, i};
    }

    /** Moves a step (a block as one) up (-1) or down (+1) among the steps at its level; false at the edge of its block. */
    public boolean move(String id, int by) {
        int i = indexOf(id);
        int[] u = unitAt(i);
        if (u == null || by == 0) return false;
        int d = depth[i];
        int[] other;
        if (by < 0) {
            int p = u[0] - 1;
            if (p < 0 || depth[p] != d) return false;
            other = closer[p] ? unitAt(head[p]) : unitAt(p);
        } else {
            int nx = u[1] + 1;
            if (nx >= rows.size() || depth[nx] != d) return false;
            other = unitAt(nx);
        }
        if (other == null) return false;
        int[] first = by < 0 ? other : u, second = by < 0 ? u : other;
        List<Row> a = new ArrayList<>(rows.subList(first[0], first[1] + 1));
        List<Row> b = new ArrayList<>(rows.subList(second[0], second[1] + 1));
        for (int k = first[0]; k <= second[1]; k++) rows.remove(first[0]);
        rows.addAll(first[0], b);
        rows.addAll(first[0] + b.size(), a);
        scan();
        return true;
    }

    /**
     * Deletes a row. A header takes its block with it, or with {@code keepChildren} only its own lines (if / else / end)
     * and the steps inside stay. An else row goes with its steps, or alone with {@code keepChildren}; an end deletes the
     * block it closes like its header does.
     */
    public void delete(String id, boolean keepChildren) {
        int i = indexOf(id);
        if (i < 0) return;
        if (closer[i]) {
            i = head[i];
            id = rows.get(i).id;
        }
        if (isHeader(id)) {
            int[] r = range(i);
            if (r[0] == r[1]) {
                rows.remove(i);
            } else if (keepChildren) {
                List<Integer> own = new ArrayList<>();
                own.add(r[0]);
                own.add(r[1]);
                for (int k = r[0] + 1; k < r[1]; k++) if (head[k] == i) own.add(k);
                own.sort(java.util.Comparator.reverseOrder());
                for (int k : own) rows.remove(k);
            } else {
                for (int k = r[0]; k <= r[1]; k++) rows.remove(r[0]);
            }
            collapsed.remove(id);
        } else if (isElse(id) && head[i] >= 0 && close[i] >= 0) {
            int next = close[i];
            for (int k = i + 1; k < close[i]; k++) if (head[k] == head[i] && kind(k) == Step.Kind.ELSE) {
                next = k;
                break;
            }
            if (keepChildren) rows.remove(i);
            else for (int k = i; k < next; k++) rows.remove(i);
        } else {
            rows.remove(i);
        }
        scan();
    }

    // ---- the list widget ---------------------------------------------------------------------------------------

    /**
     * The list widget handed back its names: ids of rows that stay (in the new order), and picked words that are new
     * steps. Rows hidden inside collapsed blocks stay where they were. A removed header (or else, or end) removes only
     * its own lines and leaves the steps inside.
     */
    public void reconcile(List<String> names, String prefix) {
        List<String> shown = visibleIds();
        Set<String> keep = new HashSet<>();
        for (String n : names) if (row(n) != null) keep.add(n);
        for (String id : shown) {
            if (keep.contains(id)) continue;
            if (isHeader(id) || isElse(id) || isBlockEnd(id)) delete(id, true);
        }
        // order: kept visible rows as given, each followed by the hidden rows that followed it
        List<String> visibleNow = visibleIds();
        List<Row> out = new ArrayList<>();
        java.util.Map<String, List<Row>> hiddenAfter = new java.util.HashMap<>();
        String last = null;
        Set<String> visibleSet = new HashSet<>(visibleNow);
        List<Row> lead = new ArrayList<>();
        for (Row r : rows) {
            if (visibleSet.contains(r.id)) {
                last = r.id;
            } else if (last == null) {
                lead.add(r);
            } else {
                hiddenAfter.computeIfAbsent(last, x -> new ArrayList<>()).add(r);
            }
        }
        out.addAll(lead);
        List<Row> removedAnchors = new ArrayList<>();
        String prev = null;
        List<String[]> fresh = new ArrayList<>(); // anchor id, word
        for (String n : names) {
            Row r = row(n);
            if (r != null && visibleSet.contains(n)) {
                out.add(r);
                List<Row> h = hiddenAfter.get(n);
                if (h != null) out.addAll(h);
                prev = n;
            } else if (r == null && !n.startsWith("step-")) {
                fresh.add(new String[]{prev, n});
            }
        }
        // rows the widget dropped that aren't structure (plain steps) are gone; hidden ones of dropped anchors go with them
        rows.clear();
        rows.addAll(out);
        scan();
        for (String[] f : fresh) {
            String word = f[1];
            if (word.equals("else") || word.equals("else if")) {
                String anchor = f[0];
                if (addElse(anchor, word.equals("else if")) == null) addElse(null, word.equals("else if"));
            } else {
                insertAfter(f[0] != null && row(f[0]) != null ? f[0] : null, template(prefix, word));
            }
        }
        collapsed.removeIf(c -> row(c) == null);
    }
}
