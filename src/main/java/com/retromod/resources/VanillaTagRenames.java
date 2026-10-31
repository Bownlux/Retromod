/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.retromod.core.RetromodVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Updates Minecraft's own tag names in a mod's data when a later release renamed the tag. */
final class VanillaTagRenames {

    /** One renamed tag: its folder under {@code tags/}, its old and new path, and the first release with the new name. */
    record Rename(String folder, String oldPath, String newPath, String version) {
        String oldFile() {
            return "data/minecraft/tags/" + folder + "/" + oldPath + ".json";
        }

        String newFile() {
            return "data/minecraft/tags/" + folder + "/" + newPath + ".json";
        }
    }

    // Read from data/minecraft/tags/ in the 1.21.11, 26.1.2, 26.2 and 26.3 client jars. Each old
    // tag is gone in the next jar, and the new one holds the same entries or does the same job
    // under the tags that release renamed. Only the names are listed; nothing of vanilla's is copied.
    static final List<Rename> RENAMES = List.of(
            new Rename("block", "bamboo_plantable_on", "supports_bamboo", "26.1"),
            new Rename("block", "big_dripleaf_placeable", "supports_big_dripleaf", "26.1"),
            new Rename("block", "dry_vegetation_may_place_on", "supports_dry_vegetation", "26.1"),
            new Rename("block", "mushroom_grow_block", "overrides_mushroom_light_requirement", "26.1"),
            new Rename("block", "small_dripleaf_placeable", "supports_small_dripleaf", "26.1"),
            new Rename("block", "snow_layer_can_survive_on", "support_override_snow_layer", "26.1"),
            new Rename("block", "snow_layer_cannot_survive_on", "cannot_support_snow_layer", "26.1"),
            // The cauldron still reads the dyeable items from a tag; dyeing in a grid became one
            // crafting_dye recipe per item, which dyeRecipes writes.
            new Rename("item", "dyeable", "cauldron_can_remove_dye", "26.1"),
            new Rename("block", "concrete_powder", "concrete_powders", "26.2"),
            new Rename("block", "convertable_to_mud", "convertible_to_mud", "26.3"),
            new Rename("worldgen/structure", "on_jungle_explorer_maps", "on_jungle_pyramid_maps", "26.3"),
            new Rename("worldgen/structure", "on_ocean_explorer_maps", "on_ocean_monument_maps", "26.3"),
            new Rename("worldgen/structure", "on_swamp_explorer_maps", "on_swamp_hut_maps", "26.3"),
            new Rename("worldgen/structure", "on_trial_chambers_maps", "on_buried_trial_chambers_maps", "26.3"),
            new Rename("worldgen/structure", "on_woodland_explorer_maps", "on_woodland_mansion_maps", "26.3"));

    /** The first release that renamed one of these tags. */
    static final String FIRST_RENAME = "26.1";

    private static final String DYEABLE = "data/minecraft/tags/item/dyeable.json";

    // A tag named without its # sits in these fields: ingredients and old item and block
    // predicates ("tag") and the exploration_map loot function ("destination").
    private static final List<String> BARE_TAG_FIELDS = List.of("tag", "destination");

    private VanillaTagRenames() {
    }

    /** The tag the host reads for {@code minecraft:path}, or the same id when no rename applies. */
    static String renamed(String id, String sourceMcVersion, String targetMcVersion) {
        if (!id.startsWith("minecraft:")) return id;
        String path = id.substring("minecraft:".length());
        for (Rename rename : RENAMES) {
            if (rename.oldPath().equals(path) && applies(rename, sourceMcVersion, targetMcVersion)) {
                return "minecraft:" + rename.newPath();
            }
        }
        return id;
    }

    /** Whether a rename applies to data built for {@code source} running on {@code target}. */
    private static boolean applies(Rename rename, String source, String target) {
        // Data of unknown age is renamed too, as VanillaIdRenames does, because the old tag
        // names nothing on the host.
        return isVersion(target) && RetromodVersion.compareMcVersions(target, rename.version()) >= 0
                && !(isVersion(source) && RetromodVersion.compareMcVersions(source, rename.version()) >= 0);
    }

    /** Renames tag references in one data JSON file; returns the same array when nothing changed. */
    static byte[] migrate(byte[] json, String sourceMcVersion, String targetMcVersion) {
        String text = new String(json, StandardCharsets.UTF_8);
        if (!mentionsOldTag(text)) return json;
        JsonElement root;
        try {
            root = JsonParser.parseString(text);
        } catch (RuntimeException unparsable) {
            return json;
        }
        boolean[] changed = {false};
        rename(root, null, sourceMcVersion, targetMcVersion, changed);
        return changed[0] ? root.toString().getBytes(StandardCharsets.UTF_8) : json;
    }

    /** Renames {@code #minecraft:} tags named in an mcfunction file's commands. */
    static byte[] migrateFunction(byte[] function, String sourceMcVersion, String targetMcVersion) {
        String text = new String(function, StandardCharsets.UTF_8);
        if (!mentionsOldTag(text)) return function;
        Matcher matcher = Pattern.compile("#(minecraft:[a-z0-9_/]+)(?![a-z0-9_/.-])").matcher(text);
        StringBuilder out = new StringBuilder();
        boolean changed = false;
        while (matcher.find()) {
            String renamed = renamed(matcher.group(1), sourceMcVersion, targetMcVersion);
            if (!renamed.equals(matcher.group(1))) changed = true;
            matcher.appendReplacement(out, Matcher.quoteReplacement("#" + renamed));
        }
        matcher.appendTail(out);
        return changed ? out.toString().getBytes(StandardCharsets.UTF_8) : function;
    }

