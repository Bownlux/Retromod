/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.core.RetromodVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Transforms Data Packs to work on newer Minecraft versions.
 * 
 * What changes between versions:
 * - pack.mcmeta "pack_format" number (same as resource packs, different values)
 * - JSON schema changes for recipes, loot tables, worldgen
 * - Namespace changes (some vanilla namespaces renamed)
 * - Feature additions/removals
 * 
 * Data Pack Format History:
 * - 4: 1.13 - 1.14.4
 * - 5: 1.15 - 1.16.1
 * - 6: 1.16.2 - 1.16.5
 * - 7: 1.17 - 1.17.1
 * - 8: 1.18 - 1.18.1
 * - 9: 1.18.2
 * - 10: 1.19 - 1.19.3
 * - 12: 1.19.4
 * - 15: 1.20 - 1.20.1
 * - 18: 1.20.2
 * - 26: 1.20.3 - 1.20.4
 * - 41: 1.20.5 - 1.20.6
 * - 48: 1.21 - 1.21.1
 * - 57: 1.21.2 - 1.21.3
 * - 61: 1.21.4
 * - 71: 1.21.5
 * - 80: 1.21.6
 * - 81: 1.21.7 - 1.21.8
 * - 88.0+: 1.21.9 and newer full-version metadata
 */
public class DataPackTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-DataPacks");
    private static final long MAX_TRANSFORMED_JSON_BYTES = 16L * 1024 * 1024;
    
    // Loot table changes
    private static final Map<String, String> LOOT_TABLE_RENAMES = new HashMap<>();
    static {
        LOOT_TABLE_RENAMES.put("minecraft:entities/zombie_pigman", "minecraft:entities/zombified_piglin");
    }
    
    private final PackFormat targetDataFormat;
    private final String targetMcVersion;
    
    public DataPackTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.targetDataFormat = PackFormat.dataTarget(targetMcVersion);
    }
    
    /**
     * Check if a file is a data pack.
     */
    public static boolean isDataPack(Path path) {
        try {
            validateDataPack(path);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    static void validateDataPack(Path path) throws IOException {
        PackMetadata.read(path);
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(path.resolve("data"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Data pack has no data directory: " + path);
            }
            return;
        }
        if (!PackArchive.containsArchiveEntryPrefix(path, "data/")) {
            throw new IOException("Data pack archive has no data directory: " + path);
        }
    }
    
    /**
     * Get data pack format.
     */
    public int getPackFormat(Path packPath) {
        try {
            return PackMetadata.read(packPath).primary().major();
        } catch (IOException e) {
            return -1;
        }
    }
    
    /**
     * Check if pack needs transformation.
     */
    public boolean needsTransformation(Path packPath) throws IOException {
        validateDataPack(packPath);
        PackMetadata.DeclaredFormats formats = PackMetadata.read(packPath);
        refuseDowngrade(formats, packPath);
        return !formats.supports(targetDataFormat) || requiresContentMigration();
    }
    
    /**
     * Transform a data pack.
     */
    public Path transformPack(Path sourcePack, Path outputDir) throws IOException {
        validateDataPack(sourcePack);
        String name = sourcePack.getFileName().toString();
        PackMetadata.DeclaredFormats oldFormats = PackMetadata.read(sourcePack);
        refuseDowngrade(oldFormats, sourcePack);
        PackFormat oldFormat = oldFormats.primary();

        LOGGER.info("Transforming data pack: {} (format {} to {})", name,
            oldFormat.display(), targetDataFormat.display());

        if (oldFormats.supports(targetDataFormat) && !requiresContentMigration()) {
            LOGGER.info("  Pack is already compatible - copying unchanged");
            Path dest = outputDir.resolve(name);
            PackArchive.copyPathAtomically(sourcePack, dest);
            return dest;
        }

        // Create temp directory
        Path tempDir = Files.createTempDirectory("retromod-dp-");
        
        try {
            // Extract
            if (Files.isDirectory(sourcePack, LinkOption.NOFOLLOW_LINKS)) {
                PackArchive.copyDirectoryContents(sourcePack, tempDir);
            } else {
                PackArchive.extractZip(sourcePack, tempDir, "Data pack");
            }

            // Transform pack.mcmeta
            PackMetadata.rewrite(tempDir, targetDataFormat, targetDataFormat.major() >= 82);

            // Transform loot tables if needed
            if (oldFormat.compareTo(new PackFormat(10, 0)) < 0) {
                transformLootTables(tempDir);
            }

            int migrated = ModDataMigrator.migrateTreeChecked(tempDir, targetMcVersion);
            if (migrated > 0) {
                LOGGER.info("  Updated {} data file(s)", migrated);
            }
            if (requiresContentMigration()) {
                ModDataMigrator.validateStrictDataJsonTree(tempDir);
            }
            
            // Repack
            String outputName = PackArchive.transformedOutputName(name);
            Path outputPath = outputDir.resolve(outputName);
            PackArchive.packZip(tempDir, outputPath);
            
            LOGGER.info("  Transformed: {}", outputName);
            return outputPath;
            
        } finally {
            PackArchive.deleteRecursivelyQuietly(tempDir);
        }
    }
    
    /**
     * Transform loot tables.
     */
    private void transformLootTables(Path packDir) throws IOException {
        Path dataDir = packDir.resolve("data");
        if (!Files.exists(dataDir)) return;
        
        try (var stream = Files.walk(dataDir)) {
            for (Path lootTable : stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> hasDirectorySegment(dataDir.relativize(p),
                        "loot_tables", "loot_table"))
                    .toList()) {
                transformLootTableFile(lootTable);
            }
        }
    }

    private void transformLootTableFile(Path lootFile) throws IOException {
        String content = PackArchive.readRegularFile(lootFile, MAX_TRANSFORMED_JSON_BYTES,
            "loot table JSON");

        for (var entry : LOOT_TABLE_RENAMES.entrySet()) {
            content = content.replace("\"" + entry.getKey() + "\"",
                "\"" + entry.getValue() + "\"");
        }

        Files.writeString(lootFile, content);
    }

    private static boolean hasDirectorySegment(Path path, String first, String second) {
        for (Path segment : path) {
            String value = segment.toString();
            if (first.equals(value) || second.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private void refuseDowngrade(PackMetadata.DeclaredFormats formats, Path packPath)
            throws IOException {
        if (formats.minimum().compareTo(targetDataFormat) > 0) {
            throw new IOException("Data pack " + packPath.getFileName()
                + " requires format " + formats.minimum().display()
                + ", which is newer than target format " + targetDataFormat.display());
        }
    }

    private boolean requiresContentMigration() {
        return RetromodVersion.isUnobfuscatedTarget(targetMcVersion);
    }

}
