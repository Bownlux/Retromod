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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Moves a pre-1.20 mod's smithing recipes to the smithing transform recipe 1.20 introduced.
 *
 * <p>Up to 1.19.4 an upgrade recipe was {@code minecraft:smithing} with a base, an addition and a
 * result. 1.20 removed that type, so each such recipe logged {@code Invalid or unsupported recipe
 * type 'minecraft:smithing'} and the upgrade was gone. {@code minecraft:smithing_transform} reads the
 * same three fields plus a template. An empty template ingredient matches an empty template slot, so
 * the converted recipe works in the smithing table without a template, as it did before.
 *
 * <p>Only recipes without a template are converted. Anything else is left for the loader to judge.
 * Only 1.20 to 1.20.4 hosts are converted: 1.20.5 changed the result format and 1.21.2 made empty
 * ingredients invalid, so a later host needs a different conversion.
 */
public final class LegacySmithingRecipeMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrator");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        if (sourceMcVersion == null || targetMcVersion == null
                || RetromodVersion.compareMcVersions(sourceMcVersion, "1.20") >= 0
                || RetromodVersion.compareMcVersions(targetMcVersion, "1.20") < 0
                || RetromodVersion.compareMcVersions(targetMcVersion, "1.20.5") >= 0) {
            return 0;
        }
        Path data = extractedJar.resolve("data");
        if (!Files.isDirectory(data)) {
            return 0;
        }
        List<Path> recipes;
        try (Stream<Path> walk = Files.walk(data)) {
            recipes = walk.filter(path -> isRecipeFile(data.relativize(path))).toList();
        }
        int written = 0;
        for (Path recipe : recipes) {
            JsonObject converted = convert(readObject(recipe));
            if (converted == null) {
                continue;
            }
            Files.writeString(recipe, GSON.toJson(converted), StandardCharsets.UTF_8);
            written++;
        }
        if (written > 0) {
            LOGGER.info("Converted {} pre-1.20 smithing recipe(s) to smithing_transform", written);
        }
        return written;
    }

    /** A path under {@code data/} of the form {@code <namespace>/recipes/...json}. */
    private static boolean isRecipeFile(Path relative) {
        if (!relative.toString().endsWith(".json") || relative.getNameCount() < 3) {
            return false;
        }
        String folder = relative.getName(1).toString();
        return folder.equals("recipes") || folder.equals("recipe");
    }

    private static JsonObject readObject(Path file) {
        try {
            JsonElement root = JsonParser.parseString(
                    Files.readString(file, StandardCharsets.UTF_8));
            return root.isJsonObject() ? root.getAsJsonObject() : null;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The smithing transform form of one legacy smithing recipe, or null for anything else. */
    static JsonObject convert(JsonObject old) {
        if (old == null || !old.has("type") || !old.get("type").isJsonPrimitive()
                || !OLD_TYPE.equals(old.get("type").getAsString())
                || old.has("template") || !old.has("base") || !old.has("addition")
                || !old.has("result")) {
            return null;
        }
        JsonObject recipe = new JsonObject();
        recipe.addProperty("type", NEW_TYPE);
        recipe.add("template", new JsonArray());
        for (Map.Entry<String, JsonElement> field : old.entrySet()) {
            if (!field.getKey().equals("type")) {
                recipe.add(field.getKey(), field.getValue());
            }
        }
        return recipe;
    }
}
