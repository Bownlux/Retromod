/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Looks up the {@code c:} tag each Forge convention tag became. */
final class ForgeConventionTags {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    // Some paths changed too, so the table names the path each tag moved to, from the version whose
    // newest loader build ships it. The file itself describes its rows and how they were checked.
    private static final String RESOURCE = "/retromod/forge-convention-tags.tsv";

    /** The first NeoForge host that fills {@code c:} tags in place of {@code forge:} ones. */
    static final String FIRST_NEOFORGE_HOST = "1.20.5";
    /** The first Forge host that dropped most {@code forge:} tags. Every row ships there. */
    static final String FIRST_FORGE_HOST = "26.1";

    private static final Pattern TAG_PATH = Pattern.compile("[a-z0-9_./-]+");

    /** One table row: the {@code c:} path and the NeoForge versions that ship it. */
    private record Row(String commonPath, String neoForgeSince, List<String> neoForgeAlso) {

        boolean shipsOnNeoForge(String targetMcVersion) {
            if (atLeast(targetMcVersion, neoForgeSince)) return true;
            if (targetMcVersion == null) return false;
            // An earlier version whose newest NeoForge build ships the c: tag, although a later
            // version's newest build drops it again.
            for (String version : neoForgeAlso) {
                if (RetromodVersion.compareMcVersions(targetMcVersion, version) == 0) return true;
            }
            return false;
        }
    }

    private static final Map<String, Row> ROWS = load();

    private ForgeConventionTags() {}

    /** Whether {@code loader} on {@code targetMcVersion} reads {@code c:} tags, not {@code forge:}. */
    static boolean appliesTo(MigrationContext.Loader loader, String targetMcVersion) {
        // On these hosts only older mods fill forge: tags, so #forge:ingots/iron matched no iron and
        // a mod's own entries never reached the mods that read c:.
        if (loader == MigrationContext.Loader.NEOFORGE) return atLeast(targetMcVersion, FIRST_NEOFORGE_HOST);
        if (loader == MigrationContext.Loader.FORGE) return atLeast(targetMcVersion, FIRST_FORGE_HOST);
        return false;
    }

    /** The {@code c:} path that replaced {@code forge:<forgePath>} on this host, or null to keep the name. */
    static String commonPath(String forgePath, MigrationContext.Loader loader, String targetMcVersion) {
        if (!appliesTo(loader, targetMcVersion)) return null;
        // A path the table does not list keeps its forge: name. Many are a mod's own private tags,
        // which its code still reads under forge:, and moving those would empty them.
        Row row = ROWS.get(forgePath);
        if (row == null) return null;
        // On NeoForge, keep the name when this version's newest build does not ship the c: tag.
        if (loader == MigrationContext.Loader.NEOFORGE && !row.shipsOnNeoForge(targetMcVersion)) {
            return null;
        }
        return row.commonPath();
    }

    /** The number of rows, for tests that check the table loaded. */
    static int size() {
        return ROWS.size();
    }

    private static boolean atLeast(String version, String floor) {
        return version != null && version.matches("\\d+\\.\\d+.*")
                && RetromodVersion.compareMcVersions(version, floor) >= 0;
    }

    /** Reads the bundled table, or an empty one when it is missing. */
    private static Map<String, Row> load() {
        Map<String, Row> rows = new LinkedHashMap<>();
        try (InputStream in = ForgeConventionTags.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                LOGGER.warn("Forge convention tag table {} is missing; forge: tag references "
                        + "will keep their names on hosts that read c: tags.", RESOURCE);
                return Map.of();
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int number = 0;
                while ((line = reader.readLine()) != null) {
                    number++;
                    if (line.isBlank() || line.charAt(0) == '#') continue;
                    // Columns: forge: path, c: path ("=" when it is the same), first version that
                    // ships it, and an optional list of earlier versions that also do.
                    String[] parts = line.split("\t");
                    if (parts.length != 3 && parts.length != 4) {
                        throw malformed(number, "expected three or four columns");
                    }
                    String forgePath = requirePath(parts[0], number);
                    String commonPath = parts[1].equals("=") ? forgePath : requirePath(parts[1], number);
                    String since = requireVersion(parts[2], number);
                    List<String> also = new ArrayList<>();
                    if (parts.length == 4) {
                        for (String version : parts[3].split(",")) {
                            String earlier = requireVersion(version, number);
                            if (RetromodVersion.compareMcVersions(earlier, since) >= 0) {
                                throw malformed(number, "'" + earlier + "' is not earlier than " + since);
                            }
                            also.add(earlier);
                        }
                    }
                    if (rows.put(forgePath, new Row(commonPath, since, List.copyOf(also))) != null) {
                        throw malformed(number, "'" + forgePath + "' is listed twice");
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read {} ({}). Continuing with {} convention tags.",
                    RESOURCE, e.toString(), rows.size());
        }
        return Map.copyOf(rows);
    }

    private static String requireVersion(String value, int lineNumber) {
        if (!value.matches("\\d+\\.\\d+(\\.\\d+)?")) {
            throw malformed(lineNumber, "'" + value + "' is not a Minecraft version");
        }
        return value;
    }

    private static String requirePath(String value, int lineNumber) {
        if (!TAG_PATH.matcher(value).matches() || value.contains("..") || value.startsWith("/")) {
            throw malformed(lineNumber, "'" + value + "' is not a tag path");
        }
        return value;
    }

    private static IllegalStateException malformed(int lineNumber, String reason) {
        return new IllegalStateException(
                "Malformed " + RESOURCE + " at line " + lineNumber + ": " + reason);
    }
}
