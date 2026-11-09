/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.util.JsonSecurity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Rewrites registry entries and item components that 1.21.2 to 26.3 renamed or reshaped. */
final class LegacyRegistryEntries implements ModResourceConverter {

    // The first host with each change, read from the 1.21.11, 26.1.2, 26.2 and 26.3 client jars.
    static final String FIRST_UNPREFIXED_ATTRIBUTE_HOST = ItemStackComponents.FIRST_UNPREFIXED_ATTRIBUTE_HOST;
    static final String FIRST_TOOLTIP_DISPLAY_HOST = "1.21.5";
    static final String FIRST_WOLF_ASSET_HOST = "1.21.5";
    static final String FIRST_CLOCK_HOST = "26.1";
    static final String FIRST_ROOT_BACKGROUND_HOST = "26.3";

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private static final int MAX_JSON_DEPTH = 256;

    // Every attribute 1.21.1 registered under a generic., player. or zombie. prefix. 1.21.2 dropped the
    // prefix from all of them, and 26.3's Attributes class lists each new name.
    private static final Set<String> PREFIXED_ATTRIBUTES = Set.of("generic.armor", "generic.armor_toughness",
            "generic.attack_damage", "generic.attack_knockback", "generic.attack_speed", "generic.burning_time",
            "generic.explosion_knockback_resistance", "generic.fall_damage_multiplier", "generic.flying_speed",
            "generic.follow_range", "generic.gravity", "generic.jump_strength", "generic.knockback_resistance",
            "generic.luck", "generic.max_absorption", "generic.max_health", "generic.movement_efficiency",
            "generic.movement_speed", "generic.oxygen_bonus", "generic.safe_fall_distance", "generic.scale",
            "generic.step_height", "generic.water_movement_efficiency", "player.block_break_speed",
            "player.block_interaction_range", "player.entity_interaction_range", "player.mining_efficiency",
            "player.sneaking_speed", "player.submerged_mining_speed", "player.sweeping_damage_ratio",
            "zombie.spawn_reinforcements");
    // One of those ids as a command or NBT token, with or without its namespace. Another mod's
    // namespace never matches; an SNBT key such as id: or Name: before a bare id does.
    private static final Pattern ATTRIBUTE_TOKEN = Pattern.compile(
            "(?:(?<![A-Za-z0-9_.:/-])|(?<=[{,\\s](?:id|Name|AttributeName):))(minecraft:)?"
                    + "((?:generic|player|zombie)\\.[a-z_]+)(?![A-Za-z0-9_.:/-])");

    // 26.1 renamed the enchantment effect that damages the item.
    private static final Map<String, String> RENAMED_EFFECTS = Map.of(
            "minecraft:damage_item", "minecraft:change_item_damage");

    private static final String STONE_BACKGROUND = "minecraft:gui/advancements/backgrounds/stone";
    private static final String NEOFORGE_CONDITIONS = "neoforge:conditions";
    private static final Pattern IDENTIFIER = Pattern.compile("(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+");
    private static final int MAX_REFERENCE_DEPTH = 8;

    @Override
    public String name() {
        return "legacy registry entries";
    }

    @Override
    public boolean appliesTo(MigrationContext context) {
        return context.targetAtLeast(FIRST_UNPREFIXED_ATTRIBUTE_HOST)
                && context.sourceBefore(FIRST_ROOT_BACKGROUND_HOST);
    }

