package dev.lazuli.core.craft;

import java.util.Set;

/** A smithing table upgrade: template + base item + addition make {@code result} (one of each is used up). */
public record SmithRecipe(String id, String result, Set<String> template, Set<String> base, Set<String> addition) {}
