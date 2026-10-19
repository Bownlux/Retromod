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

/** Updates data packs to work on newer Minecraft versions. */
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

            // Rewrite pack.mcmeta. Data packs share the pack_format field with resource packs but
            // use different numbers; PackFormat holds the version table.
            PackMetadata.rewrite(tempDir, targetDataFormat, targetDataFormat.major() >= 82);

            // Transform loot tables if needed
            if (oldFormat.compareTo(new PackFormat(10, 0)) < 0) {
                transformLootTables(tempDir);
            }

            // The same converters a mod's own data goes through. A pack names no game version,
            // so its layout follows the format it declares.
            MigrationContext context = MigrationContext.builder(targetMcVersion)
                .sourceMcVersion(PackFormat.minecraftVersionForData(oldFormat))
                .targetLoader(MigrationContext.Loader.host())
                .build();
            int migrated = ModResourceMigrations.migrateDirectory(tempDir, context);
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
