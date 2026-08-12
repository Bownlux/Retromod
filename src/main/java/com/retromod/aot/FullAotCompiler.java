/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.EnvironmentDetector;
import com.retromod.core.RetromodTransformer;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * One-time heavy compilation: transforms every class in the given mods upfront and caches the
 * result, so later launches read cached bytecode instead of transforming on the fly. Triggered
 * from the Fabric first-launch dialog or the Forge/NeoForge add-mods GUI.
 */
public class FullAotCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-FullAOT");

    private static final String CACHE_DIR = "retromod-cache/full-aot";
    private static final long MAX_EXPANDED_BYTES = ZipSecurity.DEFAULT_MAX_TOTAL_SIZE;

    private static FullAotCompiler instance;

    private final ShimRegistry shimRegistry;
    private final String targetVersion;
    private final Path cacheDir;

    private volatile int totalClasses = 0;
    private volatile int compiledClasses = 0;
    private volatile String currentMod = "";
    private volatile String currentClass = "";
    private volatile boolean isRunning = false;
    private volatile boolean wasCancelled = false;

    private final List<ProgressListener> listeners = new CopyOnWriteArrayList<>();
    
    public interface ProgressListener {
        void onProgress(int compiled, int total, String mod, String className);
        void onComplete(int totalCompiled, long timeMs);
        void onError(String mod, String className, String error);
    }
    
    private FullAotCompiler(Path gameDir, String targetVersion) {
        this.shimRegistry = new ShimRegistry();
        this.targetVersion = targetVersion;
        this.cacheDir = gameDir.resolve(CACHE_DIR);

        // Creates the cache dir AND wipes it when the Retromod build changed since it was
        // written: nothing below validates the cached per-class files, so without the stamp
        // an updated Retromod would keep serving the previous build's transforms.
        AotCacheStamp.ensureCurrent(cacheDir);
    }
    
    public static synchronized FullAotCompiler getInstance(Path gameDir, String targetVersion) {
        if (instance == null) {
            instance = new FullAotCompiler(gameDir, targetVersion);
        }
        return instance;
    }
    
    /**
     * Reduces a mod id to a single safe directory name.
     *
     * <p>The id is read out of the mod's own metadata, or falls back to its file name, so it is
     * untrusted. Without this an id such as {@code ../../evil} would put the cache, and the class
     * files read back out of it, anywhere the game can write.
     */
    static String safeModId(String modId) {
        if (modId == null || modId.isBlank()) return "unnamed-mod";
        String cleaned = modId.replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        if (cleaned.isBlank()) return "unnamed-mod";
        boolean changed = !cleaned.equals(modId);
        if (cleaned.length() > 160) {
            cleaned = cleaned.substring(0, 160);
            changed = true;
        }
        return changed ? cleaned + "_" + shortDigest(modId) : cleaned;
    }

    public boolean hasCachedCompilation(String modId) {
        Path modCache = safeModCacheDir(modId);
        return isCompletedCache(modCache);
    }

    private boolean isCompletedCache(Path modCache) {
        Path marker = modCache.resolve(".complete");
        try {
            ZipSecurity.validateNotSymlink(cacheDir);
            ZipSecurity.validateNotSymlink(modCache);
            ZipSecurity.validateNotSymlink(marker);
            return Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return false;
        }
    }

    public byte[] getCachedClass(String modId, String className) {
        Path modCache = safeModCacheDir(modId);
        Path classCache = modCache.resolve(safeClassCacheFileName(className));

        if (!isCompletedCache(modCache)) return null;
        
        if (Files.isRegularFile(classCache, LinkOption.NOFOLLOW_LINKS)) {
            try {
                ZipSecurity.validateNotSymlink(cacheDir);
                ZipSecurity.validateNotSymlink(modCache);
                ZipSecurity.validateNotSymlink(classCache);
                try (InputStream input = Files.newInputStream(classCache)) {
                    return ZipSecurity.safeReadAllBytes(input);
                }
            } catch (IOException e) {
                LOGGER.debug("Could not read cached class: {}", className);
            }
        }
        
        return null;
    }
    
    /**
     * Transform and cache every class in the given mod JARs. Returns the count of classes compiled.
     */
    public CompletableFuture<Integer> runFullCompilation(List<Path> modsToCompile) {
        if (isRunning) {
            return CompletableFuture.completedFuture(0);
        }
        
        isRunning = true;
        wasCancelled = false;
        compiledClasses = 0;
        totalClasses = 0;
        
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                LOGGER.info("Full AOT pre-compilation starting, {} mods (may take several minutes)",
                    modsToCompile.size());

                LOGGER.info("Counting classes...");
                for (Path modJar : modsToCompile) {
                    totalClasses += countClasses(modJar);
                }
                LOGGER.info("Total classes to compile: {}", totalClasses);

                com.retromod.core.RetromodTransformer transformer =
                    com.retromod.core.RetromodTransformer.getInstance();
                ExpandedByteBudget expandedInputBudget =
                        new ExpandedByteBudget(MAX_EXPANDED_BYTES);
                ExpandedByteBudget cacheOutputBudget =
                        new ExpandedByteBudget(MAX_EXPANDED_BYTES);
                
                for (Path modJar : modsToCompile) {
                    if (wasCancelled) {
                        LOGGER.info("Compilation cancelled by user");
                        break;
                    }
                    
                    currentMod = modJar.getFileName().toString();
                    LOGGER.info("Compiling: {}", currentMod);
                    
                    compileAllClassesInMod(modJar, transformer, expandedInputBudget,
                            cacheOutputBudget);
                }
                
                long elapsed = System.currentTimeMillis() - startTime;
                LOGGER.info("Full AOT compilation complete: {} classes in {}s",
                    compiledClasses, elapsed / 1000);

                return compiledClasses;

            } catch (Exception e) {
                LOGGER.error("Full AOT compilation failed", e);
                return compiledClasses;
            } finally {
                isRunning = false;
                // onComplete must fire on every exit path, else the modal progress dialog never disposes
                long elapsed = System.currentTimeMillis() - startTime;
                for (ProgressListener listener : listeners) {
                    try {
                        listener.onComplete(compiledClasses, elapsed);
                    } catch (Exception e) {
                        LOGGER.debug("Progress listener failed: {}", e.getMessage());
                    }
                }
            }
        });
    }
    
    private int countClasses(Path jarPath) {
        int count = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = ZipSecurity.safeEntryName(entry.getName());
                if (entryName.endsWith(".class") &&
                    !entryName.startsWith("META-INF/")) {
                    count++;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not count classes in: {}", jarPath.getFileName());
        }
        return count;
    }

    private static final int MAX_REFMAP_FILES = 4_096;

    private static boolean isRefmapResource(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") && lower.contains("refmap");
    }

    /** Collects one immutable repair index before the Full AOT worker threads start. */
    private com.retromod.mixin.MixinRefmapRepairIndex collectRefmapRepairs(
            JarFile jar,
            com.retromod.mixin.MixinCompatibilityTransformer mixins,
            boolean forgeRefmaps, boolean official,
            ExpandedByteBudget expandedInputBudget) throws IOException {
        var repairs = com.retromod.mixin.MixinRefmapRepairIndex.empty();
        int files = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = ZipSecurity.safeEntryName(entry.getName());
            if (entry.isDirectory() || !isRefmapResource(name)) {
                continue;
            }
            if (++files > MAX_REFMAP_FILES) {
                throw new IOException("mod JAR contains more than " + MAX_REFMAP_FILES
                        + " refmap resources");
            }
            byte[] data;
            try (InputStream input = jar.getInputStream(entry)) {
                data = ZipSecurity.safeReadAllBytes(input);
            }
            expandedInputBudget.reserve(data.length, name);
            String json = new String(data, StandardCharsets.UTF_8);
            if (forgeRefmaps) {
                com.retromod.core.MixinRefmapRemapper.RemapResult forge =
                        com.retromod.core.MixinRefmapRemapper
                                .remapForgeSelectorsWithRepairs(json, mixins);
                json = forge.json();
                repairs = repairs.merge(forge.repairs());
            }
            if (official) {
                com.retromod.core.MixinRefmapRemapper.RemapResult mapped =
                        com.retromod.core.MixinRefmapRemapper.remapWithRepairs(
                                json,
                                com.retromod.mapping.IntermediaryToMojangMapper.getInstance(),
                                mixins);
                repairs = repairs.merge(mapped.repairs());
            }
        }
        return repairs;
    }

    /** Transform every class in a mod JAR in parallel and write the results to the mod's cache dir. */
    private void compileAllClassesInMod(Path jarPath, RetromodTransformer transformer,
                                        ExpandedByteBudget expandedInputBudget,
                                        ExpandedByteBudget cacheOutputBudget) {
        // Selectors live in annotation text, which the class remap leaves alone, so a cached
        // Mixin would still point at the name the mod was built against.
        com.retromod.mixin.MixinCompatibilityTransformer mixinTransformer =
            new com.retromod.mixin.MixinCompatibilityTransformer(transformer);
        String modId;
        try {
            modId = extractModId(jarPath, expandedInputBudget);
        } catch (IOException e) {
            LOGGER.error("Could not read metadata from {}: {}", jarPath.getFileName(), e.getMessage());
            return;
        }
        if (modId == null) {
            modId = jarPath.getFileName().toString().replace(".jar", "");
        }
        modId = safeModId(modId);

        Path modCacheDir = safeModCacheDir(modId);
        Path normalizedCacheDir = cacheDir.toAbsolutePath().normalize();
        Path stagingDir;
        try {
            ZipSecurity.validateNotSymlink(cacheDir);
            ZipSecurity.validateNotSymlink(modCacheDir);
            Files.createDirectories(normalizedCacheDir);
            stagingDir = Files.createTempDirectory(normalizedCacheDir,
                    ".retromod-aot-stage-");
        } catch (IOException e) {
            LOGGER.error("Could not create staged mod cache", e);
            return;
        }
        
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            boolean forgeRefmaps = jar.getEntry("META-INF/mods.toml") != null
                    || jar.getEntry("META-INF/neoforge.mods.toml") != null;
            com.retromod.mixin.MixinRefmapRepairIndex refmapRepairs =
                    collectRefmapRepairs(jar, mixinTransformer, forgeRefmaps,
                            com.retromod.core.RetromodVersion
                                    .isUnobfuscatedTarget(targetVersion),
                            expandedInputBudget);
            Map<String, byte[]> classEntries = new LinkedHashMap<>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = ZipSecurity.safeEntryName(entry.getName());
                if (entryName.endsWith(".class") &&
                    !entryName.startsWith("META-INF/")) {
                    byte[] original;
                    try (InputStream input = new BufferedInputStream(jar.getInputStream(entry))) {
                        original = ZipSecurity.safeReadAllBytes(input);
                    }
                    expandedInputBudget.reserve(original.length, entryName);
                    if (classEntries.putIfAbsent(entryName, original) != null) {
                        throw new IOException("duplicate class entry in " + jarPath.getFileName()
                                + ": " + entryName);
                    }
                }
            }

            final java.util.function.Function<String, byte[]> classBytesProvider =
                    name -> classEntries.get(name + ".class");
            
            int batchSize = Math.max(10, classEntries.size() / threads);
            List<Future<?>> futures = new ArrayList<>();
            final Path finalStagingDir = stagingDir;
            List<Map.Entry<String, byte[]>> classEntryList = new ArrayList<>(classEntries.entrySet());
            java.util.concurrent.atomic.AtomicReference<IOException> fatalFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            
            for (int i = 0; i < classEntryList.size(); i += batchSize) {
                if (wasCancelled) break;
                
                int start = i;
                int end = Math.min(i + batchSize, classEntryList.size());
                List<Map.Entry<String, byte[]>> batch = classEntryList.subList(start, end);
                
                futures.add(executor.submit(() -> {
                    try (var hierarchyScope = transformer
                            .pushJarClassBytesProvider(classBytesProvider)) {
                    for (Map.Entry<String, byte[]> entry : batch) {
                        if (wasCancelled || fatalFailure.get() != null) return;
                        
                        String entryName = entry.getKey();
                        String className = entryName.substring(0, entryName.length() - 6)
                                .replace('/', '.');
                        
                        currentClass = className;
                        
                        try {
                            byte[] original = entry.getValue();

                            byte[] transformed = transformer.transformClass(original, className);
                            transformed = AotMixinRepair.apply(
                                    mixinTransformer,
                                    transformed != null ? transformed : original,
                                    className, refmapRepairs);

                            if (transformed != null && transformed != original) {
                                if (transformed.length > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
                                    throw new IOException("transformed class exceeds cache entry limit: "
                                            + className);
                                }
                                cacheOutputBudget.reserve(transformed.length,
                                        "full AOT cache for " + className);
                                String cacheFileName = safeClassCacheFileName(className);
                                Path cacheFile = finalStagingDir.resolve(cacheFileName);
                                ZipSecurity.validateNotSymlink(cacheFile);
                                Files.write(cacheFile, transformed);

                                synchronized (this) {
                                    compiledClasses++;
                                }
                            }

                            if (compiledClasses % 10 == 0) {
                                for (ProgressListener listener : listeners) {
                                    listener.onProgress(compiledClasses, totalClasses, currentMod, className);
                                }
                            }
                            
                        } catch (IOException e) {
                            fatalFailure.compareAndSet(null, e);
                            return;
                        } catch (Exception e) {
                            LOGGER.debug("Could not compile class: {}", className);
                        }
                    }
                    }
                }));
            }
            
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    LOGGER.debug("Batch compilation error", e);
                }
            }

            if (fatalFailure.get() != null) throw fatalFailure.get();
            if (wasCancelled) {
                LOGGER.info("Discarding incomplete full AOT cache for {}", currentMod);
                return;
            }

            Path marker = stagingDir.resolve(".complete");
            Files.writeString(marker, String.valueOf(System.currentTimeMillis()));
            if (wasCancelled) {
                LOGGER.info("Discarding cancelled full AOT cache for {}", currentMod);
                return;
            }
            replaceCompletedCache(normalizedCacheDir, stagingDir, modCacheDir);
            stagingDir = null;

            for (ProgressListener listener : listeners) {
                listener.onProgress(compiledClasses, totalClasses, currentMod, "Complete");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error processing mod: {}", jarPath.getFileName(), e);
        } finally {
            executor.shutdown();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            if (stagingDir != null) {
                try {
                    deleteCacheTree(stagingDir, normalizedCacheDir);
                } catch (IOException e) {
                    LOGGER.warn("Could not clean staged full AOT cache {}: {}",
                            stagingDir.getFileName(), e.getMessage());
                }
            }
        }
    }
    
    private String extractModId(Path jarPath, ExpandedByteBudget expandedByteBudget)
            throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry fabricEntry = jar.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                String content;
                try (InputStream is = jar.getInputStream(fabricEntry)) {
                    byte[] metadata = ZipSecurity.safeReadAllBytes(is);
                    expandedByteBudget.reserve(metadata.length, fabricEntry.getName());
                    content = new String(metadata, StandardCharsets.UTF_8);
                }
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            
            ZipEntry forgeEntry = jar.getEntry("META-INF/mods.toml");
            if (forgeEntry != null) {
                String content;
                try (InputStream is = jar.getInputStream(forgeEntry)) {
                    byte[] metadata = ZipSecurity.safeReadAllBytes(is);
                    expandedByteBudget.reserve(metadata.length, forgeEntry.getName());
                    content = new String(metadata, StandardCharsets.UTF_8);
                }
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"");
                java.util.regex.Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return null;
    }

    /**
     * Install a completed staged cache while keeping the previous completed cache recoverable until
     * the replacement directory is in place.
     */
    static void replaceCompletedCache(Path cacheRoot, Path stagedCache, Path targetCache)
            throws IOException {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Path normalizedStage = stagedCache.toAbsolutePath().normalize();
        Path normalizedTarget = targetCache.toAbsolutePath().normalize();
        requireDirectCacheChild(normalizedRoot, normalizedStage, "staged cache");
        requireDirectCacheChild(normalizedRoot, normalizedTarget, "target cache");
        ZipSecurity.validateNotSymlink(normalizedRoot);
        ZipSecurity.validateNotSymlink(normalizedStage);
        if (Files.isSymbolicLink(normalizedTarget)) {
            throw new IOException("refusing to replace symlinked full AOT cache: "
                    + normalizedTarget);
        }
        ZipSecurity.validateNotSymlink(normalizedTarget);

        Path marker = normalizedStage.resolve(".complete");
        ZipSecurity.validateNotSymlink(marker);
        if (!Files.isDirectory(normalizedStage, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("staged full AOT cache is incomplete: " + normalizedStage);
        }

        Path backupRoot = null;
        Path previousCache = null;
        boolean previousMoved = false;
        boolean installed = false;
        try {
            if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
                backupRoot = Files.createTempDirectory(normalizedRoot,
                        ".retromod-aot-backup-");
                previousCache = backupRoot.resolve("previous");
                moveCacheDirectory(normalizedTarget, previousCache);
                previousMoved = true;
            }

            try {
                moveCacheDirectory(normalizedStage, normalizedTarget);
                installed = true;
            } catch (IOException installFailure) {
                if (previousMoved) {
                    try {
                        moveCacheDirectory(previousCache, normalizedTarget);
                        previousMoved = false;
                    } catch (IOException restoreFailure) {
                        installFailure.addSuppressed(restoreFailure);
                    }
                }
                throw installFailure;
            }
        } finally {
            if (backupRoot != null && (installed || !previousMoved)) {
                try {
                    deleteCacheTree(backupRoot, normalizedRoot);
                } catch (IOException cleanupFailure) {
                    LOGGER.warn("Could not remove previous full AOT cache backup {}: {}",
                            backupRoot.getFileName(), cleanupFailure.getMessage());
                }
            }
        }
    }

    private static void moveCacheDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target);
        }
    }

    private static void requireDirectCacheChild(Path cacheRoot, Path child, String description)
            throws IOException {
        if (!cacheRoot.equals(child.getParent())) {
            throw new IOException(description + " escapes the full AOT cache: " + child);
        }
    }

    private static void deleteCacheTree(Path path, Path cacheRoot) throws IOException {
        Path normalizedRoot = cacheRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        requireDirectCacheChild(normalizedRoot, normalizedPath, "cache cleanup path");
        if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(normalizedPath)) {
            Files.delete(normalizedPath);
            return;
        }
        Files.walkFileTree(normalizedPath, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path safeModCacheDir(String modId) {
        Path normalizedCache = cacheDir.toAbsolutePath().normalize();
        Path resolved = normalizedCache.resolve(safeModId(modId)).normalize();
        if (!resolved.startsWith(normalizedCache) || !normalizedCache.equals(resolved.getParent())) {
            throw new IllegalArgumentException("mod cache path escapes the full AOT cache");
        }
        return resolved;
    }

    static String safeClassCacheFileName(String className) {
        String canonical = className == null ? "" : className.replace('\\', '/').replace('.', '/');
        String readable = canonical.replace('/', '_').replaceAll("[^A-Za-z0-9_$-]", "_");
        while (readable.startsWith(".")) readable = readable.substring(1);
        if (readable.isBlank()) readable = "unnamed-class";
        if (readable.length() > 160) readable = readable.substring(0, 160);
        return readable + "_" + shortDigest(canonical) + ".class";
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) result.append(String.format("%02x", digest[i]));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static final class ExpandedByteBudget {
        private final long limit;
        private long used;

        ExpandedByteBudget(long limit) {
            if (limit <= 0) throw new IllegalArgumentException("expanded-byte limit must be positive");
            this.limit = limit;
        }

        synchronized void reserve(long bytes, String entryName) throws IOException {
            if (bytes < 0) throw new IllegalArgumentException("expanded-byte count cannot be negative");
            if (bytes > limit - used) {
                throw new IOException("full AOT input exceeds expanded-byte limit of " + limit
                        + " bytes at " + entryName);
            }
            used += bytes;
        }

        synchronized long usedBytes() {
            return used;
        }
    }

    public void cancel() {
        wasCancelled = true;
    }

    public boolean isRunning() {
        return isRunning;
    }

    /** Percent of total classes compiled so far. */
    public int getProgress() {
        if (totalClasses == 0) return 0;
        return (int) ((compiledClasses * 100.0) / totalClasses);
    }
    
    public void addProgressListener(ProgressListener listener) {
        listeners.add(listener);
    }

    public void removeProgressListener(ProgressListener listener) {
        listeners.remove(listener);
    }

    /** Run compilation behind a Swing progress dialog, or headless if no GUI is available. */
    public void showProgressDialog(List<Path> modsToCompile) {
        if (!EnvironmentDetector.canShowGui()) {
            runFullCompilation(modsToCompile);
            return;
        }
        
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            
            JDialog dialog = new JDialog((Frame) null, "Retromod: preparing mods", true);
            dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
            
            JPanel panel = new JPanel(new BorderLayout(10, 10));
            panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

            JLabel titleLabel = new JLabel("Preparing mods for quicker launches...");
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
            panel.add(titleLabel, BorderLayout.NORTH);

            JPanel progressPanel = new JPanel(new GridLayout(4, 1, 5, 5));
            
            JProgressBar progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressPanel.add(progressBar);
            
            JLabel modLabel = new JLabel("Preparing...");
            progressPanel.add(modLabel);
            
            JLabel classLabel = new JLabel(" ");
            classLabel.setFont(classLabel.getFont().deriveFont(Font.PLAIN, 11f));
            progressPanel.add(classLabel);
            
            JLabel statsLabel = new JLabel("0 / 0 classes prepared");
            progressPanel.add(statsLabel);
            
            panel.add(progressPanel, BorderLayout.CENTER);

            JButton cancelButton = new JButton("Cancel");
            cancelButton.addActionListener(e -> {
                cancel();
                dialog.dispose();
            });
            
            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            buttonPanel.add(cancelButton);
            panel.add(buttonPanel, BorderLayout.SOUTH);
            
            dialog.add(panel);
            dialog.pack();
            dialog.setSize(450, 200);
            dialog.setLocationRelativeTo(null);

            ProgressListener listener = new ProgressListener() {
                @Override
                public void onProgress(int compiled, int total, String mod, String className) {
                    SwingUtilities.invokeLater(() -> {
                        int percent = total > 0 ? (int) ((compiled * 100.0) / total) : 0;
                        progressBar.setValue(percent);
                        progressBar.setString(percent + "%");
                        modLabel.setText("Mod: " + mod);
                        classLabel.setText("Class: " + truncate(className, 50));
                        statsLabel.setText(compiled + " / " + total + " classes prepared");
                    });
                }
                
                @Override
                public void onComplete(int totalCompiled, long timeMs) {
                    SwingUtilities.invokeLater(() -> {
                        dialog.dispose();
                        
                        JOptionPane.showMessageDialog(
                            null,
                            String.format("""
                                Retromod finished precompiling your mods.

                                Classes prepared: %d
                                Time: %.1f seconds

                                Later launches can reuse this cache.
                                """, totalCompiled, timeMs / 1000.0),
                            "Precompile finished",
                            JOptionPane.INFORMATION_MESSAGE
                        );
                    });
                }
                
                @Override
                public void onError(String mod, String className, String error) {
                    LOGGER.debug("Could not prepare {}: {}", className, error);
                }
                
                private String truncate(String s, int max) {
                    if (s.length() <= max) return s;
                    return "..." + s.substring(s.length() - max + 3);
                }
            };
            
            addProgressListener(listener);

            // whenComplete, not thenRun: a failed future must still drop the listener or it leaks
            runFullCompilation(modsToCompile).whenComplete((r, t) -> {
                removeProgressListener(listener);
            });

            dialog.setVisible(true);
        });
    }
    
    public void clearCache() {
        try {
            if (Files.exists(cacheDir)) {
                try (var walk = Files.walk(cacheDir)) {
                    walk.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {}
                        });
                }
            }
            Files.createDirectories(cacheDir);
            LOGGER.info("AOT cache cleared");
        } catch (Exception e) {
            LOGGER.error("Could not clear cache", e);
        }
    }
    
    /** Total size of the cache directory in bytes. */
    public long getCacheSize() {
        try {
            if (!Files.exists(cacheDir)) return 0;
            try (var walk = Files.walk(cacheDir)) {
                return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
