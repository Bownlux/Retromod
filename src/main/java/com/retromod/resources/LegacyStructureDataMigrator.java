/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.resources;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import java.util.Set;

/** Moves a 1.18.2 mod's jigsaw structures to the data format Minecraft 1.19 introduced. */
public final class LegacyStructureDataMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrator");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // 1.18.2 defined structures here as a vanilla feature type such as minecraft:village with a
    // nested config. 1.19 reads worldgen/structure/ with type minecraft:jigsaw and the pool settings
    // at the top level. The mod's structure sets still name the structures, so the old folder left
    // them unbound and the server refused to load any world ("Unbound values in registry ...
    // worldgen/structure", #311).
    static final String OLD_FOLDER = "configured_structure_feature";
    static final String NEW_FOLDER = "structure";

    /** 1.18.2 structure features that were jigsaw structures underneath. */
    private static final Set<String> JIGSAW_TYPES = Set.of(
            "minecraft:village", "minecraft:pillager_outpost", "minecraft:bastion_remnant",
            "minecraft:jigsaw");

    private LegacyStructureDataMigrator() {
    }

    /**
     * Converts the structures in an extracted mod built for {@code sourceMcVersion}.
     *
     * @return the number of structure files written
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
        for (String oldFile : tree.names()) {
            if (!isConfiguredStructureFeature(oldFile)) {
                continue;
            }
            String fileName = oldFile.substring(oldFile.lastIndexOf('/') + 1);
            JsonObject converted;
            try {
                converted = convert(readObject(tree, oldFile));
            } catch (RuntimeException malformed) {
                // A value of the wrong JSON type. Skip this structure rather than fail the mod.
                LOGGER.warn("Could not convert 1.18.2 structure {}: {}", fileName,
                        malformed.getMessage());
                continue;
            }
            if (converted == null) {
                continue;
            }
            // The new file goes in worldgen/structure/, beside the old folder.
            String worldgen = oldFile.substring(0, oldFile.length() - fileName.length()
                    - OLD_FOLDER.length() - 1);
            String newFile = worldgen + NEW_FOLDER + "/" + fileName;
            if (tree.exists(newFile)) {
                continue; // the mod already ships the 1.19 form
            }
            tree.write(newFile, GSON.toJson(converted).getBytes(StandardCharsets.UTF_8));
            written++;
        }
        if (written > 0) {
            LOGGER.info("Converted {} 1.18.2 jigsaw structure(s) to the 1.19 format", written);
        }
        return written;
    }

    /** Whether a mod built before 1.19 runs on a 1.19 or newer host. */
    private static boolean applies(String sourceMcVersion, String targetMcVersion) {
        return sourceMcVersion != null && targetMcVersion != null
                && RetromodVersion.compareMcVersions(sourceMcVersion, "1.19") < 0
                && RetromodVersion.compareMcVersions(targetMcVersion, "1.19") >= 0;
    }

    /** A direct child of a {@code worldgen/configured_structure_feature/} folder under {@code data/}. */
    private static boolean isConfiguredStructureFeature(String name) {
        if (!name.startsWith("data/") || !name.endsWith(".json")) {
            return false;
        }
        String folder = name.substring(0, name.lastIndexOf('/'));
        return folder.endsWith("/worldgen/" + OLD_FOLDER);
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

    /** The 1.19 form of one 1.18.2 jigsaw structure, or null for any other feature type. */
    static JsonObject convert(JsonObject old) {
        // Only jigsaw-based types are converted, since their 1.18.2 config maps field for field.
        // Other feature types are left alone and still fail as before.
        if (old == null || !old.has("type") || !old.get("type").isJsonPrimitive()
                || !JIGSAW_TYPES.contains(old.get("type").getAsString())
                || !old.has("config") || !old.get("config").isJsonObject()) {
            return null;
        }
        JsonObject config = old.getAsJsonObject("config");
        if (!config.has("start_pool") || !old.has("biomes")) {
            return null;
        }
        JsonObject structure = new JsonObject();
        structure.addProperty("type", "minecraft:jigsaw");
        structure.add("biomes", old.get("biomes"));
        structure.add("step", old.has("step") ? old.get("step")
                : new com.google.gson.JsonPrimitive("surface_structures"));
        structure.add("spawn_overrides", old.has("spawn_overrides")
                ? old.get("spawn_overrides") : new JsonObject());
        // adapt_noise was a yes or no; 1.19 names the adaptation, and beard_thin is what true meant.
        boolean adaptNoise = old.has("adapt_noise") && old.get("adapt_noise").getAsBoolean();
        structure.addProperty("terrain_adaptation", adaptNoise ? "beard_thin" : "none");
        structure.add("start_pool", config.get("start_pool"));
        structure.addProperty("size", config.has("size") ? config.get("size").getAsInt() : 7);
        // 1.18.2 hardcoded placement per feature type, so the defaults follow the vanilla type:
        // villages and outposts start on the surface with the expansion hack, bastions at y 33.
        boolean bastion = "minecraft:bastion_remnant".equals(old.get("type").getAsString());
        if (old.has("start_height")) {
            structure.add("start_height", old.get("start_height"));
        } else {
            JsonObject absolute = new JsonObject();
            absolute.addProperty("absolute", bastion ? 33 : 0);
            structure.add("start_height", absolute);
        }
        if (old.has("project_start_to_heightmap")) {
            structure.add("project_start_to_heightmap", old.get("project_start_to_heightmap"));
        } else if (!bastion) {
            structure.addProperty("project_start_to_heightmap", "WORLD_SURFACE_WG");
        }
        structure.addProperty("max_distance_from_center", old.has("max_distance_from_center")
                ? old.get("max_distance_from_center").getAsInt() : 80);
        structure.addProperty("use_expansion_hack", old.has("use_expansion_hack")
                ? old.get("use_expansion_hack").getAsBoolean() : !bastion);
        return structure;
    }
}
