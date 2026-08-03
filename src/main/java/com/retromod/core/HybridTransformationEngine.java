/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.aot.AotCompiler;
import com.retromod.shim.ShimRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
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

    private static final Path AOT_CACHE_DIR = Path.of("config/retromod/aot-cache");

    private final ShimRegistry shimRegistry;
    private final RetromodTransformer jitTransformer;
    private final ModVersionDetector versionDetector;
    private final MemorySafetyMonitor performanceMonitor;

    private final Map<String, byte[]> aotCache = new ConcurrentHashMap<>();
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
        this.shimRegistry = new ShimRegistry();
        this.jitTransformer = RetromodTransformer.getInstance();
        this.versionDetector = new ModVersionDetector();
        this.performanceMonitor = MemorySafetyMonitor.getInstance();

        // The preload trusts each cached class, so a different Retromod build must clear the
        // directory before anything is read.
        com.retromod.aot.AotCacheStamp.ensureCurrent(AOT_CACHE_DIR);

        this.backgroundExecutor = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() - 1),
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

        loadAotCache();
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
            if (aotCache.containsKey(className)) {
                result = aotCache.get(className);
                aotHits.incrementAndGet();
                LOGGER.trace("AOT cache hit: {}", className);
                return result;
            }

            // AOT may still be compiling this one; wait briefly, else fall through to JIT
            if (pendingAotClasses.contains(className)) {
                try {
                    Thread.sleep(50);
                    if (aotCache.containsKey(className)) {
                        result = aotCache.get(className);
                        aotHits.incrementAndGet();
                        return result;
                    }
                } catch (InterruptedException ignored) {}
            }

            jitFallbacks.incrementAndGet();
            LOGGER.trace("JIT fallback: {}", className);
            result = jitTransformer.transformClass(originalBytes, className);

            if (result != null) {
                aotCache.put(className, result);
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

    private void loadAotCache() {
        try {
            if (!Files.exists(AOT_CACHE_DIR)) return;

            try (var stream = Files.walk(AOT_CACHE_DIR)) {
                stream.filter(p -> p.toString().endsWith(".class"))
                    .forEach(p -> {
                        try {
                            String className = AOT_CACHE_DIR.relativize(p).toString()
                                .replace(".class", "")
                                .replace(p.getFileSystem().getSeparator(), "/");
                            byte[] bytes = Files.readAllBytes(p);
                            aotCache.put(className, bytes);
                        } catch (IOException e) {
                            LOGGER.debug("Could not load cached class: {}", p);
                        }
                    });
            }
            
            LOGGER.info("Loaded {} classes from AOT cache", aotCache.size());
            
        } catch (IOException e) {
            LOGGER.warn("Could not load AOT cache: {}", e.getMessage());
        }
    }

    private void scanModsFolder(Path modsFolder, String targetVersion) {
        if (!Files.exists(modsFolder)) return;

        try (var stream = Files.list(modsFolder)) {
            stream
                .filter(p -> p.toString().endsWith(".jar"))
                .filter(p -> !p.getFileName().toString().contains("-retromod"))
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

    private Set<String> extractPackages(Path jarPath) {
        Set<String> packages = new HashSet<>();

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            jar.stream()
                .map(JarEntry::getName)
                .filter(name -> name.endsWith(".class"))
                .filter(name -> !name.startsWith("META-INF/"))
                .forEach(name -> {
                    int lastSlash = name.lastIndexOf('/');
                    if (lastSlash > 0) {
                        packages.add(name.substring(0, lastSlash + 1));
                    }
                });
        } catch (IOException e) {
            LOGGER.debug("Could not read class packages from {}", jarPath);
        }

        return packages;
    }

    private void startBackgroundAotCompilation(String targetVersion) {
        LOGGER.info("Preparing {} {} in the background",
                modsToTransform.size(), modsToTransform.size() == 1 ? "mod" : "mods");

        for (ModTransformInfo mod : modsToTransform.values()) {
            backgroundExecutor.submit(() -> {
                try {
                    compileModAot(mod, targetVersion);
                } catch (Exception e) {
                    LOGGER.warn("Could not prepare {} in the background: {}",
                            mod.modId(), e.getMessage());
                }
            });
        }

        backgroundExecutor.submit(() -> {
            aotCompletedFlag.set(true);
            LOGGER.info("Background precompilation finished: {} cache hits, {} launch-time fallbacks",
                aotHits.get(), jitFallbacks.get());
        });
    }

    private void compileModAot(ModTransformInfo mod, String targetVersion) throws IOException {
        LOGGER.info("AOT compiling: {} ({})", mod.modId(), mod.jarPath().getFileName());

        try (JarFile jar = new JarFile(mod.jarPath().toFile())) {
            List<JarEntry> classEntries = jar.stream()
                .filter(e -> e.getName().endsWith(".class"))
                .filter(e -> !e.getName().startsWith("META-INF/"))
                .toList();

            int compiled = 0;
            for (JarEntry entry : classEntries) {
                String className = entry.getName().replace(".class", "");

                // An entry name is untrusted. One that climbs out of the cache directory would
                // have Retromod write the mod's bytes to a path of the mod's choosing.
                if (!isCacheableClassName(className)) {
                    LOGGER.warn("Skipped a class with an unusable name in {}: {}",
                            mod.jarPath().getFileName(), entry.getName());
                    continue;
                }

                if (aotCache.containsKey(className)) {
                    continue;
                }

                pendingAotClasses.add(className);

                try {
                    byte[] original;
                    try (var in = jar.getInputStream(entry)) {
                        original = in.readAllBytes();
                    }
                    byte[] transformed = jitTransformer.transformClass(original, className);

                    if (transformed != null) {
                        aotCache.put(className, transformed);
                        saveToCache(className, transformed);
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

    private void saveToCache(String className, byte[] bytes) {
        try {
            // Checked again at the write, so a new caller cannot reintroduce the escape.
            Path cachePath = com.retromod.util.ZipSecurity.safeResolve(
                    AOT_CACHE_DIR, className + ".class");
            Files.createDirectories(cachePath.getParent());
            Files.write(cachePath, bytes);
        } catch (IOException e) {
            LOGGER.debug("Could not cache class: {}", className);
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
