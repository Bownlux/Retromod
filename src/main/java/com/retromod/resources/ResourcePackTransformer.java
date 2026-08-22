/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.util.regex.*;

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
 * - 69.0: 1.21.9 - 1.21.10
 * - 75.0: 1.21.11
 * - 84.0: 26.1 - 26.1.2
 * - 88.0: 26.2
 */
public class ResourcePackTransformer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    
    // Pack format for target MC versions
    private static final Map<String, Integer> PACK_FORMATS = new HashMap<>();
    static {
        PACK_FORMATS.put("1.20", 15);
        PACK_FORMATS.put("1.20.1", 15);
        PACK_FORMATS.put("1.20.2", 18);
        PACK_FORMATS.put("1.20.3", 22);
        PACK_FORMATS.put("1.20.4", 22);
        PACK_FORMATS.put("1.20.5", 32);
        PACK_FORMATS.put("1.20.6", 32);
        PACK_FORMATS.put("1.21", 34);
        PACK_FORMATS.put("1.21.1", 34);
        PACK_FORMATS.put("1.21.2", 42);
        PACK_FORMATS.put("1.21.3", 42);
        PACK_FORMATS.put("1.21.4", 46);
        PACK_FORMATS.put("1.21.5", 55);
        PACK_FORMATS.put("1.21.6", 63);
        PACK_FORMATS.put("1.21.7", 64);
        PACK_FORMATS.put("1.21.8", 64);
        // 1.21.9 and newer use versions with dots in them
        // and are not included in this map.
    }
    
    // Texture path renames between versions (old -> new)
    private static final Map<String, String> TEXTURE_RENAMES = new HashMap<>();
    static {
        // 1.13 flattening renames
        // initial set added all the way back in the initial commit
        TEXTURE_RENAMES.put("grass_side", "grass_block_side");
        TEXTURE_RENAMES.put("grass_top", "grass_block_top");
        TEXTURE_RENAMES.put("hardened_clay", "terracotta");
        TEXTURE_RENAMES.put("stone_slab_top", "smooth_stone");
        TEXTURE_RENAMES.put("stone_slab_side", "smooth_stone_slab_side");
        TEXTURE_RENAMES.put("mob_spawner", "spawner");
        TEXTURE_RENAMES.put("noteblock", "note_block");
        TEXTURE_RENAMES.put("workbench", "crafting_table");
        TEXTURE_RENAMES.put("furnace_front_on", "furnace_front_lit");
        TEXTURE_RENAMES.put("redstone_torch_on", "redstone_torch_lit");
        TEXTURE_RENAMES.put("comparator_on", "comparator_lit");
        TEXTURE_RENAMES.put("repeater_on", "repeater_lit");
        // Wool colors
        TEXTURE_RENAMES.put("wool_colored_black", "black_wool");
        TEXTURE_RENAMES.put("wool_colored_blue", "blue_wool");
        TEXTURE_RENAMES.put("wool_colored_brown", "brown_wool");
        TEXTURE_RENAMES.put("wool_colored_cyan", "cyan_wool");
        TEXTURE_RENAMES.put("wool_colored_gray", "gray_wool");
        TEXTURE_RENAMES.put("wool_colored_green", "green_wool");
        TEXTURE_RENAMES.put("wool_colored_light_blue", "light_blue_wool");
        TEXTURE_RENAMES.put("wool_colored_lime", "lime_wool");
        TEXTURE_RENAMES.put("wool_colored_magenta", "magenta_wool");
        TEXTURE_RENAMES.put("wool_colored_orange", "orange_wool");
        TEXTURE_RENAMES.put("wool_colored_pink", "pink_wool");
        TEXTURE_RENAMES.put("wool_colored_purple", "purple_wool");
        TEXTURE_RENAMES.put("wool_colored_red", "red_wool");
        TEXTURE_RENAMES.put("wool_colored_silver", "light_gray_wool");
        TEXTURE_RENAMES.put("wool_colored_white", "white_wool");
        TEXTURE_RENAMES.put("wool_colored_yellow", "yellow_wool");

        // Add more as needed
    }
    
    private final String targetMcVersion;
    private final int targetPackFormat;
    
    public ResourcePackTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.targetPackFormat = PACK_FORMATS.getOrDefault(targetMcVersion, 46);
    }
    
    /**
     * Check if a file is a resource pack.
     */
    public static boolean isResourcePack(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                return zip.getEntry("pack.mcmeta") != null;
            } catch (Exception e) {
                return false;
            }
        }
        if (Files.isDirectory(path)) {
            return Files.exists(path.resolve("pack.mcmeta"));
        }
        return false;
    }
    
    /**
     * Get pack format from a resource pack.
     */
    public int getPackFormat(Path packPath) {
        try {
            String mcmeta = readPackMcmeta(packPath);
            if (mcmeta != null) {
                Pattern p = Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+)");
                Matcher m = p.matcher(mcmeta);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return -1;
    }
    
    /**
     * Check if pack needs transformation.
     */
    public boolean needsTransformation(Path packPath) {
        int format = getPackFormat(packPath);
        return format > 0 && format < targetPackFormat;
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
        int oldFormat = getPackFormat(sourcePack);
        
        LOGGER.info("Transforming resource pack: {} (format {} → {})", name, oldFormat, targetPackFormat);
        
        // If already correct format, just copy
        if (oldFormat >= targetPackFormat) {
            LOGGER.info("  Pack is already compatible - copying unchanged");
            Path dest = outputDir.resolve(name);
            if (Files.isDirectory(sourcePack)) {
                copyDirectory(sourcePack, dest);
            } else {
                Files.copy(sourcePack, dest, StandardCopyOption.REPLACE_EXISTING);
            }
            return dest;
        }
        
        // Create temp directory for transformation
        Path tempDir = Files.createTempDirectory("retromod-rp-");
        
        try {
            // Extract pack
            if (Files.isDirectory(sourcePack)) {
                copyDirectory(sourcePack, tempDir);
            } else {
                extractZip(sourcePack, tempDir);
            }
            
            // Transform pack.mcmeta
            transformPackMcmeta(tempDir);
            
            // Transform texture paths if needed
            if (oldFormat < 4) {
                // Pre-1.13 pack: needs path transforms
                transformTexturePaths(tempDir);
            }
            
            // Repack
            String outputName = name.replace(".zip", "") + "-retromod.zip";
            Path outputPath = outputDir.resolve(outputName);
            packZip(tempDir, outputPath);
            
            LOGGER.info("  Transformed: {}", outputName);
            return outputPath;
            
        } finally {
            // Cleanup temp
            deleteDirectory(tempDir);
        }
    }
    
    /**
     * Transform pack.mcmeta to target format.
     */
    private void transformPackMcmeta(Path packDir) throws IOException {
        Path mcmeta = packDir.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            // Create one
            Files.writeString(mcmeta, String.format("""
                {
                    "pack": {
                        "pack_format": %d,
                        "description": "Transformed by Retromod"
                    }
                }
                """, targetPackFormat));
            return;
        }
        
        String content = Files.readString(mcmeta);
        
        // Update pack_format
        content = content.replaceAll(
            "\"pack_format\"\\s*:\\s*\\d+",
            "\"pack_format\": " + targetPackFormat
        );
        
        // Add supported_formats for newer versions (1.20.2+)
        if (targetPackFormat >= 18 && !content.contains("supported_formats")) {
            // Insert supported_formats after pack_format
            content = content.replaceAll(
                "(\"pack_format\"\\s*:\\s*" + targetPackFormat + ")",
                "$1,\n        \"supported_formats\": [" + (targetPackFormat - 10) + ", " + targetPackFormat + "]"
            );
        }
        
        Files.writeString(mcmeta, content);
    }
    
    /**
     * Transform texture paths for pre-1.13 packs.
     */
    private void transformTexturePaths(Path packDir) throws IOException {
        Path texturesDir = packDir.resolve("assets/minecraft/textures");
        if (!Files.exists(texturesDir)) return;
        
        // Check for old structure (blocks/ vs block/)
        Path oldBlocks = texturesDir.resolve("blocks");
        Path newBlocks = texturesDir.resolve("block");
        if (Files.exists(oldBlocks) && !Files.exists(newBlocks)) {
            Files.move(oldBlocks, newBlocks);
            LOGGER.debug("  Renamed textures/blocks → textures/block");
        }
        
        Path oldItems = texturesDir.resolve("items");
        Path newItems = texturesDir.resolve("item");
        if (Files.exists(oldItems) && !Files.exists(newItems)) {
            Files.move(oldItems, newItems);
            LOGGER.debug("  Renamed textures/items → textures/item");
        }
        
        // Rename individual textures
        for (var entry : TEXTURE_RENAMES.entrySet()) {
            renameTexture(texturesDir, entry.getKey(), entry.getValue());
        }
    }
    
    /**
     * Rename a texture file if it exists.
     */
    private void renameTexture(Path texturesDir, String oldName, String newName) {
        try (var stream = Files.walk(texturesDir)) {
            stream.filter(p -> p.getFileName().toString().equals(oldName + ".png"))
                  .forEach(p -> {
                      try {
                          Path newPath = p.getParent().resolve(newName + ".png");
                          if (!Files.exists(newPath)) {
                              Files.move(p, newPath);
                              LOGGER.debug("  Renamed {} → {}", oldName, newName);
                          }
                      } catch (Exception e) {
                          // Ignore
                      }
                  });
        } catch (Exception e) {
            // Ignore
        }
    }
    
    
    private String readPackMcmeta(Path packPath) throws IOException {
        if (Files.isDirectory(packPath)) {
            Path mcmeta = packPath.resolve("pack.mcmeta");
            return Files.exists(mcmeta) ? Files.readString(mcmeta) : null;
        } else {
            try (ZipFile zip = new ZipFile(packPath.toFile())) {
                var entry = zip.getEntry("pack.mcmeta");
                if (entry != null) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        return new String(is.readAllBytes());
                    }
                }
            }
        }
        return null;
    }
    
    private void extractZip(Path zipPath, Path outputDir) throws IOException {
        // Use bounded extraction (ZipSecurity.copyBounded) rather than
        // Files.copy(is, …): resource packs are user-supplied ZIPs and an
        // attacker-crafted entry can lie about its declared size to slip
        // past any header-based check. We count actual decompressed bytes
        // and enforce both per-entry and per-archive caps.
        long totalSize = 0;
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                Path outPath = ZipSecurity.safeResolve(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(outPath);
                } else {
                    Files.createDirectories(outPath.getParent());
                    long writtenBytes;
                    try (InputStream is = zip.getInputStream(entry)) {
                        writtenBytes = ZipSecurity.copyBounded(
                            is, outPath, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, entry.getName());
                    }
                    totalSize += writtenBytes;
                    if (totalSize > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                        throw new IOException("Resource pack total extracted size exceeds limit ("
                            + ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes) - possible zip bomb "
                            + "(decompressed " + totalSize + " bytes so far)");
                    }
                }
            }
        }
    }
    
    private void packZip(Path sourceDir, Path zipPath) throws IOException {
        Files.deleteIfExists(zipPath);
        try (var zos = new ZipOutputStream(new FileOutputStream(zipPath.toFile()))) {
            try (var stream = Files.walk(sourceDir)) {
                stream.filter(p -> !Files.isDirectory(p)).forEach(path -> {
                    try {
                        String entryName = sourceDir.relativize(path).toString().replace("\\", "/");
                        zos.putNextEntry(new ZipEntry(entryName));
                        Files.copy(path, zos);
                        zos.closeEntry();
                    } catch (Exception e) {
                        // Ignore
                    }
                });
            }
        }
    }
    
    private void copyDirectory(Path source, Path dest) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(src -> {
                try {
                    Path dst = dest.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dst);
                    } else {
                        Files.createDirectories(dst.getParent());
                        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    // Ignore
                }
            });
        }
    }
    
    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> -a.compareTo(b))
                  .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        } catch (Exception e) {
            // Ignore
        }
    }
}
