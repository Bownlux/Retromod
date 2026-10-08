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
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves a 1.18.2 mod's jigsaw structures to the data format Minecraft 1.19 introduced.
 *
 * <p>1.18.2 defined them in {@code worldgen/configured_structure_feature/} as a vanilla feature
 * type such as {@code minecraft:village} with a nested {@code config}. 1.19 reads
 * {@code worldgen/structure/} with type {@code minecraft:jigsaw} and the pool settings at the top
 * level. The mod's structure sets still name the structures, so the old folder left them unbound
 * and the server refused to load any world ({@code Unbound values in registry ...
 * worldgen/structure}, #311).
 *
 * <p>Only jigsaw-based types are converted, since their 1.18.2 config maps field for field.
 * Other feature types are left alone and still fail as before.
 */
public final class LegacyStructureDataMigrator {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataMigrator");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
        if (sourceMcVersion == null || targetMcVersion == null
                || RetromodVersion.compareMcVersions(sourceMcVersion, "1.19") >= 0
                || RetromodVersion.compareMcVersions(targetMcVersion, "1.19") < 0) {
            return 0;
        }
        Path data = extractedJar.resolve("data");
        if (!Files.isDirectory(data)) {
            return 0;
        }
        List<Path> oldFiles;
        try (Stream<Path> walk = Files.walk(data)) {
            oldFiles = walk.filter(LegacyStructureDataMigrator::isConfiguredStructureFeature)
                    .toList();
        }
        int written = 0;
        for (Path oldFile : oldFiles) {
            JsonObject converted;
            try {
                converted = convert(readObject(oldFile));
            } catch (RuntimeException malformed) {
                // A value of the wrong JSON type. Skip this structure rather than fail the mod.
                LOGGER.warn("Could not convert 1.18.2 structure {}: {}", oldFile.getFileName(),
                        malformed.getMessage());
                continue;
            }
            if (converted == null) {
                continue;
            }
            Path worldgen = oldFile.getParent().getParent();
            Path newFile = worldgen.resolve(NEW_FOLDER)
                    .resolve(oldFile.getParent().relativize(oldFile).toString());
            if (Files.exists(newFile)) {
                continue; // the mod already ships the 1.19 form
            }
            Files.createDirectories(newFile.getParent());
            Files.writeString(newFile, GSON.toJson(converted), StandardCharsets.UTF_8);
            written++;
        }
        if (written > 0) {
            LOGGER.info("Converted {} 1.18.2 jigsaw structure(s) to the 1.19 format", written);
        }
        return written;
    }

    private static boolean isConfiguredStructureFeature(Path path) {
        return path.toString().endsWith(".json") && path.getParent() != null
                && path.getParent().getFileName().toString().equals(OLD_FOLDER)
                && path.getParent().getParent() != null
                && path.getParent().getParent().getFileName().toString().equals("worldgen");
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

    /** The 1.19 form of one 1.18.2 jigsaw structure, or null for any other feature type. */
    static JsonObject convert(JsonObject old) {
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
