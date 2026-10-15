package dev.lazuli.core.trade;

import java.util.List;

/** A villager (or wandering trader) and its offers, as last seen; {@code id} is its UUID. */
public record Trader(String id, String profession, String dim, int x, int y, int z, List<Offer> offers) {
    public Trader {
        offers = List.copyOf(offers);
    }

    public double distSq(int px, int py, int pz) {
        double dx = x - px, dy = y - py, dz = z - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
