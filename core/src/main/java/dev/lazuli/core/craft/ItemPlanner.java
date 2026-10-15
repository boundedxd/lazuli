package dev.lazuli.core.craft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * #getitem's planner: the steps (mine, craft, smelt) that get an item from what's in the inventory, picking for each
 * item the cheapest way to get it and making the tools, fuel, crafting table and furnace that takes. The mod runs the
 * first step and plans again from the real inventory, so the plan only has to be right, not exact.
 */
public final class ItemPlanner {
    public enum Kind { MINE, CRAFT, SMELT }

    /** Mine {@code count} of {@code blocks} for {@code item}; craft or smelt {@code count} more of {@code item}. */
    public record Action(Kind kind, String item, int count, List<String> blocks) {
        @Override
        public String toString() {
            return kind + " " + count + " " + item + (blocks.isEmpty() ? "" : " from " + blocks);
        }
    }

    /**
     * The steps in order, or why it can't be done; {@code keep}: how many of each item the plan uses, so nothing else
     * spends them (bridging with the cobblestone it's collecting...).
     */
    public record Plan(List<Action> actions, String problem, Map<String, Integer> keep) {
        public Plan(List<Action> actions, String problem) {
            this(actions, problem, Map.of());
        }

        public boolean ok() {
            return problem == null;
        }
    }

    /** What it may do: mine, smelt, place a crafting table, place a furnace. */
    public record Rules(boolean mine, boolean smelt, boolean placeTable, boolean placeFurnace) {
        public static final Rules ALL = new Rules(true, true, true, true);
    }

    /** Pickaxes by tier: 1 wood, 2 stone, 3 iron, 4 diamond. */
    static final String[] PICKS = {null, "minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe",
        "minecraft:diamond_pickaxe"};
    private static final String TABLE = "minecraft:crafting_table", FURNACE = "minecraft:furnace";
    private static final double INF = 1e12;
    private static final int DEPTH = 16;

    private final Recipes recipes;
    private final Drops drops;
    private final ToIntFunction<String> toolTier, burn;
    private final List<String> fuels;
    private final Rules rules;

    /**
     * @param toolTier for a block id, the pickaxe tier it needs to drop anything (0: none)
     * @param burn     for an item id, its fuel ticks (0: not a fuel)
     * @param fuels    fuels it may use, best first
     */
    public ItemPlanner(Recipes recipes, Drops drops, ToIntFunction<String> toolTier, ToIntFunction<String> burn,
                       List<String> fuels, Rules rules) {
        this.recipes = recipes;
        this.drops = drops;
        this.toolTier = toolTier;
        this.burn = burn;
        this.fuels = fuels;
        this.rules = rules;
    }

    /**
     * Steps to have at least {@code count} of {@code item}.
     *
     * @param have        plain items in the inventory
     * @param pickTier    the best pickaxe it has (0: none)
     * @param tableNear   a crafting table is near
     * @param furnaceNear a furnace is near
     */
    public Plan plan(String item, int count, Map<String, Integer> have, int pickTier, boolean tableNear, boolean furnaceNear) {
        Run r = new Run(have, pickTier, tableNear, furnaceNear);
        try {
            r.get(item, count, 0);
            return new Plan(List.copyOf(r.actions), null, Map.copyOf(r.keep));
        } catch (Stuck e) {
            return new Plan(List.of(), e.getMessage());
        }
    }

    private static final class Stuck extends RuntimeException {
        Stuck(String msg) {
            super(msg, null, false, false);
        }
    }

