/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Transforms Resource Packs (texture packs) to work on newer Minecraft versions.
 * 
 * What changes between versions:
 * - pack.mcmeta "pack_format" number
 * - Some texture paths (renamed blocks/items)
 * - Some JSON model formats
 * - Some sound paths
 * 
 * Pack Format History:
 * - 1: 1.6.1 - 1.8.9
 * - 2: 1.9 - 1.10.2
 * - 3: 1.11 - 1.12.2
 * - 4: 1.13 - 1.14.4
 * - 5: 1.15 - 1.16.1
 * - 6: 1.16.2 - 1.16.5
 * - 7: 1.17 - 1.17.1
 * - 8: 1.18 - 1.18.2
 * - 9: 1.19 - 1.19.2
 * - 12: 1.19.3
 * - 13: 1.19.4
 * - 15: 1.20 - 1.20.1
 * - 18: 1.20.2
 * - 22: 1.20.3 - 1.20.4
 * - 32: 1.20.5 - 1.20.6
 * - 34: 1.21 - 1.21.1
 * - 42: 1.21.2 - 1.21.3
 * - 46: 1.21.4
 * - 55: 1.21.5
 * - 63: 1.21.6
 * - 64: 1.21.7 - 1.21.8
 * - 69.0+: 1.21.9 and newer full-version metadata
 */
