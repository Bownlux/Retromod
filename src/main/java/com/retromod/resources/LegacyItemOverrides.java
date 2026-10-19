/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Turns a pre-1.21.4 item model's {@code overrides} into a client item definition's model tree. */
final class LegacyItemOverrides {

    /** A tree larger than this is given up rather than written: no real item needs one. */
    private static final int MAX_NODES = 512;

    private enum Kind { CONDITION, MAIN_HAND, CHARGE, RANGE }

    private record Variable(Kind kind, String property, Set<String> names, double scale) {}

    private record Rule(Map<String, Double> predicate, String model) {}

    // The old names each 1.21.4 condition replaces. They translate even when a mod registered them,
    // because that registration only extended vanilla's behavior to the mod's own item.
    private static final Map<String, String> CONDITIONS = Map.of(
            "pulling", "minecraft:using_item",
            "blocking", "minecraft:using_item",
            "throwing", "minecraft:using_item",
            "tooting", "minecraft:using_item",
            "cast", "minecraft:fishing_rod/cast",
            "damaged", "minecraft:damaged",
            "broken", "minecraft:broken");

    /** The old numeric properties with a 1.21.4 range property of the same meaning. */
    private static final Map<String, String> RANGES = Map.of(
            "damage", "minecraft:damage",
            "cooldown", "minecraft:cooldown",
            "custom_model_data", "minecraft:custom_model_data");

    /** {@code pull} counted use ticks in twentieths for a bow, and charge progress for a crossbow. */
    private static final double BOW_PULL_SCALE = 0.05;

    /** The result: the tree, or null when no override survives, and the properties that were dropped. */
    record Translation(JsonObject model, Set<String> droppedProperties) {}

    /** Builds the definition node that shows one model, given its id. */
    private final Function<String, JsonObject> leaf;
    private final List<Variable> variables = new ArrayList<>();
    private int nodes;

    private LegacyItemOverrides(Function<String, JsonObject> leaf) {
        this.leaf = leaf;
    }

    /** Translates {@code overrides} for an item whose own model is {@code baseModel}. */
    static Translation translate(JsonArray overrides, String baseModel, Function<String, JsonObject> leaf) {
        Set<String> dropped = new LinkedHashSet<>();
        List<Rule> kept = new ArrayList<>();
        // A property with no vanilla equivalent was computed by the mod's own code, which nothing
        // calls on 1.21.4 and newer. That includes angle and time: a compass that pointed at a biome
        // would now point at spawn. Such a property never matched in the old game when unregistered
        // either, so the overrides that need it are dropped and reported.
        for (JsonElement element : overrides) {
            Rule override = parse(element);
            if (override == null) continue;
            Set<String> unknown = new LinkedHashSet<>();
            for (String name : override.predicate().keySet()) {
                if (!isKnown(name)) unknown.add(name);
            }
            if (unknown.isEmpty()) {
                kept.add(override);
            } else {
                dropped.addAll(unknown);
            }
        }
        if (kept.isEmpty()) return new Translation(null, dropped);
        // 1.21.4 asks the item definition through conditions, selects and range dispatches instead,
        // so the overrides that are left become a tree of those.
        LegacyItemOverrides translator = new LegacyItemOverrides(leaf);
        translator.collectVariables(kept);
        try {
            return new Translation(translator.build(kept, 0, baseModel), dropped);
        } catch (TooLarge large) {
            return new Translation(null, dropped);
        }
    }

    /** Whether {@code name} is a vanilla property with a 1.21.4 equivalent. */
    private static boolean isKnown(String name) {
        return CONDITIONS.containsKey(name) || RANGES.containsKey(name) || name.equals("pull")
                || name.equals("lefthanded") || name.equals("charged") || name.equals("firework");
    }