    private static String name(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    /** One planning run: the inventory as the steps change it. */
    private final class Run {
        final Map<String, Integer> have, reserved = new HashMap<>(), startHave;
        final Map<String, Double> costs = new HashMap<>();
        /** Every item a step asked for, and how many (see {@link Plan#keep}). */
        final Map<String, Integer> keep = new HashMap<>();
        final Set<String> making = new HashSet<>(), costing = new HashSet<>();
        final List<Action> actions = new ArrayList<>();
        int pickTier;
        boolean tableNear, furnaceNear;
        /** While trying out alternatives (see getAny): plain gets inside, no trying out again. */
        int tryingOut;

        Run(Map<String, Integer> have, int pickTier, boolean tableNear, boolean furnaceNear) {
            this.have = new HashMap<>(have);
            this.startHave = have;
            this.pickTier = pickTier;
            this.tableNear = tableNear;
            this.furnaceNear = furnaceNear;
        }

        int free(String item) {
            return have.getOrDefault(item, 0) - reserved.getOrDefault(item, 0);
        }

        void reserve(String item, int n) {
            reserved.merge(item, n, Integer::sum);
        }

        /** Makes sure at least {@code n} of {@code item} are free (not set aside for a step being planned). */
        void get(String item, int n, int depth) {
            keep.merge(item, n, Integer::sum);
            int need = n - free(item);
            if (need <= 0) return;
            if (depth > DEPTH) throw new Stuck("Too many steps to get " + name(item));
            if (!making.add(item)) throw new Stuck("Going round in circles on " + name(item));
            try {
                // cheapest way first; one that comes back round to this item (raw iron from a raw iron block made of
                // raw iron) or can't be done: the next way
                double[] cost = {mineCost(item, 0), smeltCost(item, 0), craftCost(item, 0)};
                Integer[] order = {0, 1, 2};
                java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> cost[i]));
                if (cost[order[0]] >= INF) throw new Stuck("No way to get " + name(item) + why(item));
                Stuck first = null;
                for (int k = 0; k < 3; k++) {
                    int way = order[k];
                    if (cost[way] >= INF) break;
                    Snap before = save();
                    try {
                        switch (way) {
                            case 0 -> mine(item, need, depth);
                            case 1 -> smelt(item, need, depth);
                            // only recipes cheaper than the next way (a few nuggets don't make digging for an iron block
                            // worth it over smelting)
                            default -> craft(item, need, depth, k < 2 ? cost[order[k + 1]] : INF);
                        }
                        return;
                    } catch (Stuck e) {
                        restore(before);
                        if (first == null) first = e;
                    }
                }
                throw first;
            } finally {
                making.remove(item);
            }
        }

        String why(String item) {
            for (SmeltRecipe r : recipes.smelting(item)) {
                if (!rules.smelt()) return " (smelting is off)";
                for (String in : r.input()) return " (it's smelted from " + name(in) + ", and there's no way to get that)";
            }
            if (!drops.blocksDropping(item).isEmpty() && !rules.mine()) return " (mining is off)";
            if (!tableNear && !rules.placeTable() && recipes.producing(item).stream().anyMatch(r -> !r.fits(2)))
                return " (it needs a crafting table: none near, and placing one is off)";
            return "";
        }

        // ---- the three ways ----

