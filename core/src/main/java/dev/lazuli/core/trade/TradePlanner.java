package dev.lazuli.core.trade;

import dev.lazuli.core.inventory.ItemCount;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Which trades get the wanted items, from villagers whose offers are known: the cheapest offers first (price, then
 * distance), paid from the inventory. Short of emeralds, it can sell things for them first (whatever's cheapest to
 * get: what's in the inventory or chests before what has to be mined or crafted); other things a trade needs (the
 * book for an enchanted book) are gathered first.
 */
public final class TradePlanner {
    public static final String EMERALD = "minecraft:emerald";
    /** Rough work per emerald it has to pay (to compare offers by): about what a cheap selling trade takes. */
    private static final double EMERALD_COST = 4;
    /** Work per block of walking to the villager. */
    private static final double WALK = 0.01;
    /** Gather costs this high (or more) mean there's no way. */
    private static final double NO_WAY = 1e9;

    /** Get {@code item} first (GATHER), or trade with {@code trader} {@code times} times (TRADE). */
    public record Step(Kind kind, Trader trader, Offer offer, int times, ItemCount item) {
        public enum Kind { GATHER, TRADE }

        static Step gather(String item, int n) {
            return new Step(Kind.GATHER, null, null, 0, new ItemCount(item, n));
        }

        static Step trade(Trader t, Offer o, int times) {
            return new Step(Kind.TRADE, t, o, times, null);
        }
    }

    /** @param soldOut some of what's wanted is sold out now (a restock would get the rest) */
    public record Plan(List<Step> steps, String problem, boolean soldOut) {
        public boolean ok() {
            return problem == null;
        }

        static Plan fail(String problem) {
            return new Plan(List.of(), problem, false);
        }
    }

    private TradePlanner() {}

    /**
     * @param haveWant    how many of a want the inventory holds already
     * @param have        plain item counts in the inventory
     * @param traders     the known villagers in this dimension
     * @param sell        sell things for emeralds when short of them
     * @param gatherCost  rough work to get one of an item (infinite: no way)
     */
    public static Plan plan(List<Want> wants, ToIntFunction<Want> haveWant, Map<String, Integer> have, List<Trader> traders,
                            int px, int py, int pz, boolean sell, ToDoubleFunction<String> gatherCost) {
        return plan(wants, haveWant, have, traders, px, py, pz, sell, gatherCost, false);
    }

    /** @param partial short of stock: plan what's for sale now (see {@link Plan#soldOut}) instead of failing */
    public static Plan plan(List<Want> wants, ToIntFunction<Want> haveWant, Map<String, Integer> have, List<Trader> traders,
                            int px, int py, int pz, boolean sell, ToDoubleFunction<String> gatherCost, boolean partial) {
        boolean soldOut = false;
        Map<String, Integer> inv = new HashMap<>(have);
        Map<String, Integer> uses = new HashMap<>(); // offer key -> times still possible
        Map<String, Integer> costs = new LinkedHashMap<>();
        List<Step> buys = new ArrayList<>();
        for (Want w : wants) {
            int missing = w.count() - haveWant.applyAsInt(w);
            if (missing <= 0) continue;
            List<Step> cands = new ArrayList<>();
            boolean offered = false;
            for (Trader t : traders) {
                for (Offer o : t.offers()) {
                    if (!o.gives(w)) continue;
                    offered = true;
                    if (left(uses, t, o) > 0) cands.add(Step.trade(t, o, 0));
                }
            }
            if (cands.isEmpty()) {
                if (offered && partial) {
                    soldOut = true;
                    continue;
                }
                return Plan.fail(offered ? w.describe() + " is sold out" : "no villager I've seen sells " + w.describe());
            }
            cands.sort(Comparator.comparingDouble(s -> price(s.offer(), inv, gatherCost) / s.offer().result().count()
                + Math.sqrt(s.trader().distSq(px, py, pz)) * WALK));
            for (Step c : cands) {
                if (missing <= 0) break;
                Offer o = c.offer();
                int times = Math.min(left(uses, c.trader(), o), ceil(missing, o.result().count()));
                uses.merge(key(c.trader(), o), -times, Integer::sum);
                missing -= times * o.result().count();
                costs.merge(o.costA().item(), o.costA().count() * times, Integer::sum);
                if (o.costB() != null) costs.merge(o.costB().item(), o.costB().count() * times, Integer::sum);
                buys.add(Step.trade(c.trader(), o, times));
            }
            if (missing > 0 && partial) soldOut = true;
            else if (missing > 0) return Plan.fail("only " + (w.count() - haveWant.applyAsInt(w) - missing) + " " + w.describe() + " for sale");
        }
        // paid from the inventory first
        Map<String, Integer> gather = new LinkedHashMap<>();
        int emeralds = 0;
        for (Map.Entry<String, Integer> e : costs.entrySet()) {
            int has = inv.getOrDefault(e.getKey(), 0), use = Math.min(has, e.getValue());
            inv.put(e.getKey(), has - use);
            int shortBy = e.getValue() - use;
            if (shortBy <= 0) continue;
            if (e.getKey().equals(EMERALD)) {
                emeralds = shortBy;
            } else if (gatherCost.applyAsDouble(e.getKey()) >= NO_WAY) {
                return Plan.fail("the trade takes " + shortBy + " more " + Offer.name(e.getKey()) + " and I can't get any");
            } else {
                gather.merge(e.getKey(), shortBy, Integer::sum);
            }
        }
        List<Step> sells = new ArrayList<>();
        if (emeralds > 0) {
            if (!sell) return Plan.fail("it takes " + emeralds + " more emeralds (tradeForEmeralds is off)");
            List<Step> sellers = new ArrayList<>();
            for (Trader t : traders) {
                for (Offer o : t.offers()) {
                    if (o.result().item().equals(EMERALD) && !o.costA().item().equals(EMERALD) && o.costB() == null
                        && left(uses, t, o) > 0) sellers.add(Step.trade(t, o, 0));
                }
            }
            while (emeralds > 0) {
                Step best = null;
                double bestCost = NO_WAY;
                for (Step s : sellers) {
                    Offer o = s.offer();
                    if (left(uses, s.trader(), o) <= 0) continue;
                    String in = o.costA().item();
                    double unit = inv.getOrDefault(in, 0) >= o.costA().count() ? 0.01 : gatherCost.applyAsDouble(in);
                    if (unit >= NO_WAY) continue;
                    double c = unit * o.costA().count() / o.result().count() + Math.sqrt(s.trader().distSq(px, py, pz)) * WALK;
                    if (c < bestCost) {
                        bestCost = c;
                        best = s;
                    }
                }
                if (best == null) {
                    return Plan.fail("it takes " + emeralds + " more emeralds and no villager I've seen buys anything I can get");
                }
                Offer o = best.offer();
                int times = Math.min(left(uses, best.trader(), o), ceil(emeralds, o.result().count()));
                uses.merge(key(best.trader(), o), -times, Integer::sum);
                emeralds -= times * o.result().count();
                String in = o.costA().item();
                int needIn = o.costA().count() * times, has = inv.getOrDefault(in, 0), use = Math.min(has, needIn);
                inv.put(in, has - use);
                if (needIn > use) gather.merge(in, needIn - use, Integer::sum);
                sells.add(Step.trade(best.trader(), o, times));
            }
        }
        List<Step> steps = new ArrayList<>();
        gather.forEach((i, n) -> steps.add(Step.gather(i, n)));
        steps.addAll(sells);
        steps.addAll(buys);
        return new Plan(List.copyOf(steps), null, soldOut);
    }