    /** One override, with its property names in their unprefixed vanilla spelling, or null when malformed. */
    private static Rule parse(JsonElement element) {
        if (!element.isJsonObject()) return null;
        JsonObject override = element.getAsJsonObject();
        if (!(override.get("model") instanceof JsonPrimitive model) || !model.isString()
                || !(override.get("predicate") instanceof JsonObject predicate)) {
            return null;
        }
        // Each predicate is a property value the stack must reach. A repeated name keeps the highest.
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : predicate.entrySet()) {
            if (!(entry.getValue() instanceof JsonPrimitive value) || !value.isNumber()) return null;
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
            values.merge(name, value.getAsDouble(), Math::max);
        }
        return new Rule(values, ClientItemDefinitionMigrator.modelId(model.getAsString()));
    }

    /** Picks the properties to branch on, in nesting order. */
    private void collectVariables(List<Rule> overrides) {
        // Sort every name the overrides use into its 1.21.4 property kind.
        Map<String, Set<String>> conditions = new LinkedHashMap<>();
        Set<String> ranges = new LinkedHashSet<>();
        boolean mainHand = false;
        boolean charge = false;
        for (Rule override : overrides) {
            for (String name : override.predicate().keySet()) {
                if (CONDITIONS.containsKey(name)) {
                    conditions.computeIfAbsent(CONDITIONS.get(name), key -> new LinkedHashSet<>()).add(name);
                } else if (name.equals("lefthanded")) {
                    mainHand = true;
                } else if (name.equals("charged") || name.equals("firework")) {
                    charge = true;
                } else {
                    ranges.add(name);
                }
            }
        }
        // Conditions come first and ranges last, as vanilla nests its bow: a condition on using_item
        // whose true branch dispatches on the draw.
        conditions.forEach((property, names) ->
                variables.add(new Variable(Kind.CONDITION, property, names, 1)));
        if (mainHand) {
            variables.add(new Variable(Kind.MAIN_HAND, "minecraft:main_hand", Set.of("lefthanded"), 1));
        }
        if (charge) {
            variables.add(new Variable(Kind.CHARGE, "minecraft:charge_type",
                    Set.of("charged", "firework"), 1));
        }
        // pull becomes use_duration for a bow, or crossbow/pull next to charged.
        for (String name : ranges) {
            if (name.equals("pull")) {
                variables.add(charge
                        ? new Variable(Kind.RANGE, "minecraft:crossbow/pull", Set.of(name), 1)
                        : new Variable(Kind.RANGE, "minecraft:use_duration", Set.of(name), BOW_PULL_SCALE));
            } else {
                variables.add(new Variable(Kind.RANGE, RANGES.get(name), Set.of(name), 1));
            }
        }
    }

    /** Thrown when the tree passes {@link #MAX_NODES}; it skips the stack trace, since it is expected. */
    private static final class TooLarge extends RuntimeException {
        TooLarge() {
            super(null, null, false, false);
        }
    }

    /** Builds the node for the overrides still possible once the variables before {@code index} are fixed. */
    private JsonObject build(List<Rule> overrides, int index, String baseModel) {
        if (++nodes > MAX_NODES) throw new TooLarge();
        // With every variable fixed, the last override whose predicates all held wins, as it did in
        // the old game, and the item's own model shows when none is left.
        if (index == variables.size()) {
            String model = overrides.isEmpty() ? baseModel : overrides.get(overrides.size() - 1).model();
            return leaf.apply(model).deepCopy();
        }
        // Otherwise branch on the next variable, and skip a branch that would change nothing.
        Variable variable = variables.get(index);
        return switch (variable.kind()) {
            case CONDITION -> {
                JsonObject whenTrue = build(holding(overrides, variable, Map.of(), 1), index + 1, baseModel);
                JsonObject whenFalse = build(holding(overrides, variable, Map.of(), 0), index + 1, baseModel);
                if (whenTrue.equals(whenFalse)) yield whenTrue;
                JsonObject node = typed("minecraft:condition", variable.property());
                node.add("on_true", whenTrue);
                node.add("on_false", whenFalse);
                yield node;
            }
            // lefthanded at 1 means the left hand; right is the fallback.
            case MAIN_HAND -> {
                JsonObject left = build(holding(overrides, variable, Map.of(), 1), index + 1, baseModel);
                JsonObject right = build(holding(overrides, variable, Map.of(), 0), index + 1, baseModel);
                yield select(variable, right, Map.of("left", left));
            }
            // charged alone loads an arrow, and charged with firework loads a rocket.
            case CHARGE -> {
                JsonObject none = build(holding(overrides, variable,
                        Map.of("charged", 0.0, "firework", 0.0), 0), index + 1, baseModel);
                JsonObject arrow = build(holding(overrides, variable,
                        Map.of("charged", 1.0, "firework", 0.0), 0), index + 1, baseModel);
                JsonObject rocket = build(holding(overrides, variable,
                        Map.of("charged", 1.0, "firework", 1.0), 0), index + 1, baseModel);
                Map<String, JsonObject> cases = new LinkedHashMap<>();
                cases.put("arrow", arrow);
                cases.put("rocket", rocket);
                yield select(variable, none, cases);
            }
            case RANGE -> range(overrides, variable, index, baseModel);
        };
    }

    /** Builds a range dispatch with one entry per threshold. */
    private JsonObject range(List<Rule> overrides, Variable variable, int index, String baseModel) {
        String name = variable.names().iterator().next();
        // Every old numeric property was zero or more, so a threshold at or below zero always held.
        TreeSet<Double> thresholds = new TreeSet<>();
        for (Rule override : overrides) {
            Double threshold = override.predicate().get(name);
            if (threshold != null && threshold > 0) thresholds.add(threshold);
        }
        // The fallback shows what the item showed below the lowest threshold.
        JsonObject fallback = build(reaching(overrides, name, 0), index + 1, baseModel);
        JsonArray entries = new JsonArray();
        JsonObject below = fallback;
        for (double threshold : thresholds) {
            JsonObject model = build(reaching(overrides, name, threshold), index + 1, baseModel);
            // An entry that shows the same as the one below it changes nothing, so it is left out.
            if (model.equals(below)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("threshold", threshold);
            entry.add("model", model);
            entries.add(entry);
            below = model;
        }
        if (entries.isEmpty()) return fallback;
        JsonObject node = typed("minecraft:range_dispatch", variable.property());
        if (variable.scale() != 1) node.addProperty("scale", variable.scale());
        node.add("entries", entries);
        node.add("fallback", fallback);
        return node;
    }

    /** Builds a select node, leaving out each case that shows the same as the fallback. */
    private static JsonObject select(Variable variable, JsonObject fallback, Map<String, JsonObject> cases) {
        JsonArray list = new JsonArray();
        for (Map.Entry<String, JsonObject> entry : cases.entrySet()) {
            if (entry.getValue().equals(fallback)) continue;
            JsonObject when = new JsonObject();
            when.addProperty("when", entry.getKey());
            when.add("model", entry.getValue());
            list.add(when);
        }
        if (list.isEmpty()) return fallback;
        JsonObject node = typed("minecraft:select", variable.property());
        node.add("cases", list);
        node.add("fallback", fallback);
        return node;
    }

    private static JsonObject typed(String type, String property) {
        JsonObject node = new JsonObject();
        node.addProperty("type", type);
        node.addProperty("property", property);
        return node;
    }

    /** The overrides that hold when the variable's names take {@code values}, else {@code value}. */
    private static List<Rule> holding(List<Rule> overrides, Variable variable,
                                          Map<String, Double> values, double value) {
        List<Rule> holding = new ArrayList<>();
        for (Rule override : overrides) {
            boolean holds = true;
            for (String name : variable.names()) {
                Double threshold = override.predicate().get(name);
                if (threshold != null && values.getOrDefault(name, value) < threshold) holds = false;
            }
            if (holds) holding.add(override);
        }
        return holding;
    }

    /** The overrides whose threshold on {@code name}, if any, a value of {@code value} reaches. */
    private static List<Rule> reaching(List<Rule> overrides, String name, double value) {
        List<Rule> reaching = new ArrayList<>();
        for (Rule override : overrides) {
            Double threshold = override.predicate().get(name);
            if (threshold == null || threshold <= value) reaching.add(override);
        }
        return reaching;
    }
}
