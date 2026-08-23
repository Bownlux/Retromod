/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.util.ZipSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds transformed classes in the background and handles early class loads at runtime.
 * It also records which mod owns each class for crash reports and performance tracking.
 */
public final class HybridTransformationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Hybrid");

    private static volatile HybridTransformationEngine instance;

    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final int MAX_CACHED_CLASSES = 100_000;
    private static final long MAX_EXPANDED_BYTES = ZipSecurity.DEFAULT_MAX_TOTAL_SIZE;
    private static final int MAX_BACKGROUND_WORKERS = 2;

    private final RetromodTransformer jitTransformer;
    private final MixinCompatibilityTransformer mixinTransformer;
    private final ModVersionDetector versionDetector;
    private final MemorySafetyMonitor performanceMonitor;

    private final Map<String, CachedClass> aotCache = new ConcurrentHashMap<>();
    private final Object aotCacheLock = new Object();
    private long cachedTransformBytes;
    private final AtomicBoolean cacheLimitLogged = new AtomicBoolean(false);
    private final Map<String, String> classToModMap = new ConcurrentHashMap<>();
    private final Map<String, ModTransformInfo> modsToTransform = new ConcurrentHashMap<>();

    private final ExecutorService backgroundExecutor;
    private final AtomicBoolean aotCompletedFlag = new AtomicBoolean(false);
    private final Set<String> pendingAotClasses = ConcurrentHashMap.newKeySet();

    private final AtomicInteger aotHits = new AtomicInteger(0);
    private final AtomicInteger jitFallbacks = new AtomicInteger(0);
    
    public record ModTransformInfo(
        Path jarPath,
        String modId,
        String modName,
        String sourceVersion,
        Set<String> packages
    ) {}
    
    private HybridTransformationEngine() {
        this.jitTransformer = RetromodTransformer.getInstance();
        this.mixinTransformer = new MixinCompatibilityTransformer(jitTransformer);
        this.versionDetector = new ModVersionDetector();
        this.performanceMonitor = MemorySafetyMonitor.getInstance();

        this.backgroundExecutor = Executors.newFixedThreadPool(
            Math.max(1, Math.min(MAX_BACKGROUND_WORKERS,
                Runtime.getRuntime().availableProcessors() - 1)),
            r -> {
                Thread t = new Thread(r, "Retromod-AOT-Background");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        );
    }
    
    public static synchronized HybridTransformationEngine getInstance() {
        if (instance == null) {
            instance = new HybridTransformationEngine();
        }
        return instance;
    }
    
    /** Finds old mods and starts preparing their classes in the background. */
    public void initialize(Path modsFolder, String targetVersion) {
        LOGGER.info("Preparing the background transformation cache");

        scanModsFolder(modsFolder, targetVersion);

        // Crash reports use the same ownership map as the transformer.
        initializeCrashHandler();

        if (!modsToTransform.isEmpty()) {
            startBackgroundAotCompilation(targetVersion);
        } else {
            aotCompletedFlag.set(true);
            LOGGER.info("No mods need background preparation");
        }

        LOGGER.info("Background transformation is ready with {} {} queued and {} cached {}",
                modsToTransform.size(), modsToTransform.size() == 1 ? "mod" : "mods",
                aotCache.size(), aotCache.size() == 1 ? "class" : "classes");
    }

    private void initializeCrashHandler() {
        try {
            SafeCrashHandler.getInstance();
            LOGGER.debug("Crash recovery is ready");
        } catch (Exception e) {
            LOGGER.warn("Could not initialize safe crash handler: {}", e.getMessage());
        }
    }

    /** Class-to-mod mapping, shared with SafeCrashHandler. */
    public Map<String, String> getClassToModMap() {
        return classToModMap;
    }

    /**
     * Transform a class, serving from the AOT cache when present and falling back to JIT.
     */
    public byte[] transform(String className, byte[] originalBytes, String modId) {
        if (modId != null) {
            classToModMap.put(className, modId);
        } else {
            modId = guessModFromClass(className);
        }

        var ctx = performanceMonitor.beginTransform(className, modId);
        if (ctx == null) {
            // monitor vetoed it; return original so the class still loads
            return originalBytes;
        }

        long memBefore = Runtime.getRuntime().freeMemory();
        byte[] result = null;

        try {
            result = cachedTransform(className, originalBytes);
            if (result != null) {
                aotHits.incrementAndGet();
                LOGGER.trace("AOT cache hit: {}", className);
                return result;
            }

            // AOT may still be compiling this one; wait briefly, else fall through to JIT
            if (pendingAotClasses.contains(className)) {
                try {
                    Thread.sleep(50);
                    result = cachedTransform(className, originalBytes);
                    if (result != null) {
                        aotHits.incrementAndGet();
                        return result;
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }

            jitFallbacks.incrementAndGet();
            LOGGER.trace("JIT fallback: {}", className);
            result = transformWithMixinRepairs(
                    jitTransformer, mixinTransformer, originalBytes, className);

            if (result != null) {
                cacheTransform(className, originalBytes, result);
            }

            return result != null ? result : originalBytes;

        } catch (OutOfMemoryError e) {
            LOGGER.error("OOM during transform: {}", className);
            performanceMonitor.requestGarbageCollection();
            return originalBytes;
        } finally {
            long memUsed = memBefore - Runtime.getRuntime().freeMemory();
            performanceMonitor.endTransform(ctx, result != null, Math.max(0, memUsed));
        }
    }

    private byte[] cachedTransform(String className, byte[] originalBytes) {
        synchronized (aotCacheLock) {
            CachedClass cached = aotCache.get(className);
            if (cached == null) return null;
            if (cached.matches(originalBytes)) return cached.transformedBytes().clone();

            // Different mods can own the same class name. Never substitute bytes prepared from a
            // different source class, even within the same launch.
            if (aotCache.remove(className, cached)) {
                cachedTransformBytes -= cached.transformedBytes().length;
            }
            return null;
        }
    }

    private boolean cacheTransform(String className, byte[] sourceBytes, byte[] transformedBytes) {
        if (transformedBytes.length > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
            return false;
        }
        CachedClass replacement = CachedClass.from(sourceBytes, transformedBytes);
        synchronized (aotCacheLock) {
            CachedClass previous = aotCache.get(className);
            if (previous == null && aotCache.size() >= MAX_CACHED_CLASSES) {
                if (cacheLimitLogged.compareAndSet(false, true)) {
                    LOGGER.warn("Background transformation cache reached its class limit. "
                            + "Remaining classes will use launch-time transformation.");
                }
                return false;
            }
            long previousBytes = previous == null ? 0 : previous.transformedBytes().length;
            long nextTotal = cachedTransformBytes - previousBytes + transformedBytes.length;
            if (nextTotal > MAX_EXPANDED_BYTES) {
                if (cacheLimitLogged.compareAndSet(false, true)) {
                    LOGGER.warn("Background transformation cache reached its memory limit. "
                            + "Remaining classes will use launch-time transformation.");
                }
                return false;
            }
            aotCache.put(className, replacement);
            cachedTransformBytes = nextTotal;
            return true;
        }
    }

    private void scanModsFolder(Path modsFolder, String targetVersion) {
        if (!Files.isDirectory(modsFolder, LinkOption.NOFOLLOW_LINKS)) return;

        try (var stream = Files.list(modsFolder)) {
            stream
                .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .filter(p -> !p.getFileName().toString().contains("-retromod"))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .forEach(jarPath -> {
                    try {
                        var info = versionDetector.detectVersion(jarPath);
                        if (info != null && info.needsTransformation(targetVersion)) {
                            Set<String> packages = extractPackages(jarPath);

                            ModTransformInfo modInfo = new ModTransformInfo(
                                jarPath,
                                info.modId(),
                                info.modId(), // no separate display name yet
                                info.targetMcVersion(),
                                packages
                            );

                            modsToTransform.put(info.modId(), modInfo);

                            for (String pkg : packages) {
                                jitTransformer.addTransformablePackage(pkg);
                            }

                            LOGGER.info("Queued {} for an update from Minecraft {} to {}",
                                info.modId(), info.targetMcVersion(), targetVersion);
                        }
                    } catch (Exception e) {
                        LOGGER.debug("Could not analyze mod: {}", jarPath.getFileName());
                    }
                });
        } catch (IOException e) {
            LOGGER.error("Could not scan the mods folder {}", modsFolder, e);
        }
    }

    private Set<String> extractPackages(Path jarPath) throws IOException {
        Set<String> packages = new HashSet<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String rawName : collectClassEntryNames(jar, MAX_ARCHIVE_ENTRIES)) {
                String name = canonicalArchiveEntryName(rawName);
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash > 0) {
                    packages.add(name.substring(0, lastSlash + 1));
                }
            }
        }
        return packages;
    }

    private void startBackgroundAotCompilation(String targetVersion) {
        LOGGER.info("Preparing {} {} in the background",
                modsToTransform.size(), modsToTransform.size() == 1 ? "mod" : "mods");

        SharedByteBudget expandedInputBudget = new SharedByteBudget(MAX_EXPANDED_BYTES);
        List<CompletableFuture<Void>> compilationTasks = new ArrayList<>();
        for (ModTransformInfo mod : modsToTransform.values()) {
            compilationTasks.add(CompletableFuture.runAsync(() -> {
                try {
                    compileModAot(mod, targetVersion, expandedInputBudget);
                } catch (Exception e) {
                    LOGGER.warn("Could not prepare {} in the background: {}",
                            mod.modId(), e.getMessage());
                }
            }, backgroundExecutor));
        }

        afterAllBackgroundTasks(compilationTasks, () -> {
            aotCompletedFlag.set(true);
            LOGGER.info("Background precompilation finished: {} cache hits, {} launch-time fallbacks",
                aotHits.get(), jitFallbacks.get());
        });
    }

    static CompletableFuture<Void> afterAllBackgroundTasks(
            List<CompletableFuture<Void>> tasks, Runnable completion) {
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> completion.run());
    }

    private void compileModAot(ModTransformInfo mod, String targetVersion,
                               SharedByteBudget expandedInputBudget) throws IOException {
        LOGGER.info("AOT compiling: {} ({})", mod.modId(), mod.jarPath().getFileName());

        if (!Files.isRegularFile(mod.jarPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mod input is not a regular file: " + mod.jarPath());
        }
        try (JarFile jar = new JarFile(mod.jarPath().toFile())) {
            List<String> classEntries = collectClassEntryNames(jar, MAX_ARCHIVE_ENTRIES);
            Map<String, byte[]> originalClasses = new LinkedHashMap<>();
            for (String rawEntryName : classEntries) {
                String entryName = canonicalArchiveEntryName(rawEntryName);
                JarEntry entry = jar.getJarEntry(rawEntryName);
                if (entry == null) {
                    throw new IOException("Mod archive entry disappeared: " + rawEntryName);
                }
                byte[] original;
                try (var in = jar.getInputStream(entry)) {
                    original = ZipSecurity.safeReadAllBytes(
                            in, ZipSecurity.DEFAULT_MAX_ENTRY_SIZE);
                }
                expandedInputBudget.reserve(original.length, entryName);
                String className = entryName.substring(0, entryName.length() - 6);
                originalClasses.put(className, original);
            }

            int compiled = 0;
            for (Map.Entry<String, byte[]> classEntry : originalClasses.entrySet()) {
                String className = classEntry.getKey();

                // An entry name is untrusted. One that climbs out of the cache directory would
                // have Retromod write the mod's bytes to a path of the mod's choosing.
                if (!isCacheableClassName(className)) {
                    LOGGER.warn("Skipped a class with an unusable name in {}: {}",
                            mod.jarPath().getFileName(), className);
                    continue;
                }

                pendingAotClasses.add(className);

                try {
                    byte[] original = classEntry.getValue();

                    if (cachedTransform(className, original) != null) {
                        continue;
                    }
                    byte[] transformed = transformWithMixinRepairs(
                            jitTransformer, mixinTransformer, original, className,
                            originalClasses::get);

                    if (transformed != null) {
                        cacheTransform(className, original, transformed);
                        classToModMap.put(className, mod.modId());
                        compiled++;
                    }
                } finally {
                    pendingAotClasses.remove(className);
                }

                Thread.yield();
            }

            LOGGER.info("AOT compiled {} classes for {}", compiled, mod.modId());
        }
    }

    static List<String> collectClassEntryNames(JarFile jar, int maxEntries) throws IOException {
        if (maxEntries < 0) throw new IllegalArgumentException("archive entry limit is negative");
        List<String> classEntries = new ArrayList<>();
        Set<String> normalizedNames = new HashSet<>();
        int entryCount = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (++entryCount > maxEntries) {
                throw new IOException("Mod archive contains more than " + maxEntries + " entries");
            }
            String normalized = canonicalArchiveEntryName(entry.getName());
            if (!normalizedNames.add(normalized)) {
                throw new IOException("Mod archive contains a duplicate entry: " + normalized);
            }
            if (!entry.isDirectory() && normalized.endsWith(".class")
                    && !normalized.startsWith("META-INF/")) {
                classEntries.add(entry.getName());
            }
        }
        return List.copyOf(classEntries);
    }

    /** Applies the same pre-remap and post-remap Mixin passes as archive transforms. */
    static byte[] transformWithMixinRepairs(
            RetromodTransformer transformer,
            MixinCompatibilityTransformer mixins,
            byte[] originalBytes,
            String className) {
        byte[] preRemap = mixins.transformMixinClass(originalBytes);
        byte[] transformed = transformer.transformClass(
                preRemap != null ? preRemap : originalBytes, className);
        byte[] current = transformed != null ? transformed : preRemap;
        return mixins.applyPostRemapRepairs(
                current != null ? current : originalBytes);
    }

    static byte[] transformWithMixinRepairs(
            RetromodTransformer transformer,
            MixinCompatibilityTransformer mixins,
            byte[] originalBytes,
            String className,
            java.util.function.Function<String, byte[]> jarClassBytes) {
        try (var hierarchyScope = transformer.pushJarClassBytesProvider(jarClassBytes)) {
            return transformWithMixinRepairs(
                    transformer, mixins, originalBytes, className);
        }
    }

    private static String canonicalArchiveEntryName(String entryName) throws IOException {
        return ZipSecurity.canonicalEntryName(entryName);
    }

    static final class SharedByteBudget {
        private final long limit;
        private long used;

        SharedByteBudget(long limit) {
            if (limit < 0) throw new IllegalArgumentException("byte limit is negative");
            this.limit = limit;
        }

        synchronized void reserve(long bytes, String entryName) throws IOException {
            if (bytes < 0) throw new IllegalArgumentException("byte count is negative");
            if (bytes > limit - used) {
                throw new IOException("Background transformation input exceeds " + limit
                        + " expanded bytes at " + entryName);
            }
            used += bytes;
        }

        synchronized long usedBytes() {
            return used;
        }
    }

    /**
     * Whether a jar entry's class name can be used as a cache key and a file name.
     *
     * <p>The name reaches here straight from the archive, so it is rejected unless it is a plain
     * relative path. Anything else could place the cached file outside the cache directory.
     */
    static boolean isCacheableClassName(String className) {
        if (className.isBlank() || className.startsWith("/") || className.contains("\\")
                || className.contains(":")) {
            return false;
        }
        for (String segment : className.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        }
        return true;
    }

    static byte[] hashClassSource(byte[] sourceBytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(sourceBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static boolean matchesClassSource(byte[] expectedHash, byte[] sourceBytes) {
        return MessageDigest.isEqual(expectedHash, hashClassSource(sourceBytes));
    }

    private record CachedClass(byte[] sourceHash, byte[] transformedBytes) {
        static CachedClass from(byte[] sourceBytes, byte[] transformedBytes) {
            return new CachedClass(hashClassSource(sourceBytes), transformedBytes.clone());
        }

        boolean matches(byte[] sourceBytes) {
            return matchesClassSource(sourceHash, sourceBytes);
        }
    }

    private String guessModFromClass(String className) {
        if (classToModMap.containsKey(className)) {
            return classToModMap.get(className);
        }

        for (ModTransformInfo mod : modsToTransform.values()) {
            for (String pkg : mod.packages()) {
                if (className.startsWith(pkg)) {
                    classToModMap.put(className, mod.modId());
                    return mod.modId();
                }
            }
        }

        return "unknown";
    }

    public boolean isAotComplete() {
        return aotCompletedFlag.get();
    }

    public double getAotHitRate() {
        int total = aotHits.get() + jitFallbacks.get();
        if (total == 0) return 0;
        return (double) aotHits.get() / total;
    }

    public String getStats() {
        return String.format(
            "AOT: %d hits, JIT: %d fallbacks (%.1f%% hit rate), Cache: %d classes",
            aotHits.get(), jitFallbacks.get(), getAotHitRate() * 100, aotCache.size()
        );
    }

    public void shutdown() {
        backgroundExecutor.shutdown();
    }
}
