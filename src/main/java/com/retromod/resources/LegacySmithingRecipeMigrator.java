/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Moves a pre-1.20 mod's smithing recipes to the smithing transform recipe 1.20 introduced. */
public final class LegacySmithingRecipeMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrator");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Up to 1.19.4 an upgrade recipe was minecraft:smithing with a base, an addition and a result.
    // 1.20 removed that type, so each one logged "Invalid or unsupported recipe type
    // 'minecraft:smithing'" and the upgrade was gone. smithing_transform reads the same three
    // fields plus a template.
    static final String OLD_TYPE = "minecraft:smithing";
    static final String NEW_TYPE = "minecraft:smithing_transform";

    private LegacySmithingRecipeMigrator() {
    }

    /**
     * Converts the smithing recipes in an extracted mod built for {@code sourceMcVersion}.
     *
     * @return the number of recipe files rewritten
     */
    public static int migrate(Path extractedJar, String sourceMcVersion, String targetMcVersion)
            throws IOException {
        if (!applies(sourceMcVersion, targetMcVersion)
                || !Files.isDirectory(extractedJar.resolve("data"))) {
            return 0;
        }
        return migrate(DirectoryResourceTree.open(extractedJar), sourceMcVersion, targetMcVersion);
    }

    /** {@link #migrate(Path, String, String)} over any resource tree. */
    static int migrate(ResourceTree tree, String sourceMcVersion, String targetMcVersion)
            throws IOException {
        if (!applies(sourceMcVersion, targetMcVersion)) {
            return 0;
        }
        int written = 0;
        for (String recipe : tree.names()) {
            if (!isRecipeFile(recipe)) {
                continue;
            }
            JsonObject converted = convert(readObject(tree, recipe), targetMcVersion);
            if (converted == null) {
                continue;
            }
            tree.write(recipe, GSON.toJson(converted).getBytes(StandardCharsets.UTF_8));
            written++;
        }
        if (written > 0) {
            LOGGER.info("Converted {} pre-1.20 smithing recipe(s) to smithing_transform", written);
        }
        return written;
    }

    /** Whether a mod built before 1.20 runs on a 1.20 or newer host. */
    private static boolean applies(String sourceMcVersion, String targetMcVersion) {
        return sourceMcVersion != null && targetMcVersion != null
                && RetromodVersion.compareMcVersions(sourceMcVersion, "1.20") < 0
                && RetromodVersion.compareMcVersions(targetMcVersion, "1.20") >= 0;
    }

    /** The first host whose smithing template is optional and rejects an empty ingredient. */
    static final String FIRST_OPTIONAL_TEMPLATE_HOST = "1.21.2";

    /** A path of the form {@code data/<namespace>/recipes/...json} (or the singular folder). */
    private static boolean isRecipeFile(String name) {
        if (!name.startsWith("data/") || !name.endsWith(".json")) {
            return false;
        }
        String[] parts = name.split("/");
        return parts.length >= 4 && (parts[2].equals("recipes") || parts[2].equals("recipe"));
    }

    private static JsonObject readObject(ResourceTree tree, String name) {
        try {
            JsonElement root = JsonParser.parseString(StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(tree.read(name))).toString());
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** {@link #convert(JsonObject, String)} for a host that reads an empty template. */
    static JsonObject convert(JsonObject old) {
        return convert(old, "1.20");
    }

    /** The smithing transform form of one legacy smithing recipe, or null for anything else. */
    static JsonObject convert(JsonObject old, String targetMcVersion) {
        if (old == null || !old.has("type") || !old.get("type").isJsonPrimitive()
                || !OLD_TYPE.equals(old.get("type").getAsString())
                || old.has("template") || !old.has("base") || !old.has("addition")
                || !old.has("result")) {
            // Only recipes without a template are converted. Anything else is left for the
            // loader to judge.
            return null;
        }
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", NEW_TYPE);
        // An empty template matches an empty template slot, so the recipe works without one, as it
        // did before. Up to 1.21.1 that is an empty ingredient. From 1.21.2 an empty ingredient is
        // invalid and the template is optional, so it is left out, which matches the same empty
        // slot (checked in the 1.20.5, 1.21, 1.21.2, 1.21.5 and 26.3 client jars).
        if (RetromodVersion.compareMcVersions(targetMcVersion, FIRST_OPTIONAL_TEMPLATE_HOST) < 0) {
            recipe.add("template", new JsonArray());
        }
        // The data content step later gives the result and ingredients the host's shapes.
        for (Map.Entry<String, JsonElement> field : old.entrySet()) {
            if (!field.getKey().equals("type")) {
                recipe.add(field.getKey(), field.getValue());
            }
        }
        return recipe;
    }
}
