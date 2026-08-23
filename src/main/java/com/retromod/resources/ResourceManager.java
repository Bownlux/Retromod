/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.resources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;

/**
 * Moves staged resource packs and data packs through their matching transformers.
 */
public class ResourceManager {
    
    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Resources");
    private static final String README_NAME = "README.txt";
    private static final String PROCESSED_NAME = "processed";
    private static final String INSTRUCTIONS_NAME = "INSTRUCTIONS.txt";
    private static final String PUBLICATION_STAGE_PREFIX = ".retromod-pack-publish-";
    private static final String TRANSACTION_PREFIX = ".retromod-pack-txn-";
    
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

    /** Creates the pack folders and processes staged packs for any supported loader. */
    public static void processStagedPacks(String targetMcVersion, Path gameDir) {
        ResourceManager manager = new ResourceManager(targetMcVersion, gameDir);
        manager.ensureFolders();
        manager.processAll();
    }
    
    /** Creates the input and archive folders used by pack transforms. */
    public void ensureFolders() {
        try {
            validateManagedPaths();
            Path inputDir = gameDir.resolve("retromod-input");
            Path rpInput = inputDir.resolve("resourcepacks");
            Path dpInput = inputDir.resolve("datapacks");
            
            Files.createDirectories(rpInput);
            Files.createDirectories(dpInput);
            
            Files.createDirectories(rpInput.resolve(PROCESSED_NAME));
            Files.createDirectories(dpInput.resolve(PROCESSED_NAME));

            validateManagedPaths();
            
            createReadme(rpInput, "resource packs");
            createReadme(dpInput, "data packs");
            
        } catch (Exception e) {
            LOGGER.warn("Could not create resource folders: {}", e.getMessage());
        }
    }
    
    private void createReadme(Path folder, String type) throws IOException {
        Path readme = folder.resolve(README_NAME);
        createManagedTextFile(readme, String.format("""
            Retromod %s input

            Put old %s here as zip files or unpacked folders.
            Retromod updates them for Minecraft %s, copies the results to
            the appropriate output folder, and keeps the originals in processed/.
            """, type, type.toLowerCase(), targetMcVersion));
    }