    private static boolean mentionsOldTag(String text) {
        for (Rename rename : RENAMES) {
            if (text.contains("minecraft:" + rename.oldPath())) return true;
        }
        return false;
    }

    /** Renames, in place, every {@code #minecraft:} string and every bare tag in a tag field. */
    private static void rename(JsonElement element, String key, String source, String target, boolean[] changed) {
        if (element instanceof JsonArray array) {
            for (int i = 0; i < array.size(); i++) {
                JsonElement renamed = renamedValue(array.get(i), key, source, target, changed);
                if (renamed != null) array.set(i, renamed);
                else rename(array.get(i), key, source, target, changed);
            }
        } else if (element instanceof JsonObject object) {
            for (Map.Entry<String, JsonElement> field : object.entrySet()) {
                JsonElement renamed = renamedValue(field.getValue(), field.getKey(), source, target, changed);
                if (renamed != null) field.setValue(renamed);
                else rename(field.getValue(), field.getKey(), source, target, changed);
            }
        }
    }

    /** The renamed string, or null when {@code value} is not an old tag reference. */
    private static JsonElement renamedValue(JsonElement value, String key, String source, String target,
                                            boolean[] changed) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) return null;
        String text = primitive.getAsString();
        // A bare id is only a tag in the fields that hold one; elsewhere it could name an item.
        boolean hashed = text.startsWith("#");
        if (!hashed && (key == null || !BARE_TAG_FIELDS.contains(key))) return null;
        String id = hashed ? text.substring(1) : text;
        String renamed = renamed(id, source, target);
        if (renamed.equals(id)) return null;
        changed[0] = true;
        return new JsonPrimitive(hashed ? "#" + renamed : renamed);
    }

    /** Copies a mod's additions to a renamed vanilla tag to the new name, and returns how many files it wrote. */
    static int copyTagFiles(ResourceTree tree, String sourceMcVersion, String targetMcVersion) throws IOException {
        int written = 0;
        for (Rename rename : RENAMES) {
            if (!applies(rename, sourceMcVersion, targetMcVersion) || !tree.exists(rename.oldFile())) continue;
            // The original stays, as every copy step leaves it. A file already at the new name is
            // the mod's own and wins.
            if (!tree.exists(rename.newFile())) {
                tree.write(rename.newFile(), tree.read(rename.oldFile()));
                written++;
            }
            if (rename.oldFile().equals(DYEABLE)) written += dyeRecipes(tree, tree.read(rename.oldFile()));
        }
        return written;
    }

    /**
     * Writes the crafting_dye recipe 26.1 needs for each item the mod made dyeable. Only required
     * entries get one: from 26.3 a recipe naming a missing item stops the world from loading, and
     * an optional entry is allowed to name nothing.
     */
    private static int dyeRecipes(ResourceTree tree, byte[] tag) throws IOException {
        JsonElement root;
        try {
            root = JsonParser.parseString(new String(tag, StandardCharsets.UTF_8));
        } catch (RuntimeException unparsable) {
            return 0;
        }
        if (!(root instanceof JsonObject object) || !(object.get("values") instanceof JsonArray values)) return 0;
        int written = 0;
        for (String item : requiredModItems(values)) {
            int colon = item.indexOf(':');
            String recipe = "data/" + item.substring(0, colon) + "/recipe/" + item.substring(colon + 1) + "_dyed.json";
            if (tree.exists(recipe)) continue;
            // The same fields vanilla's leather armor recipes use on 26.1 to 26.3.
            JsonObject json = new JsonObject();
            json.addProperty("type", "minecraft:crafting_dye");
            json.addProperty("category", "misc");
            json.addProperty("dye", "#minecraft:dyes");
            json.addProperty("group", "dyed_armor");
            JsonObject result = new JsonObject();
            result.addProperty("id", item);
            json.add("result", result);
            json.addProperty("target", item);
            tree.write(recipe, json.toString().getBytes(StandardCharsets.UTF_8));
            written++;
        }
        return written;
    }

    /** The plain item ids a tag requires, outside the minecraft namespace, whose recipes vanilla ships. */
    private static List<String> requiredModItems(JsonArray values) {
        List<String> items = new ArrayList<>();
        for (JsonElement value : values) {
            String id = null;
            if (value instanceof JsonPrimitive primitive && primitive.isString()) {
                id = primitive.getAsString();
            } else if (value instanceof JsonObject entry && entry.get("id") instanceof JsonPrimitive inner
                    && inner.isString() && !(entry.get("required") instanceof JsonPrimitive flag
                    && flag.isBoolean() && !flag.getAsBoolean())) {
                id = inner.getAsString();
            }
            if (id != null && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && !id.startsWith("minecraft:")) {
                items.add(id);
            }
        }
        return items;
    }

    private static boolean isVersion(String version) {
        return version != null && version.matches("\\d+\\.\\d+.*");
    }
}