    @Override
    public int apply(ResourceTree tree, MigrationContext context) throws IOException {
        int changed = 0;
        Set<String> emptyTags = new java.util.TreeSet<>();
        for (String name : tree.names()) {
            String path = ModDataMigrator.dataPath(name);
            if (!path.startsWith("data/")) continue;
            byte[] before;
            if (path.endsWith(".mcfunction")) {
                before = tree.read(name, MAX_FILE_BYTES);
                byte[] after = renameFunctionAttributes(before, context);
                if (after != before) {
                    tree.write(name, after);
                    changed++;
                }
                continue;
            }
            if (!path.endsWith(".json")) continue;
            before = tree.read(name, MAX_FILE_BYTES);
            JsonElement root = parse(before);
            if (root == null) continue; // a malformed file is left for the steps after this one
            Rewriter rewriter = new Rewriter(context, path, tree, emptyTags);
            JsonElement after = rewriter.file(root);
            if (!rewriter.changed) continue;
            tree.write(name, GSON.toJson(after).getBytes(StandardCharsets.UTF_8));
            changed++;
        }
        for (String tag : emptyTags) {
            if (tree.exists(tag)) continue;
            tree.write(tag, "{\"values\":[]}".getBytes(StandardCharsets.UTF_8));
            changed++;
        }
        return changed;
    }

