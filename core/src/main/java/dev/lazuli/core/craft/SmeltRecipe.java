package dev.lazuli.core.craft;

import java.util.Set;

/** A furnace recipe: one of {@code input} cooks into one {@code result} in {@code cookTicks} in that kind of furnace. */
public record SmeltRecipe(String id, String result, Set<String> input, Furnace furnace, int cookTicks) {
    public enum Furnace {
        FURNACE("minecraft:furnace"), BLAST_FURNACE("minecraft:blast_furnace"), SMOKER("minecraft:smoker");

        /** The block (and item) id. */
        public final String block;

        Furnace(String block) {
            this.block = block;
        }
    }
}