    /**
     * Selling {@code count} of {@code item} (at most what the inventory has) for emeralds: the best-paying buyers first
     * (emeralds per item, then nearest), whole trades only.
     */
    public static Plan sell(String item, int count, Map<String, Integer> have, List<Trader> traders, int px, int py, int pz) {
        List<Step> buyers = new ArrayList<>();
        for (Trader t : traders) {
            for (Offer o : t.offers()) {
                if (o.costA().item().equals(item) && o.costB() == null && o.result().item().equals(EMERALD) && o.left() > 0) {
                    buyers.add(Step.trade(t, o, 0));
                }
            }
        }
        if (buyers.isEmpty()) return Plan.fail("no villager I've seen buys " + Offer.name(item) + " (or they're sold out)");
        buyers.sort(Comparator.comparingDouble((Step s) -> -(double) s.offer().result().count() / s.offer().costA().count())
            .thenComparingDouble(s -> s.trader().distSq(px, py, pz)));
        int left = Math.min(count, have.getOrDefault(item, 0));
        List<Step> steps = new ArrayList<>();
        for (Step b : buyers) {
            int per = b.offer().costA().count(), times = Math.min(b.offer().left(), left / per);
            if (times <= 0) continue;
            steps.add(Step.trade(b.trader(), b.offer(), times));
            left -= times * per;
        }
        if (steps.isEmpty()) {
            return Plan.fail("not enough " + Offer.name(item) + " for one trade (" + buyers.get(0).offer().costA().count() + " each)");
        }
        return new Plan(List.copyOf(steps), null, false);
    }

    /** Every known offer of {@code w} (sold out too), the cheapest per item first, then the nearest. */
    public static List<Step> find(Want w, List<Trader> traders, int px, int py, int pz) {
        List<Step> out = new ArrayList<>();
        for (Trader t : traders) for (Offer o : t.offers()) if (o.gives(w)) out.add(Step.trade(t, o, 0));
        out.sort(Comparator.comparingDouble((Step s) -> (worth(s.offer().costA()) + worth(s.offer().costB())) / s.offer().result().count())
            .thenComparingDouble(s -> s.trader().distSq(px, py, pz)));
        return out;
    }

    /** Emeralds count whole, anything else (a book, sticks) a little. */
    private static double worth(ItemCount c) {
        return c == null ? 0 : c.item().equals(EMERALD) ? c.count() : c.count() * 0.25;
    }

    /** Rough work to pay for one trade of {@code o}. */
    private static double price(Offer o, Map<String, Integer> inv, ToDoubleFunction<String> gatherCost) {
        return price(o.costA(), inv, gatherCost) + (o.costB() == null ? 0 : price(o.costB(), inv, gatherCost));
    }

    private static double price(ItemCount c, Map<String, Integer> inv, ToDoubleFunction<String> gatherCost) {
        boolean has = inv.getOrDefault(c.item(), 0) >= c.count();
        double unit = c.item().equals(EMERALD) ? EMERALD_COST : Math.min(NO_WAY, gatherCost.applyAsDouble(c.item()));
        return c.count() * (has ? unit / 4 : unit);
    }

    private static int left(Map<String, Integer> uses, Trader t, Offer o) {
        return uses.computeIfAbsent(key(t, o), k -> o.left());
    }

    private static String key(Trader t, Offer o) {
        return t.id() + "#" + o.index();
    }

    private static int ceil(int a, int b) {
        return (a + b - 1) / b;
    }
}