public class ResourcePackTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    
    // Texture path renames between versions (old -> new)
    static final Map<String, String> TEXTURE_RENAMES = new HashMap<>();
    static {
        // 1.13 flattening renames
        TEXTURE_RENAMES.put("grass_side", "grass_block_side");
        TEXTURE_RENAMES.put("grass_top", "grass_block_top");
        TEXTURE_RENAMES.put("hardened_clay", "terracotta");
        TEXTURE_RENAMES.put("stone_slab_top", "smooth_stone");
        TEXTURE_RENAMES.put("stone_slab_side", "smooth_stone_slab_side");
        TEXTURE_RENAMES.put("mob_spawner", "spawner");
        TEXTURE_RENAMES.put("noteblock", "note_block");
        TEXTURE_RENAMES.put("workbench", "crafting_table");
        TEXTURE_RENAMES.put("redstone_torch_on", "redstone_torch");
        // Add more as needed
    }
    
    private final PackFormat targetPackFormat;
    private final String targetMcVersion;
    
    public ResourcePackTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.targetPackFormat = PackFormat.resourceTarget(targetMcVersion);
    }
    
    /**
     * Check if a file is a resource pack.
     */
    public static boolean isResourcePack(Path path) {
        try {
            PackMetadata.read(path);
            return true;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }
    
    /**
     * Get pack format from a resource pack.
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
        PackMetadata.DeclaredFormats formats = PackMetadata.read(packPath);
        refuseDowngrade(formats, packPath);
        return !formats.supports(targetPackFormat);
    }
    
    /**
     * Transform a resource pack to work with target version.
     * 
     * @param sourcePack Path to original pack (.zip or folder)
     * @param outputDir Directory to write transformed pack
     * @return Path to transformed pack
     */
    public Path transformPack(Path sourcePack, Path outputDir) throws IOException {
        String name = sourcePack.getFileName().toString();
        PackMetadata.DeclaredFormats oldFormats = PackMetadata.read(sourcePack);
        refuseDowngrade(oldFormats, sourcePack);
        PackFormat oldFormat = oldFormats.primary();
        
        LOGGER.info("Transforming resource pack: {} (format {} to {})", name,
            oldFormat.display(), targetPackFormat.display());
        
        if (oldFormats.supports(targetPackFormat)) {
            LOGGER.info("  Pack is already compatible - copying unchanged");
            Path dest = outputDir.resolve(name);
            PackArchive.copyPathAtomically(sourcePack, dest);
            return dest;
        }
        
        // Create temp directory for transformation
        Path tempDir = Files.createTempDirectory("retromod-rp-");
        
        try {
            // Extract pack
            if (Files.isDirectory(sourcePack, LinkOption.NOFOLLOW_LINKS)) {
                PackArchive.copyDirectoryContents(sourcePack, tempDir);
            } else {
                PackArchive.extractZip(sourcePack, tempDir, "Resource pack");
            }
            
            // Transform pack.mcmeta
            PackMetadata.rewrite(tempDir, targetPackFormat, targetPackFormat.major() >= 65);
            
            // Transform texture paths if needed
            if (oldFormat.compareTo(new PackFormat(4, 0)) < 0) {
                // Pre-1.13 pack: needs path transforms
                transformTexturePaths(tempDir);
            }

            int movedTextures = 0;
            for (Path namespace : PackNamespaces.list(tempDir)) {
                movedTextures += VersionedTexturePathMappings.apply(
                    namespace.resolve("textures"), oldFormat, targetPackFormat);
            }
            if (movedTextures > 0) {
                LOGGER.info("  Updated {} versioned texture path(s)", movedTextures);
            }

            // Moving a texture without repointing the models that name it leaves a pack that looks
            // converted and renders the missing-texture checkerboard.
            int repointed = PackTextureReferences.rewrite(
                tempDir, blockRenames(), FlatteningTextureRenames.items());
            if (repointed > 0) {
                LOGGER.info("  Repointed texture references in {} model(s)", repointed);
            }

            int migrated = ModDataMigrator.migrateTreeChecked(tempDir, targetMcVersion);
            if (migrated > 0) {
                LOGGER.info("  Updated {} resource file(s)", migrated);
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
     * Transform texture paths for pre-1.13 packs.
     */
    private void transformTexturePaths(Path packDir) throws IOException {
        // A pack that also skins mods keeps those textures under assets/<modid>, and they followed
        // the same 1.13 layout change, so every namespace gets the same treatment.
        for (Path namespace : PackNamespaces.list(packDir)) {
            transformTexturePathsIn(namespace.resolve("textures"));
        }
    }

    private void transformTexturePathsIn(Path texturesDir) throws IOException {
        if (!Files.exists(texturesDir)) return;
        
        // Check for old structure (blocks/ vs block/)
        Path oldBlocks = texturesDir.resolve("blocks");
        Path newBlocks = texturesDir.resolve("block");
        if (Files.exists(oldBlocks)) {
            mergeTextureDirectory(oldBlocks, newBlocks);
            LOGGER.debug("  Renamed textures/blocks → textures/block");
        }
        
        Path oldItems = texturesDir.resolve("items");
        Path newItems = texturesDir.resolve("item");
        if (Files.exists(oldItems)) {
            mergeTextureDirectory(oldItems, newItems);
            LOGGER.debug("  Renamed textures/items → textures/item");
        }
        
        // These are block texture names. Item textures with the same basename are unrelated:
        // a 1.12.2 pack ships both blocks/brick.png and items/brick.png, and only the block one
        // became bricks. The curated table is applied last so a hand-checked entry always wins.
        for (var entry : blockRenames().entrySet()) {
            renameTexture(newBlocks, entry.getKey(), entry.getValue());
        }
        for (var entry : FlatteningTextureRenames.items().entrySet()) {
            renameTexture(newItems, entry.getKey(), entry.getValue());
        }

        int movedTextures = LegacyTexturePathMappings.apply(texturesDir);
        if (movedTextures > 0) {
            LOGGER.debug("  Renamed {} legacy entity texture(s)", movedTextures);
        }
    }

    /**
     * The derived Flattening renames with the curated table layered on top.
     *
     * <p>The derived table comes from the vanilla jars and covers the whole Flattening. The curated
     * one is hand-checked and narrower, so it is applied second and wins any disagreement.
     */
    static Map<String, String> blockRenames() {
        Map<String, String> merged = new HashMap<>(FlatteningTextureRenames.blocks());
        merged.putAll(TEXTURE_RENAMES);
        return merged;
    }

    private void mergeTextureDirectory(Path source, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.move(source, destination);
            return;
        }
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Texture destination is not a directory: " + destination);
        }

        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                if (path.equals(source)) continue;
                Path target = destination.resolve(source.relativize(path).toString()).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Texture path escapes its destination: " + path);
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                        // A pack that ships both layouts is telling us which one it means: the file
                        // already on the current path is the one the author maintained. Failing here
                        // would reject the whole pack over a duplicate that has an obvious winner.
                        LOGGER.debug("  Kept the existing {} over its legacy copy",
                                destination.relativize(target));
                        Files.delete(path);
                        continue;
                    }
                    Files.createDirectories(target.getParent());
                    Files.move(path, target);
                }
            }
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.delete(path);
            }
        }
    }
    
    /**
     * Rename a texture file if it exists.
     */
    private void renameTexture(Path blockTextures, String oldName, String newName) throws IOException {
        if (!Files.isDirectory(blockTextures, LinkOption.NOFOLLOW_LINKS)) return;
        try (var stream = Files.walk(blockTextures)) {
            for (Path path : stream
                    .filter(p -> p.getFileName().toString().equals(oldName + ".png"))
                    .toList()) {
                Path newPath = path.getParent().resolve(newName + ".png");
                Path oldMetadata = path.resolveSibling(path.getFileName() + ".mcmeta");
                Path newMetadata = newPath.resolveSibling(newPath.getFileName() + ".mcmeta");
                if (Files.exists(newPath, LinkOption.NOFOLLOW_LINKS)
                        || Files.exists(newMetadata, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Texture rename destination already exists: " + newPath);
                }
                Files.move(path, newPath);
                if (Files.exists(oldMetadata, LinkOption.NOFOLLOW_LINKS)) {
                    Files.move(oldMetadata, newMetadata);
                }
                LOGGER.debug("  Renamed {} to {}", oldName, newName);
            }
        }
    }

    private void refuseDowngrade(PackMetadata.DeclaredFormats formats, Path packPath)
            throws IOException {
        if (formats.minimum().compareTo(targetPackFormat) > 0) {
            throw new IOException("Resource pack " + packPath.getFileName()
                + " requires format " + formats.minimum().display()
                + ", which is newer than target format " + targetPackFormat.display());
        }
    }

}
