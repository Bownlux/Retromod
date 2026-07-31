/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Moves staged resource packs and data packs through their matching transformers.
 */
public class ResourceManager {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    
    private final String targetMcVersion;
    private final Path gameDir;
    private final ResourcePackTransformer rpTransformer;
    private final DataPackTransformer dpTransformer;
    
    private int resourcePacksTransformed = 0;
    private int dataPacksTransformed = 0;
    
    public ResourceManager(String targetMcVersion, Path gameDir) {
        this.targetMcVersion = targetMcVersion;
        this.gameDir = gameDir;
        this.rpTransformer = new ResourcePackTransformer(targetMcVersion);
        this.dpTransformer = new DataPackTransformer(targetMcVersion);
    }
    
    /** Creates the input and archive folders used by pack transforms. */
    public void ensureFolders() {
        try {
            Path inputDir = gameDir.resolve("retromod-input");
            Path rpInput = inputDir.resolve("resourcepacks");
            Path dpInput = inputDir.resolve("datapacks");
            
            Files.createDirectories(rpInput);
            Files.createDirectories(dpInput);
            
            Files.createDirectories(rpInput.resolve("processed"));
            Files.createDirectories(dpInput.resolve("processed"));
            
            createReadme(rpInput, "resource packs");
            createReadme(dpInput, "data packs");
            
        } catch (Exception e) {
            LOGGER.warn("Could not create resource folders: {}", e.getMessage());
        }
    }
    
    private void createReadme(Path folder, String type) throws IOException {
        Path readme = folder.resolve("README.txt");
        if (Files.exists(readme)) return;
        
        Files.writeString(readme, String.format("""
            Retromod %s input

            Put old %s here as zip files or unpacked folders.
            Retromod updates them for Minecraft %s, copies the results to
            the appropriate output folder, and keeps the originals in processed/.
            """, type, type.toLowerCase(), targetMcVersion));
    }
    
    /** Processes every staged resource pack and data pack. */
    public void processAll() {
        processResourcePacks();
        processDataPacks();
        
        if (resourcePacksTransformed > 0 || dataPacksTransformed > 0) {
            LOGGER.info("Updated {} resource pack(s) and {} data pack(s)",
                resourcePacksTransformed, dataPacksTransformed);
        }
    }
    
    private void processResourcePacks() {
        Path inputDir = gameDir.resolve("retromod-input/resourcepacks");
        Path outputDir = gameDir.resolve("resourcepacks");
        Path processedDir = inputDir.resolve("processed");
        
        if (!Files.exists(inputDir)) return;
        
        try {
            Files.createDirectories(outputDir);
            
            try (var stream = Files.list(inputDir)) {
                stream.filter(p -> !p.getFileName().toString().equals("processed"))
                      .filter(p -> !p.getFileName().toString().equals("README.txt"))
                      .filter(p -> isResourcePack(p))
                      .forEach(pack -> {
                          try {
                              processResourcePack(pack, outputDir, processedDir);
                          } catch (Exception e) {
                              LOGGER.warn("Could not process resource pack {}: {}", 
                                  pack.getFileName(), e.getMessage());
                          }
                      });
            }
        } catch (Exception e) {
            LOGGER.debug("Could not process resource packs: {}", e.getMessage());
        }
    }
    
    private void processResourcePack(Path pack, Path outputDir, Path processedDir) throws IOException {
        String name = pack.getFileName().toString();
        
        Path processedMarker = processedDir.resolve(name + ".done");
        if (Files.exists(processedMarker)) {
            return;
        }
        
        if (!rpTransformer.needsTransformation(pack)) {
            Path dest = outputDir.resolve(name);
            if (!Files.exists(dest)) {
                if (Files.isDirectory(pack)) {
                    copyDirectory(pack, dest);
                } else {
                    Files.copy(pack, dest);
                }
                LOGGER.info("Copied {} without changes", name);
            }
        } else {
            rpTransformer.transformPack(pack, outputDir);
            resourcePacksTransformed++;
        }
        
        Path processed = processedDir.resolve(name);
        if (Files.isDirectory(pack)) {
            moveDirectory(pack, processed);
        } else {
            Files.move(pack, processed, StandardCopyOption.REPLACE_EXISTING);
        }
        
        Files.writeString(processedMarker, "Processed by Retromod");
    }
    
    private void processDataPacks() {
        Path inputDir = gameDir.resolve("retromod-input/datapacks");
        Path processedDir = inputDir.resolve("processed");
        
        if (!Files.exists(inputDir)) return;
        
        // The world is not known at startup, so data packs wait in a shared output folder.
        Path outputDir = gameDir.resolve("retromod-output/datapacks");
        
        try {
            Files.createDirectories(outputDir);
            
            Path instructions = outputDir.resolve("INSTRUCTIONS.txt");
            if (!Files.exists(instructions)) {
                Files.writeString(instructions, """
                    Retromod data pack output

                    Copy the updated packs to your world's datapacks folder:
                       .minecraft/saves/[YourWorld]/datapacks/

                    Then run /reload in the game.
                    """);
            }
            
            try (var stream = Files.list(inputDir)) {
                stream.filter(p -> !p.getFileName().toString().equals("processed"))
                      .filter(p -> !p.getFileName().toString().equals("README.txt"))
                      .filter(p -> isDataPack(p))
                      .forEach(pack -> {
                          try {
                              processDataPack(pack, outputDir, processedDir);
                          } catch (Exception e) {
                              LOGGER.warn("Could not process data pack {}: {}", 
                                  pack.getFileName(), e.getMessage());
                          }
                      });
            }
        } catch (Exception e) {
            LOGGER.debug("Could not process data packs: {}", e.getMessage());
        }
    }
    
    private void processDataPack(Path pack, Path outputDir, Path processedDir) throws IOException {
        String name = pack.getFileName().toString();
        
        Path processedMarker = processedDir.resolve(name + ".done");
        if (Files.exists(processedMarker)) {
            return;
        }
        
        // Check if needs transformation
        if (!dpTransformer.needsTransformation(pack)) {
            Path dest = outputDir.resolve(name);
            if (!Files.exists(dest)) {
                if (Files.isDirectory(pack)) {
                    copyDirectory(pack, dest);
                } else {
                    Files.copy(pack, dest);
                }
                LOGGER.info("Copied data pack (already compatible): {}", name);
            }
        } else {
            dpTransformer.transformPack(pack, outputDir);
            dataPacksTransformed++;
        }
        
        // Move original
        Path processed = processedDir.resolve(name);
        if (Files.isDirectory(pack)) {
            moveDirectory(pack, processed);
        } else {
            Files.move(pack, processed, StandardCopyOption.REPLACE_EXISTING);
        }
        
        Files.writeString(processedMarker, "Processed by Retromod");
    }
    
    
    private boolean isResourcePack(Path path) {
        return ResourcePackTransformer.isResourcePack(path);
    }
    
    private boolean isDataPack(Path path) {
        return DataPackTransformer.isDataPack(path);
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
                } catch (Exception e) {}
            });
        }
    }
    
    private void moveDirectory(Path source, Path dest) throws IOException {
        copyDirectory(source, dest);
        deleteDirectory(source);
    }
    
    private void deleteDirectory(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> -a.compareTo(b))
                  .forEach(p -> { try { Files.delete(p); } catch (Exception e) {} });
        } catch (Exception e) {}
    }
    
    // Getters
    public int getResourcePacksTransformed() { return resourcePacksTransformed; }
    public int getDataPacksTransformed() { return dataPacksTransformed; }
}
