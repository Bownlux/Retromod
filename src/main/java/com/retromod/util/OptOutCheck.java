/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Mod-author opt-out mechanism.
 *
 * <p>A mod author can request that Retromod not transform their JAR by shipping
 * an empty marker file at {@code META-INF/retromod-opt-out} (add it under
 * {@code src/main/resources/}). When present, the JAR passes through untouched
 * to {@code mods/}. Only the file's presence matters; its contents are ignored.</p>
 *
 * <p>Renderer replacements such as Sodium and Iris pass through the same way. They patch the
 * renderer for one exact Minecraft version and pin each other to exact versions, so a converted
 * copy cannot work, and relaxing Iris's Sodium pin let a mismatched pair crash on a missing Sodium
 * class. Untouched, the loader rejects the old jar and names the version it needs. Every supported
 * version has native builds of these mods.</p>
 *
 * <p>A user can force-transform an opted-out mod with
 * {@code -Dretromod.honorOptOut=false}. Default is {@code true} (opt-out honored).</p>
 */
public final class OptOutCheck {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-OptOut");

    /** Marker path inside a mod JAR; presence is the opt-out signal. */
    public static final String OPT_OUT_MARKER = "META-INF/retromod-opt-out";

    /** Set by {@code -Dretromod.honorOptOut=false} to force-transform opted-out mods. */
    private static final boolean IGNORE_OPT_OUT =
            !Boolean.parseBoolean(System.getProperty("retromod.honorOptOut", "true"));

    /** Mod ids of renderer replacements, which are never converted. */
    static final Set<String> RENDERER_MOD_IDS = Set.of("sodium", "iris", "embeddium", "oculus", "rubidium");

    private static final Pattern TOML_MOD_ID = Pattern.compile("modId\\s*=\\s*[\"']([^\"']+)[\"'].*");
    private static final String[] METADATA = {"fabric.mod.json", "quilt.mod.json",
            "META-INF/neoforge.mods.toml", "META-INF/mods.toml"};
    private static final int MAX_METADATA_BYTES = 1 << 20;

    private OptOutCheck() {}

    /**
     * @return {@code true} if the JAR contains the opt-out marker AND the user
     *         hasn't disabled marker-honoring. Caller should skip transformation
     *         when this returns true.
     */
    public static boolean isOptedOut(Path modJar) {
        if (IGNORE_OPT_OUT) return false;
        if (modJar == null) return false;
        try (JarFile jar = new JarFile(modJar.toFile())) {
            return jar.getEntry(OPT_OUT_MARKER) != null || rendererModId(jar) != null;
        } catch (IOException e) {
            // can't open the JAR; let the regular transform path surface the error
            LOGGER.debug("Could not read {} to check opt-out: {}", modJar, e.getMessage());
            return false;
        }
    }

    /** The jar's mod id when it is a renderer replacement, otherwise null. */
    static String rendererModId(JarFile jar) throws IOException {
        for (String name : METADATA) {
            ZipEntry entry = jar.getEntry(name);
            if (entry == null) continue;
            String text;
            try (InputStream in = jar.getInputStream(entry)) {
                text = new String(in.readNBytes(MAX_METADATA_BYTES), StandardCharsets.UTF_8);
            }
            if (name.endsWith(".json")) {
                String id = jsonModId(name, text);
                if (id != null && RENDERER_MOD_IDS.contains(id)) return id;
                continue;
            }
            String id = tomlRendererId(text);
            if (id != null) return id;
        }
        return null;
    }

    /**
     * A renderer id declared in a {@code [[mods]]} block. A {@code [[dependencies.x]]} block names
     * other mods, such as one this mod is incompatible with, so its ids are skipped.
     */
    private static String tomlRendererId(String text) {
        boolean inMods = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[")) {
                inMods = trimmed.replace(" ", "").equals("[[mods]]");
                continue;
            }
            Matcher id = TOML_MOD_ID.matcher(trimmed);
            if (inMods && id.matches() && RENDERER_MOD_IDS.contains(id.group(1))) return id.group(1);
        }
        return null;
    }

    /** The top-level mod id; a nested id, such as a Mod Menu parent, belongs to another mod. */
    private static String jsonModId(String name, String text) {
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (name.startsWith("quilt")) {
                root = root.has("quilt_loader") && root.get("quilt_loader").isJsonObject()
                        ? root.getAsJsonObject("quilt_loader") : null;
            }
            return root != null && root.has("id") && root.get("id").isJsonPrimitive()
                    ? root.get("id").getAsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Log a consistent skip message; call from each entry point. */
    public static void logSkipped(Path modJar) {
        try (JarFile jar = new JarFile(modJar.toFile())) {
            String renderer = rendererModId(jar);
            if (renderer != null && jar.getEntry(OPT_OUT_MARKER) == null) {
                LOGGER.warn("Skipping {} - {} replaces Minecraft's renderer for one exact version and "
                        + "cannot be converted. Install the {} build made for this Minecraft version instead.",
                        modJar.getFileName(), renderer, renderer);
                return;
            }
        } catch (IOException e) {
            LOGGER.debug("Could not read {} to name the skip reason: {}", modJar, e.getMessage());
        }
        LOGGER.info("Skipping {} - mod author opted out via {} marker. " +
                "Override with -Dretromod.honorOptOut=false if you want to force transform.",
                modJar.getFileName(), OPT_OUT_MARKER);
    }
}