    private static void createManagedTextFile(Path path, String content) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularManagedFile(path);
            return;
        }
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        } catch (FileAlreadyExistsException concurrentCreation) {
            requireRegularManagedFile(path);
        }
    }

    private static void requireRegularManagedFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed instruction file is not a regular file: " + path);
        }
    }
    
    /** Processes every staged resource pack and data pack. */
    public void processAll() {
        try {
            validateManagedPaths();
        } catch (IOException unsafePath) {
            LOGGER.warn("Could not process staged packs: {}", unsafePath.getMessage());
            return;
        }
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
        Path processedDir = inputDir.resolve(PROCESSED_NAME);
        
        if (!Files.exists(inputDir)) return;
        
        try {
            Files.createDirectories(outputDir);
            
            List<PackPlan> plans = new ArrayList<>();
            for (Path pack : stagedPacks(inputDir)) {
                try {
                    refuseInternalWorkflowName(pack);
                    boolean transform = rpTransformer.needsTransformation(pack);
                    plans.add(new PackPlan(pack, transform,
                        plannedOutputName(pack, transform)));
                } catch (Exception e) {
                    LOGGER.warn("Could not inspect resource pack {}: {}",
                        pack.getFileName(), e.getMessage());
                }
            }
            refuseOutputCollisions(plans, outputDir, Set.of());
            for (PackPlan plan : plans) {
                try {
                    processResourcePack(plan, outputDir, processedDir);
                } catch (Exception e) {
                    LOGGER.warn("Could not process resource pack {}: {}",
                        plan.pack().getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not process resource packs: {}", e.getMessage());
        }
    }
    
    private void processResourcePack(PackPlan plan, Path outputDir, Path processedDir)
            throws IOException {
        validateManagedPaths();
        Path pack = plan.pack();
        String name = pack.getFileName().toString();
        boolean transform = plan.transform();
        if (rpTransformer.needsTransformation(pack) != transform) {
            throw new IOException("Staged resource pack changed during processing: " + name);
        }

        publishPackTransaction(pack, outputDir, processedDir, plan.outputName(), stagingDirectory -> {
            if (transform) {
                return rpTransformer.transformPack(pack, stagingDirectory);
            }
            Path stagedOutput = stagingDirectory.resolve(name);
            PackArchive.copyPathAtomically(pack, stagedOutput);
            return stagedOutput;
        });

        if (!transform) {
            LOGGER.info("Copied {} without changes", name);
        } else {
            resourcePacksTransformed++;
        }
    }
    
    private void processDataPacks() {
        Path inputDir = gameDir.resolve("retromod-input/datapacks");
        Path processedDir = inputDir.resolve(PROCESSED_NAME);
        
        if (!Files.exists(inputDir)) return;
        
        // The world is not known at startup, so data packs wait in a shared output folder.
        Path outputDir = gameDir.resolve("retromod-output/datapacks");
        
        try {
            Files.createDirectories(outputDir);
            
            createManagedTextFile(outputDir.resolve(INSTRUCTIONS_NAME), """
                Retromod data pack output

                Copy the updated packs to your world's datapacks folder:
                   .minecraft/saves/[YourWorld]/datapacks/

                Then run /reload in the game.
                """);

            List<PackPlan> plans = new ArrayList<>();
            for (Path pack : stagedPacks(inputDir)) {
                try {
                    refuseInternalWorkflowName(pack);
                    DataPackTransformer.validateDataPack(pack);
                    boolean transform = dpTransformer.needsTransformation(pack);
                    plans.add(new PackPlan(pack, transform,
                        plannedOutputName(pack, transform)));
                } catch (Exception e) {
                    LOGGER.warn("Could not inspect data pack {}: {}",
                        pack.getFileName(), e.getMessage());
                }
            }
            refuseOutputCollisions(plans, outputDir, Set.of(INSTRUCTIONS_NAME));
            for (PackPlan plan : plans) {
                try {
                    processDataPack(plan, outputDir, processedDir);
                } catch (Exception e) {
                    LOGGER.warn("Could not process data pack {}: {}",
                        plan.pack().getFileName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Could not process data packs: {}", e.getMessage());
        }
    }
    
    private void processDataPack(PackPlan plan, Path outputDir, Path processedDir)
            throws IOException {
        validateManagedPaths();
        Path pack = plan.pack();
        DataPackTransformer.validateDataPack(pack);
        String name = pack.getFileName().toString();
        boolean transform = plan.transform();
        if (dpTransformer.needsTransformation(pack) != transform) {
            throw new IOException("Staged data pack changed during processing: " + name);
        }

        publishPackTransaction(pack, outputDir, processedDir, plan.outputName(), stagingDirectory -> {
            if (transform) {
                return dpTransformer.transformPack(pack, stagingDirectory);
            }
            Path stagedOutput = stagingDirectory.resolve(name);
            PackArchive.copyPathAtomically(pack, stagedOutput);
            return stagedOutput;
        });

        if (!transform) {
            LOGGER.info("Copied data pack (already compatible): {}", name);
        } else {
            dataPacksTransformed++;
        }
    }

    /**
     * Stages output first, then archives the input before publishing. A failed archive or
     * publication restores every path that was visible before the transaction.
     */
    private Path publishPackTransaction(Path pack, Path outputDir, Path processedDir,
            String expectedOutputName, PackOutputStager outputStager) throws IOException {
        String name = pack.getFileName().toString();
        Path processed = processedDir.resolve(name);

        // Preserve the old behavior for a zip colliding with a non-empty processed directory.
        // The refusal now happens before any installed output can change.
        if (Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)
                && Files.isDirectory(processed, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Processed pack destination is a directory: " + processed);
        }

        Path publicationStage = Files.createTempDirectory(outputDir, PUBLICATION_STAGE_PREFIX);
        Path transaction = Files.createTempDirectory(processedDir, TRANSACTION_PREFIX);
        Path processedBackup = transaction.resolve("previous-processed");
        Path outputBackup = transaction.resolve("previous-output");
        Path failedOutput = transaction.resolve("failed-output");
        Path failedArchive = transaction.resolve("failed-archive");

        Path finalOutput = null;
        boolean archiveMoveStarted = false;
        boolean outputPublishStarted = false;
        boolean committed = false;
        boolean rollbackComplete = false;
        try {
            Path stagedOutput = outputStager.stage(publicationStage)
                    .toAbsolutePath().normalize();
            Path normalizedStage = publicationStage.toAbsolutePath().normalize();
            if (!normalizedStage.equals(stagedOutput.getParent())) {
                throw new IOException("Staged pack output escapes its publication directory: "
                        + stagedOutput);
            }
            if (!stagedOutput.getFileName().toString().equals(expectedOutputName)) {
                throw new IOException("Staged pack output name changed during processing: "
                        + stagedOutput.getFileName());
            }
            finalOutput = outputDir.resolve(expectedOutputName).toAbsolutePath().normalize();

            if (Files.exists(processed, LinkOption.NOFOLLOW_LINKS)) {
                PackArchive.movePathAtomically(processed, processedBackup);
            }

            archiveMoveStarted = true;
            PackArchive.movePathAtomically(pack, processed);

            if (Files.exists(finalOutput, LinkOption.NOFOLLOW_LINKS)) {
                PackArchive.movePathAtomically(finalOutput, outputBackup);
            }
            outputPublishStarted = true;
            PackArchive.movePathAtomically(stagedOutput, finalOutput);

            committed = true;
            return finalOutput;
        } catch (IOException | RuntimeException failure) {
            IOException rollbackFailure = rollbackPackTransaction(
                    pack, processed, finalOutput,
                    processedBackup, outputBackup,
                    failedArchive, failedOutput,
                    archiveMoveStarted, outputPublishStarted);
            rollbackComplete = rollbackFailure == null;
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            PackArchive.deleteRecursivelyQuietly(publicationStage);
            if (committed || rollbackComplete) {
                PackArchive.deleteRecursivelyQuietly(transaction);
            } else {
                LOGGER.warn("Pack rollback left recoverable transaction data at {}", transaction);
            }
        }
    }

    private static IOException rollbackPackTransaction(
            Path pack, Path processed, Path finalOutput,
            Path processedBackup, Path outputBackup,
            Path failedArchive, Path failedOutput,
            boolean archiveMoveStarted, boolean outputPublishStarted) {
        IOException failure = null;

        if (outputPublishStarted && finalOutput != null
                && Files.exists(finalOutput, LinkOption.NOFOLLOW_LINKS)) {
            failure = rollbackMove(finalOutput, failedOutput, failure);
        }
        if (Files.exists(outputBackup, LinkOption.NOFOLLOW_LINKS) && finalOutput != null) {
            failure = rollbackMove(outputBackup, finalOutput, failure);
        }

        if (archiveMoveStarted && Files.exists(processed, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(pack, LinkOption.NOFOLLOW_LINKS)) {
                failure = rollbackMove(processed, failedArchive, failure);
            } else {
                failure = rollbackMove(processed, pack, failure);
            }
        }
        if (Files.exists(processedBackup, LinkOption.NOFOLLOW_LINKS)) {
            failure = rollbackMove(processedBackup, processed, failure);
        }
        return failure;
    }

    private static IOException rollbackMove(Path source, Path destination, IOException previous) {
        try {
            PackArchive.movePathAtomically(source, destination);
            return previous;
        } catch (IOException rollbackFailure) {
            if (previous == null) return rollbackFailure;
            previous.addSuppressed(rollbackFailure);
            return previous;
        }
    }

    @FunctionalInterface
    private interface PackOutputStager {
        Path stage(Path stagingDirectory) throws IOException;
    }

    private static List<Path> stagedPacks(Path inputDirectory) throws IOException {
        try (var stream = Files.list(inputDirectory)) {
            return stream
                // These exact names belong to the managed input layout, not user packs.
                .filter(path -> !path.getFileName().toString().equals(PROCESSED_NAME))
                .filter(path -> !path.getFileName().toString().equals(README_NAME))
                .sorted()
                .toList();
        }
    }

    private static void refuseInternalWorkflowName(Path pack) throws IOException {
        String name = pack.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith(PUBLICATION_STAGE_PREFIX)
                || name.startsWith(TRANSACTION_PREFIX)) {
            throw new IOException("Staged pack name uses a reserved Retromod workflow prefix: "
                + pack.getFileName());
        }
    }

    private static String plannedOutputName(Path pack, boolean transform) {
        String name = pack.getFileName().toString();
        return transform ? PackArchive.transformedOutputName(name) : name;
    }

    private static void refuseOutputCollisions(List<PackPlan> plans, Path outputDirectory,
            Set<String> reservedNames) throws IOException {
        Map<String, Path> claimedOutputs = new HashMap<>();
        Set<String> reservedOutputs = new HashSet<>();
        for (String reservedName : reservedNames) {
            reservedOutputs.add(outputCollisionKey(outputDirectory.resolve(reservedName)));
        }
        for (PackPlan plan : plans) {
            Path output = outputDirectory.resolve(plan.outputName()).toAbsolutePath().normalize();
            String collisionKey = outputCollisionKey(output);
            if (reservedOutputs.contains(collisionKey)) {
                throw new IOException("Staged pack " + plan.pack().getFileName()
                    + " resolves to reserved output name: " + plan.outputName());
            }
            Path prior = claimedOutputs.putIfAbsent(collisionKey, plan.pack());
            if (prior != null) {
                throw new IOException("Staged packs " + prior.getFileName() + " and "
                    + plan.pack().getFileName() + " resolve to the same output name: "
                    + plan.outputName());
            }
        }
    }

    private static String outputCollisionKey(Path output) {
        return Normalizer.normalize(output.toAbsolutePath().normalize().toString(),
            Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private record PackPlan(Path pack, boolean transform, String outputName) {}

    private void validateManagedPaths() throws IOException {
        for (Path path : List.of(
                gameDir,
                gameDir.resolve("retromod-input"),
                gameDir.resolve("retromod-input/resourcepacks"),
                gameDir.resolve("retromod-input/resourcepacks/processed"),
                gameDir.resolve("retromod-input/datapacks"),
                gameDir.resolve("retromod-input/datapacks/processed"),
                gameDir.resolve("resourcepacks"),
                gameDir.resolve("retromod-output"),
                gameDir.resolve("retromod-output/datapacks"))) {
            PackArchive.validateContainedPath(gameDir, path, "Pack workflow path");
        }
    }
    
    // Getters
    public int getResourcePacksTransformed() { return resourcePacksTransformed; }
    public int getDataPacksTransformed() { return dataPacksTransformed; }
}