    /** A function file with its 1.21.1 attribute ids renamed, or the same array when it names none. */
    private static byte[] renameFunctionAttributes(byte[] function, MigrationContext context) {
        if (!changedIn(context, FIRST_UNPREFIXED_ATTRIBUTE_HOST)) return function;
        String text = new String(function, StandardCharsets.UTF_8);
        Matcher matcher = ATTRIBUTE_TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        boolean renamed = false;
        while (matcher.find()) {
            // A bare id stays bare: an unquoted SNBT string such as id:generic.scale cannot hold a colon.
            String attribute = matcher.group(2);
            String namespace = matcher.group(1) == null ? "" : matcher.group(1);
            String replacement = PREFIXED_ATTRIBUTES.contains(attribute) ? namespace + unprefixed(attribute) : matcher.group();
            renamed |= !replacement.equals(matcher.group());
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return renamed ? out.toString().getBytes(StandardCharsets.UTF_8) : function;
    }

    private static JsonElement parse(byte[] json) {
        try {
            String text = new String(json, StandardCharsets.UTF_8);
            JsonSecurity.validate(text, MAX_FILE_BYTES, MAX_JSON_DEPTH, "Data JSON");
            return JsonParser.parseString(text);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Whether data from the mod's version still needs the change {@code version} made. */
    private static boolean changedIn(MigrationContext context, String version) {
        return context.targetAtLeast(version) && context.sourceBefore(version);
    }

    private static String unprefixed(String attribute) {
        return attribute.substring(attribute.indexOf('.') + 1);
    }

    /** One file's rewrite, with the rules its source and target versions allow. */
    private static final class Rewriter {
        private final MigrationContext context;
        private final String path;
        private final ResourceTree tree;
        private final Set<String> emptyTags;
        boolean changed;

        Rewriter(MigrationContext context, String path, ResourceTree tree, Set<String> emptyTags) {
            this.context = context;
            this.path = path;
            this.tree = tree;
            this.emptyTags = emptyTags;
        }

        JsonElement file(JsonElement root) {
            if (path.contains("/wolf_variant/") && root instanceof JsonObject variant) wolfVariant(variant);
            if (path.contains("/advancement/") && root instanceof JsonObject advancement) {
                background(advancement);
                foreignIcon(advancement);
            }
            if (path.contains("/loot_modifiers/") && root instanceof JsonObject modifier) inlineReferences(modifier);
            walk(root);
            return root;
        }

        /**
         * Loads an advancement whose icon names another mod's item only when that item exists. From
         * 26.3 an unknown icon item stops the world, so on NeoForge, whose registry loading reads
         * neoforge:conditions on every entry, the advancement is skipped when the mod is absent.
         */
        private void foreignIcon(JsonObject advancement) {
            if (!changedIn(context, FIRST_ROOT_BACKGROUND_HOST)
                    || context.targetLoader() != MigrationContext.Loader.NEOFORGE
                    || !(advancement.get("display") instanceof JsonObject display)
                    || !(display.get("icon") instanceof JsonObject icon)) return;
            String item = string(icon, "id") != null ? string(icon, "id") : string(icon, "item");
            int colon = item == null ? -1 : item.indexOf(':');
            if (colon <= 0) return;
            String namespace = item.substring(0, colon);
            if (namespace.equals("minecraft") || namespace.equals("c")
                    || context.declaredNamespaces().contains(namespace)) return;
            JsonObject registered = new JsonObject();
            registered.addProperty("type", "neoforge:registered");
            registered.addProperty("registry", "minecraft:item");
            registered.addProperty("value", item);
            JsonArray conditions = advancement.get(NEOFORGE_CONDITIONS) instanceof JsonArray existing
                    ? existing : new JsonArray();
            if (conditions.contains(registered)) return;
            conditions.add(registered);
            advancement.add(NEOFORGE_CONDITIONS, conditions);
            changed = true;
        }

        /**
         * Puts the predicate a Forge loot modifier's condition list referenced in place of the reference.
         * 26.3 removed the reference condition, and Forge reads this list as direct conditions only.
         */
        private void inlineReferences(JsonObject modifier) {
            if (!changedIn(context, FIRST_ROOT_BACKGROUND_HOST) || context.targetLoader() != MigrationContext.Loader.FORGE
                    || !(modifier.get("conditions") instanceof JsonArray conditions)) return;
            for (int i = 0; i < conditions.size(); i++) {
                // A referenced predicate may itself be a reference, so a short chain is followed.
                for (int depth = 0; depth < MAX_REFERENCE_DEPTH; depth++) {
                    JsonElement predicate = referencedPredicate(conditions.get(i));
                    if (predicate == null) break;
                    conditions.set(i, predicate);
                    changed = true;
                }
            }
        }

        /** The predicate file a reference condition names, as one condition, or null when the jar lacks it. */
        private JsonElement referencedPredicate(JsonElement condition) {
            if (!(condition instanceof JsonObject reference) || reference.size() != 2
                    || !"minecraft:reference".equals(namespaced(String.valueOf(string(reference, "condition"))))) {
                return null;
            }
            String id = string(reference, "name");
            int colon = id == null ? -1 : id.indexOf(':');
            if (colon <= 0) return null;
            for (String folder : new String[] {"/predicate/", "/predicates/"}) {
                String file = "data/" + id.substring(0, colon) + folder + id.substring(colon + 1) + ".json";
                if (!tree.exists(file)) continue;
                try {
                    JsonElement predicate = parse(tree.read(file, MAX_FILE_BYTES));
                    if (predicate instanceof JsonObject single) return single;
                    if (predicate instanceof JsonArray list) {
                        // A predicate file listing conditions meant all of them.
                        JsonObject allOf = new JsonObject();
                        allOf.addProperty("condition", "minecraft:all_of");
                        allOf.add("terms", list);
                        return allOf;
                    }
                } catch (IOException unreadable) {
                    return null;
                }
            }
            return null;
        }

        private void walk(JsonElement element) {
            if (element instanceof JsonArray array) {
                for (JsonElement item : array) walk(item);
                return;
            }
            if (!(element instanceof JsonObject object)) return;
            for (JsonElement child : object.asMap().values()) walk(child);
            if (changedIn(context, FIRST_UNPREFIXED_ATTRIBUTE_HOST)) attributes(object);
            if (object.get("components") instanceof JsonObject components) components(components);
            if (changedIn(context, FIRST_CLOCK_HOST)) {
                timeCheckClock(object);
                if (path.contains("/enchantment/")) renameEffect(object);
            }
            if (changedIn(context, FIRST_ROOT_BACKGROUND_HOST)) missingOwnMapTag(object);
        }

        /**
         * Keeps an exploration map's own structure tag that the jar never shipped as the empty tag the
         * old game read. 26.3 binds the destination, and one missing tag stops the world from loading.
         */
        private void missingOwnMapTag(JsonObject function) {
            String kind = string(function, "function") != null ? string(function, "function") : string(function, "type");
            String destination = string(function, "destination");
            if (kind == null || !namespaced(kind).equals("minecraft:exploration_map") || destination == null
                    || destination.startsWith("#")) return;
            int colon = destination.indexOf(':');
            if (colon <= 0 || !context.ownNamespaces().contains(destination.substring(0, colon))) return;
            String tag = "data/" + destination.substring(0, colon) + "/tags/worldgen/structure/"
                    + destination.substring(colon + 1) + ".json";
            if (!tree.exists(tag)) emptyTags.add(tag);
        }

        /** Reshapes item components that 1.21.2, 1.21.5 and 26.3 replaced, wherever data sets or matches them. */
        private void components(JsonObject components) {
            // 1.21.2 made fire resistance one case of damage resistance.
            if (changedIn(context, FIRST_UNPREFIXED_ATTRIBUTE_HOST) && remove(components, "fire_resistant") != null) {
                JsonObject resistant = new JsonObject();
                resistant.addProperty("types", "#minecraft:is_fire");
                components.add("minecraft:damage_resistant", resistant);
                changed = true;
            }
            if (changedIn(context, FIRST_TOOLTIP_DISPLAY_HOST)) {
                // 1.21.5 listed hidden tooltip lines in tooltip_display and made the modifiers a plain list.
                if (remove(components, "hide_additional_tooltip") != null) {
                    hideInTooltip(components, ItemStackComponents.additionalTooltipComponents());
                }
                JsonElement modifiers = components.get("minecraft:attribute_modifiers") != null
                        ? components.get("minecraft:attribute_modifiers") : components.get("attribute_modifiers");
                if (modifiers instanceof JsonObject old && old.get("modifiers") instanceof JsonArray list) {
                    remove(components, "attribute_modifiers");
                    components.add("minecraft:attribute_modifiers", list);
                    if (old.get("show_in_tooltip") instanceof JsonPrimitive shown && !shown.getAsBoolean()) {
                        hideInTooltip(components, java.util.List.of("minecraft:attribute_modifiers"));
                    }
                    changed = true;
                }
            }
            // 26.3 removed map_color, and nothing reads it any more, so it is dropped.
            if (changedIn(context, FIRST_ROOT_BACKGROUND_HOST)) {
                remove(components, "map_color");
                if (components.remove("!minecraft:map_color") != null) changed = true;
                unreadableEntityData(components);
            }
        }

        /**
         * Drops entity data that holds nothing but an id the old game could not read. A spawn egg then
         * used its own entity and applied nothing, which is what it does with no entity data; 26.3
         * rejects the id and stops the world.
         */
        private void unreadableEntityData(JsonObject components) {
            for (String key : new String[] {"minecraft:entity_data", "entity_data"}) {
                if (components.get(key) instanceof JsonObject data && data.size() == 1
                        && string(data, "id") != null && !IDENTIFIER.matcher(string(data, "id")).matches()) {
                    components.remove(key);
                    changed = true;
                }
            }
        }

        /** Removes a component under its namespaced or bare key, and returns its value or null. */
        private JsonElement remove(JsonObject components, String path) {
            JsonElement value = components.remove("minecraft:" + path);
            JsonElement bare = components.remove(path);
            if (value != null || bare != null) changed = true;
            return value != null ? value : bare;
        }

        private void hideInTooltip(JsonObject components, java.util.List<String> hidden) {
            JsonObject display = components.get("minecraft:tooltip_display") instanceof JsonObject existing
                    ? existing : new JsonObject();
            JsonArray list = display.get("hidden_components") instanceof JsonArray existing ? existing : new JsonArray();
            for (String component : hidden) {
                if (!list.contains(new JsonPrimitive(component))) list.add(component);
            }
            display.add("hidden_components", list);
            components.add("minecraft:tooltip_display", display);
            changed = true;
        }

        /** Renames a 1.21.1 attribute id in an effect, a loot function or an item's attribute modifiers. */
        private void attributes(JsonObject object) {
            // An enchantment effect and set_attributes name it in "attribute", a component modifier in "type".
            renameAttribute(object, "attribute");
            if (object.has("operation") && object.has("amount")) renameAttribute(object, "type");
        }

        private void renameAttribute(JsonObject object, String key) {
            if (!(object.get(key) instanceof JsonPrimitive value) || !value.isString()) return;
            String id = value.getAsString();
            String path = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            if (!PREFIXED_ATTRIBUTES.contains(path)) return;
            object.addProperty(key, "minecraft:" + unprefixed(path));
            changed = true;
        }

        /**
         * Gives an old time check the overworld clock. 26.1 reads the time from a named clock; before,
         * the check read the day time every dimension shares with the overworld.
         */
        private void timeCheckClock(JsonObject object) {
            String kind = string(object, "condition") != null ? string(object, "condition") : string(object, "type");
            if (kind == null || !namespaced(kind).equals("minecraft:time_check") || object.has("clock")
                    || !object.has("value")) return;
            object.addProperty("clock", "minecraft:overworld");
            changed = true;
        }

        private void renameEffect(JsonObject object) {
            String type = string(object, "type");
            if (type == null || !RENAMED_EFFECTS.containsKey(namespaced(type))) return;
            object.addProperty("type", RENAMED_EFFECTS.get(namespaced(type)));
            changed = true;
        }

        /**
         * Rewrites a 1.21.1 wolf variant: 1.21.5 grouped its textures as assets and turned its biomes into
         * a spawn condition, and 26.1 added the baby textures.
         */
        private void wolfVariant(JsonObject variant) {
            if (changedIn(context, FIRST_WOLF_ASSET_HOST) && variant.has("wild_texture") && !variant.has("assets")) {
                JsonObject assets = new JsonObject();
                assets.add("wild", variant.remove("wild_texture"));
                if (variant.has("tame_texture")) assets.add("tame", variant.remove("tame_texture"));
                if (variant.has("angry_texture")) assets.add("angry", variant.remove("angry_texture"));
                variant.add("assets", assets);
                JsonArray spawns = new JsonArray();
                if (variant.has("biomes")) {
                    JsonObject condition = new JsonObject();
                    condition.addProperty("type", "minecraft:biome");
                    condition.add("biomes", variant.remove("biomes"));
                    JsonObject spawn = new JsonObject();
                    spawn.add("condition", condition);
                    spawn.addProperty("priority", 1);
                    spawns.add(spawn);
                }
                // Before 1.21.5 a wolf with no matching variant was pale, which vanilla now writes as a fallback.
                if (path.equals("data/minecraft/wolf_variant/pale.json")) {
                    JsonObject fallback = new JsonObject();
                    fallback.addProperty("priority", 0);
                    spawns.add(fallback);
                }
                variant.add("spawn_conditions", spawns);
                changed = true;
            }
            if (changedIn(context, FIRST_CLOCK_HOST) && variant.get("assets") instanceof JsonObject assets
                    && !variant.has("baby_assets")) {
                variant.add("baby_assets", babyAssets(assets));
                changed = true;
            }
        }

        /** Vanilla's wolf textures have a _baby twin; another texture is reused for the baby as it is. */
        private static JsonObject babyAssets(JsonObject assets) {
            JsonObject baby = new JsonObject();
            for (Map.Entry<String, JsonElement> texture : assets.entrySet()) {
                String id = texture.getValue() instanceof JsonPrimitive value && value.isString() ? value.getAsString() : null;
                boolean vanilla = id != null && id.startsWith("minecraft:entity/wolf/");
                baby.add(texture.getKey(), vanilla ? new JsonPrimitive(id + "_baby") : texture.getValue());
            }
            return baby;
        }

        /**
         * 26.3 rejects a root advancement with a display but no background, and any other advancement
         * that has one. The old screen drew a missing texture for the first and ignored the second.
         */
        private void background(JsonObject advancement) {
            if (!changedIn(context, FIRST_ROOT_BACKGROUND_HOST)
                    || !(advancement.get("display") instanceof JsonObject display)) return;
            boolean root = !advancement.has("parent");
            if (root && !display.has("background")) {
                display.addProperty("background", STONE_BACKGROUND);
                changed = true;
            } else if (!root && display.has("background")) {
                display.remove("background");
                changed = true;
            }
        }
    }

    private static String string(JsonObject object, String key) {
        return object.get(key) instanceof JsonPrimitive value && value.isString() ? value.getAsString() : null;
    }

    private static String namespaced(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }
}
