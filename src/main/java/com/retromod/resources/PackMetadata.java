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
import com.google.gson.JsonParseException;
import com.retromod.util.JsonSecurity;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Parses and updates legacy and full-version pack metadata. */
final class PackMetadata {

    private static final Gson OUTPUT_GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private PackMetadata() {}

    static DeclaredFormats read(Path packPath) throws IOException {
        String content = PackArchive.readMetadata(packPath);
        if (content == null) {
            throw new IOException("Pack has no pack.mcmeta: " + packPath);
        }
        return parse(content, packPath.toString());
    }

    static DeclaredFormats parse(String content, String sourceName) throws IOException {
        JsonObject root = parseRoot(content, sourceName);
        JsonObject pack = requiredPackObject(root, sourceName);

        boolean hasMin = pack.has("min_format");
        boolean hasMax = pack.has("max_format");
        if (hasMin || hasMax) {
            if (!hasMin || !hasMax) {
                throw new IOException("Pack metadata must define both min_format and max_format: "
                    + sourceName);
            }
            PackFormat minimum = parseFullVersion(pack.get("min_format"), "min_format", sourceName);
            PackFormat maximum = parseFullVersion(pack.get("max_format"), "max_format", sourceName);
            return checkedRange(maximum, minimum, maximum, sourceName);
        }

        PackFormat primary = parseFullVersion(pack.get("pack_format"), "pack_format", sourceName);
        JsonElement supported = pack.get("supported_formats");
        if (supported == null) {
            return new DeclaredFormats(primary, primary, primary);
        }
        if (supported.isJsonPrimitive()) {
            PackFormat exact = parseFullVersion(supported, "supported_formats", sourceName);
            return checkedRange(primary, exact, exact, sourceName);
        }
        if (!supported.isJsonArray() || supported.getAsJsonArray().size() != 2) {
            throw new IOException("supported_formats must be one format or a two-value range: "
                + sourceName);
        }
        JsonArray range = supported.getAsJsonArray();
        PackFormat minimum = parseFullVersion(range.get(0), "supported_formats minimum", sourceName);
        PackFormat maximum = parseFullVersion(range.get(1), "supported_formats maximum", sourceName);
        return checkedRange(primary, minimum, maximum, sourceName);
    }

    static void rewrite(Path packDirectory, PackFormat target, boolean modernMetadata)
            throws IOException {
        Path metadataPath = packDirectory.resolve("pack.mcmeta");
        JsonObject root;
        if (Files.exists(metadataPath)) {
            String content = PackArchive.readRegularFile(metadataPath, PackArchive.MAX_METADATA_BYTES,
                "pack.mcmeta");
            root = parseRoot(content, metadataPath.toString());
        } else {
            root = new JsonObject();
            JsonObject pack = new JsonObject();
            pack.addProperty("description", "Transformed by Retromod");
            root.add("pack", pack);
        }

        if (root.has("overlays")) {
            throw new IOException("Pack overlay format ranges need manual review: " + metadataPath);
        }

        JsonObject pack = requiredPackObject(root, metadataPath.toString());
        if (modernMetadata) {
            pack.remove("pack_format");
            pack.remove("supported_formats");
            pack.add("min_format", fullVersion(target));
            pack.add("max_format", fullVersion(target));
        } else {
            pack.remove("min_format");
            pack.remove("max_format");
            pack.remove("supported_formats");
            pack.addProperty("pack_format", target.major());
        }

        Files.writeString(metadataPath, OUTPUT_GSON.toJson(root) + System.lineSeparator(),
            StandardCharsets.UTF_8);
    }

    private static JsonObject parseRoot(String content, String sourceName) throws IOException {
        JsonSecurity.validate(content, PackArchive.MAX_METADATA_BYTES,
            JsonSecurity.DEFAULT_MAX_DEPTH, "Pack metadata " + sourceName);
        try {
            JsonElement parsed = com.google.gson.JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                throw new IOException("Pack metadata root must be an object: " + sourceName);
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Could not parse pack metadata " + sourceName + ": "
                + e.getMessage(), e);
        }
    }

    private static JsonObject requiredPackObject(JsonObject root, String sourceName) throws IOException {
        JsonElement pack = root.get("pack");
        if (pack == null || !pack.isJsonObject()) {
            throw new IOException("Pack metadata has no pack object: " + sourceName);
        }
        return pack.getAsJsonObject();
    }

    private static PackFormat parseFullVersion(JsonElement element,
                                                String field,
                                                String sourceName) throws IOException {
        try {
            if (element == null) {
                throw new IOException("Pack metadata has no " + field + ": " + sourceName);
            }
            if (element.isJsonPrimitive()) {
                return new PackFormat(integerComponent(element, field, sourceName), 0);
            }
            if (element.isJsonArray()) {
                JsonArray version = element.getAsJsonArray();
                if (version.size() == 2) {
                    return new PackFormat(
                        integerComponent(version.get(0), field + " major", sourceName),
                        integerComponent(version.get(1), field + " minor", sourceName));
                }
            }
        } catch (ArithmeticException | ClassCastException | IllegalStateException
                 | IllegalArgumentException e) {
            throw new IOException("Invalid " + field + " in pack metadata " + sourceName, e);
        }
        throw new IOException(field + " must be an integer or [major, minor]: " + sourceName);
    }

    private static int integerComponent(JsonElement element,
                                        String field,
                                        String sourceName) throws IOException {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException(field + " must be an integer: " + sourceName);
        }
        BigDecimal value = element.getAsBigDecimal();
        int component = value.intValueExact();
        if (component < 0) {
            throw new IOException(field + " must be nonnegative: " + sourceName);
        }
        return component;
    }

    private static DeclaredFormats checkedRange(PackFormat primary,
                                                 PackFormat minimum,
                                                 PackFormat maximum,
                                                 String sourceName) throws IOException {
        if (minimum.compareTo(maximum) > 0) {
            throw new IOException("Pack metadata minimum exceeds its maximum: " + sourceName);
        }
        if (primary.compareTo(minimum) < 0 || primary.compareTo(maximum) > 0) {
            throw new IOException("Pack metadata pack_format is outside its supported range: "
                + sourceName);
        }
        return new DeclaredFormats(primary, minimum, maximum);
    }

    private static JsonArray fullVersion(PackFormat format) {
        JsonArray version = new JsonArray();
        version.add(format.major());
        version.add(format.minor());
        return version;
    }

    record DeclaredFormats(PackFormat primary, PackFormat minimum, PackFormat maximum) {
        boolean supports(PackFormat target) {
            return minimum.compareTo(target) <= 0 && maximum.compareTo(target) >= 0;
        }
    }
}