        void mine(String item, int n, int depth) {
            List<String> blocks = mineable(item);
            int need = Integer.MAX_VALUE;
            for (String b : blocks) need = Math.min(need, toolTier.applyAsInt(b));
            if (need > pickTier) {
                if (need >= PICKS.length) throw new Stuck("Can't mine " + name(item) + " with any pickaxe it knows");
                get(PICKS[need], 1, depth + 1);
                pickTier = need;
            }
            int tier = pickTier;
            List<String> usable = blocks.stream().filter(b -> toolTier.applyAsInt(b) <= tier).toList();
            have.merge(item, n, Integer::sum);
            // one trip for all of it: add to an earlier mining step for the same item, or one that takes these blocks
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                if (a.kind() == Kind.MINE && (a.item().equals(item) || a.blocks().containsAll(usable))) {
                    actions.set(i, new Action(Kind.MINE, item, a.count() + n, a.blocks()));
                    return;
                }
            }
            actions.add(new Action(Kind.MINE, item, n, usable));
        }

        void smelt(String item, int n, int depth) {
            Set<String> inputs = smeltInputs(item);
            String input = cheapest(inputs, n, depth);
            getAny(inputs, input, n, depth);
            reserve(input, n);
            // fuel: what it has from the list, else the cheapest fuel to get
            Map<String, Integer> fuel = Smelting.fuel(fuels, freeMap(), burn, n);
            if (fuel == null) {
                String best = null;
                double bestCost = INF;
                for (String f : fuels) {
                    int b = burn.applyAsInt(f);
                    if (b <= 0) continue;
                    double c = cost(f, depth) * Math.ceil((double) n * Smelting.ITEM_TICKS / b);
                    if (c < bestCost) {
                        bestCost = c;
                        best = f;
                    }
                }
                if (best == null) throw new Stuck("No fuel to smelt " + name(item) + " with");
                get(best, (int) Math.ceil((double) n * Smelting.ITEM_TICKS / burn.applyAsInt(best)) + free(best), depth + 1);
                fuel = Smelting.fuel(fuels, freeMap(), burn, n);
                if (fuel == null) throw new Stuck("Not enough fuel to smelt " + name(item));
            }
            fuel.forEach(this::reserve);
            if (!furnaceNear) station(FURNACE, rules.placeFurnace(), "furnace", depth);
            reserved.merge(input, -n, Integer::sum);
            fuel.forEach((f, k) -> reserved.merge(f, -k, Integer::sum));
            have.merge(input, -n, Integer::sum);
            fuel.forEach((f, k) -> have.merge(f, -k, Integer::sum));
            have.merge(item, n, Integer::sum);
            actions.add(new Action(Kind.SMELT, item, n, List.of()));
        }

        /** Crafts with the cheapest recipe that works, of those costing under {@code limit}. */
        void craft(String item, int n, int depth, double limit) {
            List<Recipe> rs = new ArrayList<>(recipes.producing(item));
            rs.sort(java.util.Comparator.comparingDouble(r -> recipeCost(r, depth)));
            Stuck first = null;
            for (Recipe r : rs) {
                double c = recipeCost(r, depth);
                if (c >= INF || c > limit && first != null) break;
                Snap before = save();
                try {
                    craft(item, n, r, depth);
                    return;
                } catch (Stuck e) {
                    restore(before);
                    if (first == null) first = e;
                }
            }
            throw first != null ? first : new Stuck("No way to craft " + name(item));
        }

        void craft(String item, int n, Recipe r, int depth) {
            if (!r.fits(2) && !tableNear) station(TABLE, rules.placeTable(), "crafting table", depth);
            int crafts = (n + r.count() - 1) / r.count();
            Map<Set<String>, Integer> groups = new LinkedHashMap<>();
            for (Set<String> c : r.cells()) if (!c.isEmpty()) groups.merge(c, 1, Integer::sum);
            Map<String, Integer> mine = new HashMap<>();
            for (var g : groups.entrySet()) {
                int k = crafts * g.getValue();
                String o = cheapest(g.getKey(), k, depth);
                getAny(g.getKey(), o, k, depth);
                reserve(o, k);
                mine.merge(o, k, Integer::sum);
            }
            mine.forEach((o, k) -> reserved.merge(o, -k, Integer::sum));
            // the inventory after it, as #craft will do it
            CraftTree.Result res = CraftTree.resolve(recipes, item, n, freeMap(), true);
            if (!res.ok()) throw new Stuck("Can't work out crafting " + name(item));
            Map<String, Integer> next = new HashMap<>(res.left()); // what's set aside stays on top of what's left
            reserved.forEach((k, v) -> next.merge(k, v, Integer::sum));
            have.clear();
            have.putAll(next);
            actions.add(new Action(Kind.CRAFT, item, n, List.of()));
        }

        /**
         * Gets {@code k} of {@code rep}, chosen from {@code options}. Options just as cheap (any kind of planks, any
         * log...) are planned too, and where they come out the same apart from what's mined, the mining takes any of
         * their blocks: "mine 3 logs" rather than "mine 3 oak logs". The next plan goes on from whichever it got.
         */
        void getAny(Set<String> options, String rep, int k, int depth) {
            double c = cost(rep, depth);
            List<String> ties = free(rep) >= k || tryingOut > 0 ? List.of()
                : options.stream().filter(o -> !o.equals(rep) && Math.abs(cost(o, depth) - c) < 1e-9).toList();
            if (ties.isEmpty()) {
                get(rep, k, depth + 1);
                return;
            }
            Snap before = save();
            get(rep, k, depth + 1);
            Snap after = save();
            List<Set<String>> wide = new ArrayList<>();
            for (Action a : actions) wide.add(new java.util.LinkedHashSet<>(a.blocks()));
            tryingOut++;
            try {
                for (String alt : ties) {
                    restore(before);
                    try {
                        get(alt, k, depth + 1);
                    } catch (Stuck e) {
                        continue;
                    }
                    if (actions.size() != after.actions.size()) continue;
                    boolean same = true;
                    for (int i = 0; i < actions.size() && same; i++) {
                        Action a = actions.get(i), b = after.actions.get(i);
                        same = a.kind() == b.kind() && a.count() == b.count();
                    }
                    if (!same) continue;
                    for (int i = 0; i < actions.size(); i++) if (actions.get(i).kind() == Kind.MINE) wide.get(i).addAll(actions.get(i).blocks());
                }
            } finally {
                tryingOut--;
            }
            restore(after);
            for (int i = 0; i < actions.size(); i++) {
                Action a = actions.get(i);
                if (a.kind() == Kind.MINE && wide.get(i).size() > a.blocks().size()) {
                    actions.set(i, new Action(Kind.MINE, a.item(), a.count(), List.copyOf(wide.get(i))));
                }
            }
        }

        /** Everything a plan changes, to try something and go back. */
        record Snap(Map<String, Integer> have, Map<String, Integer> reserved, List<Action> actions, Map<String, Double> costs,
                    int pickTier, boolean tableNear, boolean furnaceNear, Map<String, Integer> keep) {}

        Snap save() {
            return new Snap(new HashMap<>(have), new HashMap<>(reserved), new ArrayList<>(actions), new HashMap<>(costs),
                pickTier, tableNear, furnaceNear, new HashMap<>(keep));
        }

        void restore(Snap s) {
            have.clear();
            have.putAll(s.have());
            reserved.clear();
            reserved.putAll(s.reserved());
            actions.clear();
            actions.addAll(s.actions());
            costs.clear();
            costs.putAll(s.costs());
            pickTier = s.pickTier();
            tableNear = s.tableNear();
            furnaceNear = s.furnaceNear();
            keep.clear();
            keep.putAll(s.keep());
        }

        /** A crafting table or furnace to place, made if it has none. */
        void station(String block, boolean mayPlace, String what, int depth) {
            if (!mayPlace) throw new Stuck("Needs a " + what + ": none near, and placing one is off");
            get(block, 1, depth + 1);
            have.merge(block, -1, Integer::sum); // placed
            if (block.equals(TABLE)) tableNear = true;
            else furnaceNear = true;
        }

        Map<String, Integer> freeMap() {
            Map<String, Integer> out = new HashMap<>();
            have.forEach((k, v) -> {
                int f = v - reserved.getOrDefault(k, 0);
                if (f > 0) out.put(k, f);
            });
            return out;
        }

        /** Of these, one it has {@code n} free of, else the cheapest to get (on a tie, one it's already mining). */
        String cheapest(Set<String> options, int n, int depth) {
            for (String o : options) if (free(o) >= n) return o;
            String best = null;
            double bestCost = INF;
            for (String o : options) {
                double c = cost(o, depth);
                for (Action a : actions) if (a.kind() == Kind.MINE && a.item().equals(o)) c -= 0.5;
                if (best == null || c < bestCost) {
                    bestCost = c;
                    best = o;
                }
            }
            return best;
        }

        // ---- costs: rough work per item, to choose between ways ----

        double cost(String item, int depth) {
            if (making.contains(item)) return INF; // what's being made can't go into making itself
            Double known = costs.get(item);
            if (known != null) return known;
            if (depth > DEPTH || !costing.add(item)) return INF;
            double c = Math.min(mineCost(item, depth), Math.min(smeltCost(item, depth), craftCost(item, depth)));
            costing.remove(item);
            // some in the inventory: cheaper (use what it has), but not free, or a few nuggets look like a way to make
            // ingots and it ends up digging for an iron block to make the rest
            if (startHave.getOrDefault(item, 0) > 0) c = c >= INF ? 1 : c / 4;
            costs.put(item, c);
            return c;
        }

        double mineCost(String item, int depth) {
            if (!rules.mine()) return INF;
            double best = INF;
            for (String b : mineable(item)) {
                int t = toolTier.applyAsInt(b);
                double tool = t > pickTier ? t < PICKS.length ? cost(PICKS[t], depth + 1) : INF : 0;
                best = Math.min(best, Drops.abundance(b) + tool);
            }
            return best;
        }

        double smeltCost(String item, int depth) {
            if (!rules.smelt()) return INF;
            double best = INF;
            for (String in : smeltInputs(item)) best = Math.min(best, cost(in, depth + 1) + 2);
            // a furnace to make first counts too
            if (best < INF && !furnaceNear && startHave.getOrDefault(FURNACE, 0) < 1) {
                best += rules.placeFurnace() ? cost(FURNACE, depth + 1) : INF;
            }
            return best;
        }

        double craftCost(String item, int depth) {
            double best = INF;
            for (Recipe r : recipes.producing(item)) best = Math.min(best, recipeCost(r, depth));
            return best;
        }

        double recipeCost(Recipe r, int depth) {
            double sum = 1;
            for (Set<String> cell : r.cells()) {
                if (cell.isEmpty()) continue;
                double c = INF;
                for (String o : cell) c = Math.min(c, cost(o, depth + 1));
                sum += c;
                if (sum >= INF) return INF;
            }
            if (!r.fits(2) && !tableNear && !rules.placeTable()) return INF;
            return sum / r.count();
        }

        /** Blocks to mine for {@code item}: the usual ones, or any if that's all there is. */
        List<String> mineable(String item) {
            List<String> all = drops.blocksDropping(item);
            List<String> known = all.stream().filter(b -> Drops.abundance(b) < Drops.UNKNOWN).toList();
            return known.isEmpty() ? all : known;
        }

        Set<String> smeltInputs(String item) {
            Set<String> out = new java.util.LinkedHashSet<>();
            for (SmeltRecipe r : recipes.smelting(item)) if (r.furnace() == SmeltRecipe.Furnace.FURNACE) out.addAll(r.input());
            return out;
        }
    }
}
