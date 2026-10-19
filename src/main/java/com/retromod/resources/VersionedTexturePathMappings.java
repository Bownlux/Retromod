/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Ordered texture moves introduced after the 1.13 Flattening. */
final class VersionedTexturePathMappings {

    private static final String RESOURCE = "/retromod/texture-migrations.tsv";
    private static final String NEW_LAYOUT = "new_layout";
    private static final Pattern SAFE_PATH = Pattern.compile("[a-z0-9._/-]+\\.png");
    private static final List<Migration> MIGRATIONS = loadMigrations();

    private VersionedTexturePathMappings() {}

    static List<Migration> migrations() {
        return MIGRATIONS;
    }

    /** Where {@code path} in a {@code sourceFormat} pack lives in a {@code targetFormat} pack. */
    static Followed follow(String path, PackFormat sourceFormat, PackFormat targetFormat) {
        String current = path;
        boolean keepsLayout = true;
        // Each move in order: the same chain that apply moves the files along.
        for (Migration migration : MIGRATIONS) {
            if (migration.applies(sourceFormat, targetFormat) && migration.source().equals(current)) {
                current = migration.destination();
                keepsLayout &= migration.keepsLayout();
            }
        }
        return new Followed(current, keepsLayout);
    }

    /** A texture's path after its moves, and whether every move kept the art in the same layout. */
    record Followed(String path, boolean keepsLayout) {
    }

    static int apply(Path texturesDirectory, PackFormat sourceFormat, PackFormat targetFormat)
            throws IOException {
        if (!Files.isDirectory(texturesDirectory, LinkOption.NOFOLLOW_LINKS)) return 0;

        int renamed = 0;
        for (Migration migration : MIGRATIONS) {
            if (!migration.applies(sourceFormat, targetFormat)) continue;
            if (LegacyTexturePathMappings.move(texturesDirectory,
                    migration.source(), migration.destination())) {
                renamed++;
            }
        }
        return renamed;
    }

    private static List<Migration> loadMigrations() {
        List<Migration> migrations = new ArrayList<>();
        Set<String> boundarySources = new HashSet<>();
        Set<String> boundaryDestinations = new HashSet<>();
        try (var input = VersionedTexturePathMappings.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing texture migration catalog: " + RESOURCE);
            }
            try (var reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                int lineNumber = 0;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank() || line.startsWith("#")) continue;
                    // Columns: source maximum format, required format, source, destination, and
                    // an optional new_layout flag.
                    String[] columns = line.split("\\t", -1);
                    if (columns.length != 4 && columns.length != 5) {
                        throw malformed(lineNumber, "expected four or five tab-separated columns");
                    }
                    if (columns.length == 5 && !columns[4].equals(NEW_LAYOUT)) {
                        throw malformed(lineNumber, "the fifth column can only be " + NEW_LAYOUT);
                    }
                    int sourceMaximum;
                    int requiredFormat;
                    try {
                        sourceMaximum = Integer.parseInt(columns[0]);
                        requiredFormat = Integer.parseInt(columns[1]);
                    } catch (NumberFormatException invalidFormat) {
                        throw malformed(lineNumber, "invalid pack format boundary");
                    }
                    if (sourceMaximum <= 0 || requiredFormat <= 0) {
                        throw malformed(lineNumber, "pack formats must be positive");
                    }
                    if (sourceMaximum > requiredFormat) {
                        throw malformed(lineNumber,
                            "source maximum exceeds the required target format");
                    }
                    String source = columns[2];
                    String destination = columns[3];
                    validatePath(source, lineNumber, "source");
                    validatePath(destination, lineNumber, "destination");
                    if (source.equals(destination)) {
                        throw malformed(lineNumber, "source and destination are identical");
                    }
                    if (!boundarySources.add(requiredFormat + "\\0" + source)) {
                        throw malformed(lineNumber, "duplicate source at one format boundary");
                    }
                    if (!boundaryDestinations.add(requiredFormat + "\\0" + destination)) {
                        throw malformed(lineNumber,
                            "duplicate destination at one format boundary");
                    }
                    migrations.add(new Migration(new PackFormat(sourceMaximum, 0),
                        new PackFormat(requiredFormat, 0), source, destination,
                        columns.length == 4));
                }
            }
        } catch (IOException readFailure) {
            throw new ExceptionInInitializerError(readFailure);
        }
        if (migrations.isEmpty()) {
            throw new IllegalStateException("Texture migration catalog is empty: " + RESOURCE);
        }
        migrations.sort((left, right) -> left.requiredFormat()
            .compareTo(right.requiredFormat()));
        return List.copyOf(migrations);
    }

    private static void validatePath(String path, int lineNumber, String column) {
        if (!SAFE_PATH.matcher(path).matches() || path.startsWith("/")
                || path.contains("../")) {
            throw malformed(lineNumber, "unsafe " + column + " texture path");
        }
    }

    private static IllegalStateException malformed(int lineNumber, String reason) {
        return new IllegalStateException("Malformed texture migration catalog line "
            + lineNumber + ": " + reason);
    }

    /** One move. {@code keepsLayout} is false when the new art does not fit old texture coordinates. */
    record Migration(PackFormat sourceMaximum, PackFormat requiredFormat,
                     String source, String destination, boolean keepsLayout) {
        boolean applies(PackFormat sourceFormat, PackFormat targetFormat) {
            return sourceFormat.compareTo(sourceMaximum) <= 0
                && targetFormat.compareTo(requiredFormat) >= 0;
        }
    }
}
