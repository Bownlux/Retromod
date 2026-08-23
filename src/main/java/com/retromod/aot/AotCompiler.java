/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.aot;

import com.retromod.core.*;
import com.retromod.embedder.*;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
import com.retromod.util.ArchivePublication;
import com.retromod.util.JarSignatureSanitizer;
import com.retromod.util.JsonSecurity;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Pre-transforms a mod JAR's classes ahead of time, embeds shims for removed APIs, caches the result,
 * and falls back to runtime JIT only for obfuscated classes that can't be analyzed statically.
 */
public class AotCompiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-aot");

    private static final Path AOT_CACHE_DIR = Path.of("config/retromod/aot-cache");

    private static final String AOT_MANIFEST_KEY = "Retromod-AOT-Version";
    private static final String AOT_CONTEXT_KEY = "Retromod-AOT-Context";

    private static final int MAX_ARCHIVE_ENTRIES = 100_000;
    private static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = ZipSecurity.DEFAULT_MAX_TOTAL_SIZE;
    private static final long MAX_NESTED_OUTPUT_BYTES = ZipSecurity.DEFAULT_MAX_ENTRY_SIZE;

    // Bump this when transform behavior changes.
    static final String AOT_VERSION = RetromodVersion.RETROMOD_VERSION;

    // Development classpaths have no jar to hash, so this can be empty.
    private static String currentSelfHash() {
        return AotCacheStamp.currentSelfHash();
    }

    private final ShimRegistry shimRegistry;
    private final RetromodTransformer transformer;
    private final ModVersionDetector versionDetector;
    private final ApiEmbedder apiEmbedder;
    private final String targetMcVersion;
    private final Path cacheDir;
    private final boolean isolateOfflineInputs;
    private volatile boolean cacheReady;

    private int classesTransformed = 0;
    private int classesSkipped = 0;
    private int classesObfuscated = 0;
    
    public AotCompiler(ShimRegistry shimRegistry, String targetMcVersion) {
        this(shimRegistry, targetMcVersion, AOT_CACHE_DIR, false);
    }

    AotCompiler(ShimRegistry shimRegistry, String targetMcVersion, Path cacheDir) {
        this(shimRegistry, targetMcVersion, cacheDir, false);
    }

    private AotCompiler(ShimRegistry shimRegistry, String targetMcVersion, Path cacheDir,
                        boolean isolateOfflineInputs) {
        this.shimRegistry = shimRegistry;
        this.transformer = RetromodTransformer.getInstance();
        this.versionDetector = new ModVersionDetector();
        this.apiEmbedder = new ApiEmbedder();
        this.targetMcVersion = targetMcVersion;
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        this.isolateOfflineInputs = isolateOfflineInputs;

        // Never reuse transforms produced by a different Retromod build.
        this.cacheReady = AotCacheStamp.ensureCurrent(cacheDir);
    }

    /** Creates a compiler whose inputs may target different loaders in one CLI process. */
    public static AotCompiler forOfflineInputs(
            ShimRegistry shimRegistry, String targetMcVersion) {
        return new AotCompiler(shimRegistry, targetMcVersion, AOT_CACHE_DIR, true);
    }

    static AotCompiler forOfflineInputs(
            ShimRegistry shimRegistry, String targetMcVersion, Path cacheDir) {
        return new AotCompiler(shimRegistry, targetMcVersion, cacheDir, true);
    }
    
    /** Returns the AOT-compiled JAR for {@code modJar} (possibly a cached copy), or the original if no transform applies. */
    public Path compileModAot(Path modJar) throws IOException {
        return compileModAot(modJar, false);
    }

    /**
     * Returns the AOT-compiled JAR, optionally honoring an explicit transform request.
     *
     * <p>The default remains conservative for automatic scans. Explicit transforms may repair an
     * unknown-source or same-Minecraft-version mod through an auxiliary API provider.
     */
    public Path compileModAot(Path modJar, boolean explicitTransform) throws IOException {
        requireCacheReady();
        Path validatedModJar = ZipSecurity.requireRegularFileNoFollow(modJar, "AOT mod input");
        LOGGER.info("AOT compiling: {}", validatedModJar.getFileName());

        ModVersionInfo modInfo = versionDetector.detectVersion(validatedModJar);
        if (modInfo == null) {
            LOGGER.warn("Could not analyze mod: {}", validatedModJar.getFileName());
            return modJar;
        }

        boolean sourceVersionUnknown = isUnknownSourceVersion(modInfo.targetMcVersion());
        List<VersionShim> apiShims = shimRegistry.findApiShimsForLoader(
                modInfo.modLoaderType(), targetMcVersion);
        boolean explicitApiTransform = explicitTransform && !apiShims.isEmpty();
        if (!modInfo.needsTransformation(targetMcVersion)
                && !(explicitTransform && sourceVersionUnknown)
                && !explicitApiTransform) {
            LOGGER.info("Mod {} is already compatible, skipping AOT", modInfo.modId());
            return modJar;
        }

        Path outputJar = resolveCachePath(validatedModJar);
        Path cachedJar = getCachedJar(validatedModJar);
        if (cachedJar != null
                && isValidCache(cachedJar, validatedModJar, explicitTransform)) {
            LOGGER.info("Using cached AOT compilation for: {}", validatedModJar.getFileName());
            return cachedJar;
        }

        backupOriginalMod(validatedModJar);

        if (isolateOfflineInputs) {
            transformer.resetOfflineRegistrations();
            new com.retromod.polyfill.PolyfillRegistry().loadAndRegister(transformer);
            if (RetromodVersion.isUnobfuscatedTarget(targetMcVersion)
                    && ("forge".equalsIgnoreCase(modInfo.modLoaderType())
                        || "neoforge".equalsIgnoreCase(modInfo.modLoaderType()))) {
                com.retromod.shim.forge.ForgeNeoForgeSynthetics.registerAll(transformer);
            }
        }

        configureLoaderMappingsFor(modInfo);

        List<VersionShim> shimChain;
        if (sourceVersionUnknown) {
            shimChain = shimRegistry.findShimsForUnknownSource(
                    modInfo.modLoaderType(), targetMcVersion);
            LOGGER.info("Source Minecraft version is unknown for {}; applying {} "
                    + "loader-compatible shim(s) through {}",
                    modInfo.modId(), shimChain.size(), targetMcVersion);
        } else {
            shimChain = shimRegistry.findShimChain(
                    modInfo.modLoaderType(), modInfo.targetMcVersion(), targetMcVersion);
        }

        // For 26.x targets the vanilla class-move table below is needed even when the chain is empty,
        // so only bail on an empty chain for non-26.x targets.
        if (shimChain.isEmpty() && apiShims.isEmpty()
                && !RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) {
            LOGGER.warn("No shim chain available for {} ({} -> {})",
                modInfo.modId(), modInfo.targetMcVersion(), targetMcVersion);
            return modJar;
        }

        List<VersionShim> shimsToApply = withApiShims(
                modInfo.modLoaderType(), targetMcVersion, shimChain);
        for (VersionShim shim : shimsToApply) {
            LOGGER.debug("Applying shim: {}", shim.getShimName());
            shim.registerRedirects(transformer);
        }

        // Layer the vanilla 26.1 class moves on top of the chain, matching the in-game boot and CLI
        // `transform` paths. Without these, AOT-prepped mods kept pre-26.x class names (EndDragonFight
        // vs EnderDragonFight) and a 1.21.x mod's mixin @Shadow/@Inject failed to apply. Class moves are
        // loader-agnostic; the intermediary->Mojang MEMBER mappings are Fabric-only, since
        // NeoForge/Forge are already Mojang-named and applying them clobbers correct fields.
        if (RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) {
            try {
                var moves = com.retromod.mapping.IntermediaryToMojangMapper
                        .getInstance().getClassMoves();
                for (var e : moves.entrySet()) {
                    transformer.registerClassRedirect(e.getKey(), e.getValue());
                }
            } catch (Exception e) {
                LOGGER.warn("Could not register vanilla class moves for AOT", e);
            }
            // ResourceLocation/Identifier ctor -> factory, matching an in-game boot (AOT == runtime).
            com.retromod.mapping.IntermediaryToMojangMapper.registerIdentifierCtorRedirects(transformer);
            // Same empty-chain gap as the CLI transform/batch paths: the version-graph BFS returns
            // an empty chain for 1.21.x sources. Register the loader-agnostic 26.1 common shim,
            // and for 26.2+ targets the 26.2 core moves, or an AOT jar misses the 26.x
            // signature/API skews and the screen/hideGui bridges a plain transform applies.
            // Idempotent when the chain already includes them.
            try {
                com.retromod.shim.common.Common_1_21_11_to_26_1_ClassMoves.register(transformer);
            } catch (Exception e) {
                LOGGER.warn("Could not register 26.1 common adaptations for AOT", e);
            }
            if (!RetromodVersion.mcVersionExceeds("26.2", targetMcVersion)) {
                try {
                    com.retromod.shim.common.Mc26_1To26_2CoreMoves.register(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register 26.2 core moves for AOT", e);
                }
            }
        }
        
        compileJar(validatedModJar, outputJar, modInfo, explicitTransform);
        
        LOGGER.info("AOT compilation complete: {} classes transformed, {} skipped, {} obfuscated (JIT fallback)",
            classesTransformed, classesSkipped, classesObfuscated);
        
        return outputJar;
    }

    private static boolean isUnknownSourceVersion(String sourceVersion) {
        return sourceVersion == null || sourceVersion.isBlank()
                || sourceVersion.contains("$")
                || !sourceVersion.matches(".*\\d+\\.\\d+.*");
    }

    private List<VersionShim> withApiShims(
            String modLoader, String targetVersion, List<VersionShim> versionShims) {
        LinkedHashSet<VersionShim> combined = new LinkedHashSet<>(versionShims);
        combined.addAll(shimRegistry.findApiShimsForLoader(modLoader, targetVersion));
        return List.copyOf(combined);
    }

    /** Selects the member namespace for this mod before an offline AOT transform. */
    void configureLoaderMappingsFor(ModVersionInfo modInfo) {
        var mappings = com.retromod.mapping.OfflineLoaderNameMappings.configure(
                transformer, modInfo.modLoaderType(), targetMcVersion,
                com.retromod.util.McReflect.isNeoForge());
        if (mappings.intermediaryMappings() > 0 || mappings.srgMappings() > 0) {
            LOGGER.info("Configured AOT member mappings: {} intermediary, {} SRG",
                    mappings.intermediaryMappings(), mappings.srgMappings());
        }
    }
    
    /** Backs up the original mod JAR to mods/retromod-backups/ before transformation. */
    private void backupOriginalMod(Path modJar) throws IOException {
        Path inputParent = modJar.toAbsolutePath().normalize().getParent();
        if (inputParent == null) {
            throw new IOException("AOT mod input has no parent directory: " + modJar);
        }

        Path backupFolder = resolveDirectChild(
                inputParent, "retromod-backups", "AOT backup directory");
        if (Files.exists(backupFolder, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularDirectory(backupFolder, "AOT backup directory");
        } else {
            Files.createDirectory(backupFolder);
            requireRegularDirectory(backupFolder, "AOT backup directory");
        }

        Path backupPath = resolveDirectChild(
                backupFolder, modJar.getFileName().toString(), "AOT backup file");
        refuseSymlinkOrNonRegularTarget(backupPath, "AOT backup file");
        ArchivePublication.copyReplacing(modJar, backupPath);
        if (!Files.isRegularFile(backupPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("AOT backup is not a regular file: " + backupPath);
        }
        LOGGER.info("Created backup: {}", backupPath);
    }
    
    /** Compiles all mods in a folder in the background, returning immediately. */
    public CompletableFuture<List<Path>> compileAllModsAsync(Path modsFolder) {
        return CompletableFuture.supplyAsync(() -> {
            List<Path> compiled = new ArrayList<>();

            try {
                File[] modFiles = modsFolder.toFile().listFiles(
                    (dir, name) -> name.endsWith(".jar") && !name.contains("-aot")
                            && !name.startsWith("retromod-")
                );

                if (modFiles == null) return compiled;

                for (File modFile : modFiles) {
                    try {
                        Path result = compileModAot(modFile.toPath());
                        compiled.add(result);
                    } catch (Exception e) {
                        LOGGER.error("Failed to AOT compile: {}", modFile.getName(), e);
                        compiled.add(modFile.toPath());
                    }
                }
            } catch (Exception e) {
                LOGGER.error("AOT compilation batch failed", e);
            }
            
            return compiled;
        });
    }
    
    /** Compiles all mods synchronously, reporting progress through {@code callback}. */
    public List<AotResult> compileAllModsSync(Path modsFolder, ProgressCallback callback) {
        List<AotResult> results = new ArrayList<>();
        
        File[] modFiles = modsFolder.toFile().listFiles(
            (dir, name) -> name.endsWith(".jar") && !name.contains("-aot")
                    && !name.startsWith("retromod-")
        );

        if (modFiles == null || modFiles.length == 0) {
            return results;
        }

        int total = modFiles.length;
        int current = 0;
        
        for (File modFile : modFiles) {
            current++;
            
            if (callback != null) {
                callback.onProgress(current, total, modFile.getName());
            }
            
            try {
                long startTime = System.currentTimeMillis();
                Path result = compileModAot(modFile.toPath());
                long duration = System.currentTimeMillis() - startTime;
                
                results.add(new AotResult(
                    modFile.toPath(),
                    result,
                    AotResult.Status.SUCCESS,
                    duration,
                    classesTransformed,
                    classesObfuscated
                ));

                classesTransformed = 0;
                classesSkipped = 0;
                classesObfuscated = 0;
                
            } catch (Exception e) {
                results.add(new AotResult(
                    modFile.toPath(),
                    modFile.toPath(),
                    AotResult.Status.FAILED,
                    0,
                    0,
                    0
                ));
                LOGGER.error("AOT compilation failed: {}", modFile.getName(), e);
            }
        }
        
        return results;
    }
    
    private void compileJar(Path inputJar, Path outputJar, ModVersionInfo modInfo) throws IOException {
        compileJar(inputJar, outputJar, modInfo, false);
    }

    private void compileJar(Path inputJar, Path outputJar, ModVersionInfo modInfo,
                            boolean explicitTransform) throws IOException {
        compileJar(inputJar, outputJar, modInfo,
                new ArchiveBudget(MAX_EXPANDED_BYTES, MAX_ARCHIVE_ENTRIES),
                explicitTransform);
    }

    void compileJar(Path inputJar, Path outputJar, ModVersionInfo modInfo,
                    ArchiveBudget outputBudget) throws IOException {
        compileJar(inputJar, outputJar, modInfo, outputBudget, false);
    }

    private synchronized void compileJar(Path inputJar, Path outputJar, ModVersionInfo modInfo,
                                         ArchiveBudget outputBudget, boolean explicitTransform)
            throws IOException {
        Objects.requireNonNull(outputBudget, "outputBudget");
        classesTransformed = 0;
        classesSkipped = 0;
        classesObfuscated = 0;
        Map<String, byte[]> transformedClasses = new LinkedHashMap<>();
        Map<String, byte[]> originalResources = new LinkedHashMap<>();
        Set<String> obfuscatedClasses = new HashSet<>();
        Set<String> inputEntryNames = new HashSet<>();
        ArchiveBudget inputBudget = new ArchiveBudget(MAX_EXPANDED_BYTES, MAX_ARCHIVE_ENTRIES);
        ArchiveBudget retainedBudget = new ArchiveBudget(MAX_EXPANDED_BYTES, MAX_ARCHIVE_ENTRIES);
        MixinCompatibilityTransformer mixinTransformer = new MixinCompatibilityTransformer(transformer);
        boolean forgeRefmaps = "forge".equalsIgnoreCase(modInfo.modLoaderType())
                || "neoforge".equalsIgnoreCase(modInfo.modLoaderType());
        ArchiveRefmapPlan refmapPlan;

        try (JarFile jar = new JarFile(inputJar.toFile())) {
            refmapPlan = prepareRefmapPlan(jar, mixinTransformer, forgeRefmaps,
                    RetromodVersion.isUnobfuscatedTarget(targetMcVersion));
            // Rebuilding a frame needs the mod's own class hierarchy, and those classes are not on
            // the transform classpath. Reading them back out of the jar keeps a branch between two
            // of the mod's own types from being typed as Object, which the JVM rejects as soon as
            // that value is passed somewhere that wants a specific type. A precompiled result is
            // cached, so a bad frame here would survive restarts.
            java.util.function.Function<String, byte[]> hierarchyProvider = name -> {
                try {
                    JarEntry e = jar.getJarEntry(name + ".class");
                    if (e == null) return null;
                    try (InputStream in = jar.getInputStream(e)) {
                        return ZipSecurity.safeReadAllBytes(in);
                    }
                } catch (IOException io) {
                    return null;
                }
            };
            try (var hierarchyScope = transformer
                    .pushJarClassBytesProvider(hierarchyProvider)) {
                Enumeration<JarEntry> entries = jar.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();

                    String entryName = ZipSecurity.safeEntryName(entry.getName());
                    String canonicalName = ZipSecurity.canonicalEntryName(entryName);
                    if (!inputEntryNames.add(canonicalName)) {
                        throw new IOException("duplicate JAR entry: " + entryName);
                    }
                    if (entry.isDirectory()) {
                        inputBudget.reserve(0, entryName);
                        continue;
                    }

                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] data = ZipSecurity.safeReadAllBytes(is);
                        inputBudget.reserve(data.length, entryName);

                        if (entryName.endsWith(".class")) {
                            String className = entryName.substring(
                                    0, entryName.length() - ".class".length());

                            byte[] preparedMixin = AotMixinRepair.applyPreRemap(
                                    mixinTransformer, data, className);
                            byte[] out = preparedMixin;
                            if (shouldTransformClass(className, modInfo)) {
                                if (isObfuscated(data)) {
                                    obfuscatedClasses.add(className);
                                    classesObfuscated++;
                                } else {
                                    out = transformClassAot(preparedMixin, className);
                                    classesTransformed++;
                                }
                            } else {
                                classesSkipped++;
                            }
                            // A class whose own bytecode was left alone can still hold a Mixin.
                            byte[] repaired = AotMixinRepair.apply(
                                    mixinTransformer, out, className, refmapPlan.repairs());
                            retainEntry(retainedBudget, repaired, entryName);
                            transformedClasses.put(entryName, repaired);
                        } else {
                            if (JarSignatureSanitizer.isSigningArtifact(entryName)) {
                                continue;
                            }
                            retainEntry(retainedBudget, data, entryName);
                            originalResources.put(entryName, data);
                        }
                    }
                }
            }
        }

        Map<String, byte[]> embeddedShims = collectEmbeddedShims(modInfo);
        RetromodTransformer.NestedArchiveBudget nestedArchiveBudget =
                RetromodTransformer.NestedArchiveBudget.defaults();

        Path absoluteOutput = outputJar.toAbsolutePath().normalize();
        Path outputParent = absoluteOutput.getParent();
        if (outputParent == null) {
            throw new IOException("AOT output has no parent directory: " + outputJar);
        }
        requireRegularDirectory(outputParent, "AOT output directory");
        refuseSymlinkOrNonRegularTarget(absoluteOutput, "AOT output file");
        Path stagedOutput = Files.createTempFile(outputParent, ".retromod-aot-", ".tmp");
        try {
            try (JarOutputStream jos = new JarOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(stagedOutput)))) {
            Set<String> outputEntryNames = new HashSet<>();

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue(AOT_MANIFEST_KEY, AOT_VERSION);
            manifest.getMainAttributes().putValue(AOT_CONTEXT_KEY,
                    cacheContext(explicitTransform));
            manifest.getMainAttributes().putValue("Retromod-Source-Version",
                    ZipSecurity.sanitizeManifestValue(
                            sourceVersionOrUnknown(modInfo.targetMcVersion())));
            manifest.getMainAttributes().putValue("Retromod-Target-Version",
                    ZipSecurity.sanitizeManifestValue(targetMcVersion));
            manifest.getMainAttributes().putValue("Retromod-Compiled-Time", String.valueOf(System.currentTimeMillis()));
            manifest.getMainAttributes().putValue("Retromod-Source-Hash", computeHash(inputJar));
            String selfHash = currentSelfHash();
            if (!selfHash.isEmpty()) {
                manifest.getMainAttributes().putValue("Retromod-Self-Hash", selfHash);
            }

            if (!obfuscatedClasses.isEmpty()) {
                manifest.getMainAttributes().putValue("Retromod-JIT-Classes",
                        ZipSecurity.sanitizeManifestValue(
                                String.join(",", obfuscatedClasses)));
            }

            ByteArrayOutputStream manifestBytes = new ByteArrayOutputStream();
            manifest.write(manifestBytes);
            writeOutputEntry(jos, outputBudget, outputEntryNames,
                    "META-INF/MANIFEST.MF", manifestBytes.toByteArray());

            // ZIP directory entries: package resources (ClassLoader.getResources) and classpath
            // scanners (Reflections, YungsApi @AutoRegister) silently find nothing without them.
            java.util.List<String> allNames = new java.util.ArrayList<>(transformedClasses.keySet());
            allNames.addAll(originalResources.keySet());
            for (String shimName : embeddedShims.keySet()) {
                allNames.add("retromod_embedded/" + shimName + ".class");
            }
            allNames.add("META-INF/MANIFEST.MF");
            allNames.add("retromod_aot.properties");
            for (String directoryName : outputDirectoryEntries(allNames)) {
                writeOutputEntry(jos, outputBudget, outputEntryNames,
                        directoryName, new byte[0]);
            }

            // safeEntryName guards against zip-slip: entry names come from the input JAR, so a
            // malicious mod could ship one that traverses out of the archive ("../../etc/foo.class").
            // Downstream tooling that extracts the output JAR would inherit it.
            for (Map.Entry<String, byte[]> entry : transformedClasses.entrySet()) {
                writeOutputEntry(jos, outputBudget, outputEntryNames,
                        entry.getKey(), entry.getValue());
            }
            
            for (Map.Entry<String, byte[]> entry : originalResources.entrySet()) {
                if (entry.getKey().equals("META-INF/MANIFEST.MF")) continue;

                byte[] data = entry.getValue();

                if (entry.getKey().endsWith(".mixins.json") || entry.getKey().endsWith("mixin.json")) {
                    try {
                        JsonSecurity.validate(data, JsonSecurity.DEFAULT_MAX_BYTES,
                                JsonSecurity.DEFAULT_MAX_DEPTH, "Mixin config JSON");
                        String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                        String transformed = mixinTransformer.transformMixinConfig(json, transformedClasses);
                        if (!transformed.equals(json)) {
                            LOGGER.info("Processed mixin config: {} (stripped broken entries)", entry.getKey());
                            data = transformed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to process mixin config {}: {}", entry.getKey(), e.getMessage());
                    }
                }

                // Refmaps are separate from annotation bytecode, so Forge redirects must also be
                // applied here. On a 26.x host, Fabric resources additionally need the official
                // namespace pass before any mod code can run.
                String resourceName = entry.getKey();
                String resourceNameLower = resourceName.toLowerCase();
                try {
                    if (isRefmapEntry(resourceName)) {
                        data = refmapPlan.resources().getOrDefault(resourceName, data);
                    } else if (RetromodVersion.isUnobfuscatedTarget(targetMcVersion)
                            && (resourceNameLower.endsWith(".accesswidener")
                                    || resourceNameLower.endsWith(".classtweaker"))) {
                        data = com.retromod.core.AccessWidenerRemapper.remapToOfficial(
                                new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                com.retromod.mapping.IntermediaryToMojangMapper.getInstance())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    } else if (resourceName.equals("META-INF/accesstransformer.cfg")) {
                        data = com.retromod.core.ForgeModTransformer.normalizeAccessTransformer(
                                new String(data, java.nio.charset.StandardCharsets.UTF_8))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not remap {} ({}). Keeping the original.",
                            resourceName, e.toString());
                }

                // 26.x data-only changes the bytecode pass cannot reach; gated inside migrate().
                if (com.retromod.resources.ModDataMigrator.isMigratableData(entry.getKey())) {
                    data = com.retromod.resources.ModDataMigrator.migrate(
                            entry.getKey(), data, targetMcVersion);
                }

                // Relax version constraints in mod metadata for 26.1+
                if (targetMcVersion != null && targetMcVersion.startsWith("26.")) {
                    if (entry.getKey().equals("fabric.mod.json")) {
                        data = relaxFabricModDependencies(data);
                        LOGGER.info("Patched Fabric metadata: {}", entry.getKey());
                    } else if (entry.getKey().equals("quilt.mod.json")) {
                        data = com.retromod.core.QuiltMetadataCompat.updateMinecraftVersion(
                                data, targetMcVersion);
                        LOGGER.info("Patched Quilt metadata: {}", entry.getKey());
                    } else if (entry.getKey().equals("META-INF/mods.toml") ||
                               entry.getKey().equals("META-INF/neoforge.mods.toml")) {
                        data = relaxNeoForgeDependencies(data);
                        LOGGER.info("Patched NeoForge/Forge metadata: {}", entry.getKey());
                    }
                }

                // Recurse the transform into bundled Jar-in-Jar libs so an AOT-prepped mod's JiJ'd
                // libs get the same treatment as the JIT path (#95). An unsafe child aborts the
                // staged outer rewrite, so it cannot be published unchanged.
                if ((entry.getKey().startsWith("META-INF/jars/")
                        || entry.getKey().startsWith("META-INF/jarjar/"))
                        && entry.getKey().endsWith(".jar")) {
                    String nestedKey = inputJar.getFileName() + "!/" + entry.getKey();
                    data = transformNestedJarAot(
                            data, 1, mixinTransformer, forgeRefmaps,
                            nestedArchiveBudget, nestedKey);
                }

                writeOutputEntry(jos, outputBudget, outputEntryNames,
                        entry.getKey(), data);
            }

            // Keys come from Retromod's own shim collection, not user input, but safeEntryName guards
            // against a future refactor letting an attacker-controlled string land here.
            for (Map.Entry<String, byte[]> entry : embeddedShims.entrySet()) {
                writeOutputEntry(jos, outputBudget, outputEntryNames,
                        "retromod_embedded/" + entry.getKey() + ".class", entry.getValue());
            }

                writeOutputEntry(jos, outputBudget, outputEntryNames,
                        "retromod_aot.properties",
                        createAotMetadata(modInfo, obfuscatedClasses));
            }

            try (JarFile ignored = new JarFile(stagedOutput.toFile())) {
                // Opening the completed archive validates its central directory before replacement.
            }
            moveReplacing(stagedOutput, absoluteOutput);
        } finally {
            Files.deleteIfExists(stagedOutput);
        }
    }

    private static List<String> outputDirectoryEntries(Collection<String> fileNames)
            throws IOException {
        TreeSet<String> directories = new TreeSet<>();
        for (String rawName : fileNames) {
            String name = ZipSecurity.safeEntryName(rawName);
            for (int slash = name.indexOf('/'); slash >= 0;
                    slash = name.indexOf('/', slash + 1)) {
                directories.add(name.substring(0, slash + 1));
            }
        }
        return List.copyOf(directories);
    }

    private static void writeOutputEntry(JarOutputStream output,
                                         ArchiveBudget budget,
                                         Set<String> outputEntryNames,
                                         String rawName,
                                         byte[] data) throws IOException {
        String entryName = ZipSecurity.safeEntryName(rawName);
        String canonicalName = ZipSecurity.canonicalEntryName(entryName);
        if (!outputEntryNames.add(canonicalName)) {
            throw new IOException("duplicate generated JAR entry: " + entryName);
        }
        retainEntry(budget, data, entryName);
        output.putNextEntry(new JarEntry(entryName));
        output.write(data);
        output.closeEntry();
    }

    private static void retainEntry(ArchiveBudget budget, byte[] data, String entryName)
            throws IOException {
        if (data.length > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
            throw new IOException("transformed JAR entry exceeds "
                    + ZipSecurity.DEFAULT_MAX_ENTRY_SIZE + " bytes: " + entryName);
        }
        budget.reserve(data.length, entryName);
    }

    private static final long MAX_REFMAP_SCAN_BYTES = 64L * 1024 * 1024;
    private static final int MAX_REFMAP_FILES = 4_096;

    private record ArchiveRefmapPlan(
            com.retromod.mixin.MixinRefmapRepairIndex repairs,
            Map<String, byte[]> resources) {
        private ArchiveRefmapPlan {
            repairs = repairs == null
                    ? com.retromod.mixin.MixinRefmapRepairIndex.empty() : repairs;
            resources = Map.copyOf(resources);
        }
    }

    private record RemappedRefmap(byte[] bytes,
            com.retromod.mixin.MixinRefmapRepairIndex repairs) {}

    private static boolean isRefmapEntry(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".json") && lower.contains("refmap");
    }

    private static ArchiveRefmapPlan prepareRefmapPlan(JarFile jar,
            MixinCompatibilityTransformer mixins, boolean forgeRefmaps,
            boolean official) throws IOException {
        var repairs = com.retromod.mixin.MixinRefmapRepairIndex.empty();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        Set<String> entryNames = new HashSet<>();
        long totalBytes = 0;
        int files = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = ZipSecurity.safeEntryName(entry.getName());
            String canonicalName = ZipSecurity.canonicalEntryName(name);
            if (!entryNames.add(canonicalName)) {
                throw new IOException("duplicate mod JAR entry: " + name);
            }
            if (entry.isDirectory() || !isRefmapEntry(name)) continue;
            if (++files > MAX_REFMAP_FILES) {
                throw new IOException("mod JAR contains more than " + MAX_REFMAP_FILES
                        + " refmap resources");
            }
            byte[] data;
            try (InputStream input = jar.getInputStream(entry)) {
                data = ZipSecurity.safeReadAllBytes(input);
            }
            totalBytes += data.length;
            if (totalBytes > MAX_REFMAP_SCAN_BYTES) {
                throw new IOException("mod JAR refmaps exceed " + MAX_REFMAP_SCAN_BYTES
                        + " expanded bytes");
            }
            RemappedRefmap remapped = remapRefmap(data, mixins, forgeRefmaps, official);
            if (resources.putIfAbsent(name, remapped.bytes()) != null) {
                throw new IOException("duplicate refmap entry: " + name);
            }
            repairs = repairs.merge(remapped.repairs());
        }
        return new ArchiveRefmapPlan(repairs, resources);
    }

    private static ArchiveRefmapPlan prepareRefmapPlan(byte[] jarData,
            MixinCompatibilityTransformer mixins, boolean forgeRefmaps,
            boolean official, RetromodTransformer.NestedArchiveBudget budget,
            String archiveKey) throws IOException {
        var repairs = com.retromod.mixin.MixinRefmapRepairIndex.empty();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        Set<String> entryNames = new HashSet<>();
        long totalBytes = 0;
        int files = 0;
        try (var input = new ZipInputStream(new ByteArrayInputStream(jarData))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = ZipSecurity.safeEntryName(entry.getName());
                String canonicalName = ZipSecurity.canonicalEntryName(name);
                if (!entryNames.add(canonicalName)) {
                    throw new IOException("duplicate nested JAR entry: " + name);
                }
                if (entry.isDirectory() || !isRefmapEntry(name)) continue;
                if (++files > MAX_REFMAP_FILES) {
                    throw new IOException("nested JAR contains more than " + MAX_REFMAP_FILES
                            + " refmap resources");
                }
                String qualifiedName = archiveKey + "!/" + name;
                long allowance = budget.beginRead(
                        ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, qualifiedName);
                byte[] data = ZipSecurity.safeReadAllBytes(input, allowance);
                budget.completeRead(allowance, data.length);
                totalBytes += data.length;
                if (totalBytes > MAX_REFMAP_SCAN_BYTES) {
                    throw new IOException("nested JAR refmaps exceed " + MAX_REFMAP_SCAN_BYTES
                            + " expanded bytes");
                }
                RemappedRefmap remapped = remapRefmap(data, mixins, forgeRefmaps, official);
                if (resources.putIfAbsent(name, remapped.bytes()) != null) {
                    throw new IOException("duplicate nested refmap entry: " + name);
                }
                repairs = repairs.merge(remapped.repairs());
            }
        }
        return new ArchiveRefmapPlan(repairs, resources);
    }

    private static RemappedRefmap remapRefmap(byte[] data,
            MixinCompatibilityTransformer mixins, boolean forgeRefmaps,
            boolean official) throws IOException {
        JsonSecurity.validate(data, JsonSecurity.DEFAULT_MAX_BYTES,
                JsonSecurity.DEFAULT_MAX_DEPTH, "Mixin refmap JSON");
        String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
        var repairs = com.retromod.mixin.MixinRefmapRepairIndex.empty();
        if (forgeRefmaps) {
            MixinRefmapRemapper.RemapResult forge =
                    MixinRefmapRemapper.remapForgeSelectorsWithRepairs(json, mixins);
            json = forge.json();
            repairs = repairs.merge(forge.repairs());
        }
        if (official) {
            MixinRefmapRemapper.RemapResult mapped =
                    MixinRefmapRemapper.remapWithRepairs(
                            json, com.retromod.mapping.IntermediaryToMojangMapper.getInstance(),
                            mixins);
            json = mapped.json();
            repairs = repairs.merge(mapped.repairs());
        }
        return new RemappedRefmap(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8), repairs);
    }

    /** Max Jar-in-Jar nesting depth the AOT path recurses through. */
    private static final int MAX_JIJ_DEPTH_AOT = 4;

    /**
     * Rewrites a bundled Jar-in-Jar library's bytecode and metadata with the outer mod's
     * transformer and recurses into its own bundled jars. Mirrors
     * {@code RetromodCli.transformNestedJar} without making AOT depend on the CLI package.
     */
    private byte[] transformNestedJarAot(byte[] jarData, int depth,
            MixinCompatibilityTransformer mixinTransformer) throws IOException {
        return transformNestedJarAot(jarData, depth, mixinTransformer, false);
    }

    private byte[] transformNestedJarAot(byte[] jarData, int depth,
            MixinCompatibilityTransformer mixinTransformer, boolean forgeRefmaps)
            throws IOException {
        return transformNestedJarAot(jarData, depth, mixinTransformer, forgeRefmaps,
                RetromodTransformer.NestedArchiveBudget.defaults());
    }

    private byte[] transformNestedJarAot(byte[] jarData, int depth,
            MixinCompatibilityTransformer mixinTransformer, boolean forgeRefmaps,
            RetromodTransformer.NestedArchiveBudget nestedBudget) throws IOException {
        return transformNestedJarAot(jarData, depth, mixinTransformer,
                forgeRefmaps, nestedBudget, "nested-depth-" + depth + ".jar");
    }

    private byte[] transformNestedJarAot(byte[] jarData, int depth,
            MixinCompatibilityTransformer mixinTransformer, boolean forgeRefmaps,
            RetromodTransformer.NestedArchiveBudget nestedBudget,
            String syntheticKey) throws IOException {
        Objects.requireNonNull(jarData, "jarData");
        Objects.requireNonNull(mixinTransformer, "mixinTransformer");
        Objects.requireNonNull(nestedBudget, "nestedBudget");
        Objects.requireNonNull(syntheticKey, "syntheticKey");

        nestedBudget.reserve(0, syntheticKey);
        if (NestedArchivePolicy.shouldPreserve(jarData, nestedBudget, syntheticKey)) {
            return jarData;
        }
        try {
            var bais = new java.io.ByteArrayInputStream(jarData);
            var boundedOutput = new BoundedArchiveOutput(
                    MAX_NESTED_OUTPUT_BYTES, jarData.length);
            boolean modified = false;
            Map<String, byte[]> classBytes =
                    RetromodTransformer.readJarClassBytes(jarData, nestedBudget);
            boolean official = RetromodVersion.isUnobfuscatedTarget(targetMcVersion);
            ArchiveRefmapPlan refmapPlan = prepareRefmapPlan(
                    jarData, mixinTransformer, forgeRefmaps, official,
                    nestedBudget, syntheticKey);
            Set<String> entryNames = new HashSet<>();
            try (var hierarchyScope = transformer.pushJarClassBytesProvider(classBytes::get);
                 var jis = new java.util.zip.ZipInputStream(bais);
                 var jos = new JarOutputStream(boundedOutput)) {
                java.util.zip.ZipEntry e;
                while ((e = jis.getNextEntry()) != null) {
                    String name = ZipSecurity.safeEntryName(e.getName());
                    String qualifiedName = syntheticKey + "!/" + name;
                    String canonicalName = ZipSecurity.canonicalEntryName(name);
                    if (!entryNames.add(canonicalName)) {
                        throw new IOException("duplicate nested JAR entry: " + qualifiedName);
                    }
                    if (e.isDirectory()) {
                        nestedBudget.reserve(0, qualifiedName);
                    }
                    jos.putNextEntry(new JarEntry(name));
                    if (!e.isDirectory()) {
                        long allowance = nestedBudget.beginRead(
                                ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, qualifiedName);
                        byte[] d = ZipSecurity.safeReadAllBytes(jis, allowance);
                        nestedBudget.completeRead(allowance, d.length);
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (name.endsWith(".class")) {
                            String cn = name.substring(0, name.length() - ".class".length());
                            byte[] preparedMixin = AotMixinRepair.applyPreRemap(
                                    mixinTransformer, d, cn);
                            if (preparedMixin != d) {
                                d = preparedMixin;
                                modified = true;
                            }
                            try {
                                byte[] t = transformer.transformClass(d, cn);
                                if (t != null && t != d) { d = t; modified = true; }
                            } catch (Exception ignored) {
                            }
                            // A bundled library ships its own Mixins, which need the same
                            // repairs as the mod's, or they cannot resolve their targets.
                            byte[] repaired = AotMixinRepair.apply(
                                    mixinTransformer, d, cn, refmapPlan.repairs());
                            if (repaired != d) { d = repaired; modified = true; }
                        } else if (name.equals("fabric.mod.json")) {
                            d = relaxFabricModDependencies(d); modified = true;
                        } else if (name.equals("quilt.mod.json")) {
                            byte[] updated = com.retromod.core.QuiltMetadataCompat
                                    .updateMinecraftVersion(d, targetMcVersion);
                            if (!java.util.Arrays.equals(updated, d)) {
                                d = updated;
                                modified = true;
                            }
                        } else if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                            d = relaxNeoForgeDependencies(d); modified = true;
                        } else if (official && (lower.endsWith(".accesswidener")
                                || lower.endsWith(".classtweaker"))) {
                            byte[] t = com.retromod.core.AccessWidenerRemapper.remapToOfficial(
                                    new String(d, java.nio.charset.StandardCharsets.UTF_8),
                                    com.retromod.mapping.IntermediaryToMojangMapper.getInstance())
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (!java.util.Arrays.equals(t, d)) { d = t; modified = true; }
                        } else if (name.equals("META-INF/accesstransformer.cfg")) {
                            byte[] t = com.retromod.core.ForgeModTransformer
                                    .normalizeAccessTransformer(new String(
                                            d, java.nio.charset.StandardCharsets.UTF_8))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            if (!java.util.Arrays.equals(t, d)) { d = t; modified = true; }
                        } else if (isRefmapEntry(name)) {
                            byte[] t = refmapPlan.resources().getOrDefault(name, d);
                            if (!java.util.Arrays.equals(t, d)) {
                                d = t;
                                modified = true;
                            }
                        } else if (com.retromod.resources.ModDataMigrator.isMigratableData(name)) {
                            byte[] t = com.retromod.resources.ModDataMigrator.migrate(
                                    name, d, targetMcVersion);
                            if (t != d) { d = t; modified = true; }
                        } else if (depth < MAX_JIJ_DEPTH_AOT
                                && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))
                                && name.endsWith(".jar")) {
                            byte[] t = transformNestedJarAot(
                                    d, depth + 1, mixinTransformer, forgeRefmaps,
                                    nestedBudget, syntheticKey + "!/" + name);
                            if (t != d) { d = t; modified = true; }
                        }
                        jos.write(d);
                    }
                    jos.closeEntry();
                }
            }
            byte[] transformed = modified ? boundedOutput.toByteArray() : jarData;
            SyntheticEmbedder.ByteEmbeddingResult embedding =
                    SyntheticEmbedder.embedIntoJarBytes(transformed, syntheticKey, transformer);
            if (!embedding.succeeded()) {
                return jarData;
            }
            boolean changed = modified || embedding.embeddedCount() > 0;
            return changed
                    ? JarSignatureSanitizer.sanitizeJarBytes(
                            embedding.jarBytes(), MAX_NESTED_OUTPUT_BYTES)
                    : embedding.jarBytes();
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("could not rewrite nested JAR: " + syntheticKey, ex);
        }
    }

    /**
     * Uses the same complete class transform as the runtime path. A smaller
     * transform remains as a per-class fallback when the main pass fails.
     */
    private byte[] transformClassAot(byte[] classBytes, String className) {
        try {
            byte[] out = transformer.transformClass(classBytes, className);
            return out != null ? out : classBytes;
        } catch (Exception e) {
            LOGGER.warn("Full transform failed for {}, using simple transform", className);
            return transformClassSimple(classBytes, className);
        }
    }

    /** Transforms a whole class in one pass, no per-method analysis. */
    private byte[] transformClassSimple(byte[] classBytes, String className) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            // Mod dependencies are often absent here, so frame computation needs
            // the class writer that tolerates an unresolved common parent.
            ClassWriter writer = new com.retromod.util.SafeClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            ClassVisitor visitor = new AotClassVisitor(Opcodes.ASM9, writer, className);
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);

            return writer.toByteArray();
        } catch (Throwable frameFailure) {
            // One unusual stack map should not discard the rest of the prepared jar.
            LOGGER.warn("The simple transform failed for {} ({}). Trying the runtime transform.",
                    className, frameFailure.toString());
            try {
                byte[] jit = transformer.transformClass(classBytes, className);
                return jit != null ? jit : classBytes;
            } catch (Throwable jitFailure) {
                LOGGER.warn("The fallback also failed for {} ({}). Keeping the original class.",
                        className, jitFailure.toString());
                return classBytes;
            }
        }
    }

    private class AotClassVisitor extends ClassVisitor {
        private final String className;
        
        public AotClassVisitor(int api, ClassVisitor classVisitor, String className) {
            super(api, classVisitor);
            this.className = className;
        }
        
        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new AotMethodVisitor(api, mv);
        }
        
        @Override
        public void visit(int version, int access, String name, String signature,
                String superName, String[] interfaces) {
            String newSuper = transformer.getClassRedirects().getOrDefault(superName, superName);
            
            String[] newInterfaces = interfaces;
            if (interfaces != null) {
                newInterfaces = new String[interfaces.length];
                for (int i = 0; i < interfaces.length; i++) {
                    newInterfaces[i] = transformer.getClassRedirects()
                        .getOrDefault(interfaces[i], interfaces[i]);
                }
            }
            
            super.visit(version, access, name, signature, newSuper, newInterfaces);
        }
    }
    
    private class AotMethodVisitor extends MethodVisitor {

        public AotMethodVisitor(int api, MethodVisitor methodVisitor) {
            super(api, methodVisitor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                String descriptor, boolean isInterface) {

            var key = new RetromodTransformer.MethodKey(owner, name, descriptor);
            var target = transformer.getMethodRedirects().get(key);

            if (target != null) {
                super.visitMethodInsn(opcode, target.owner(), target.name(),
                    target.desc(), isInterface);
            } else {
                String newOwner = transformer.getClassRedirects().getOrDefault(owner, owner);
                super.visitMethodInsn(opcode, newOwner, name, descriptor, isInterface);
            }
        }
        
        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            String newOwner = transformer.getClassRedirects().getOrDefault(owner, owner);
            super.visitFieldInsn(opcode, newOwner, name, descriptor);
        }
        
        @Override
        public void visitTypeInsn(int opcode, String type) {
            String newType = transformer.getClassRedirects().getOrDefault(type, type);
            super.visitTypeInsn(opcode, newType);
        }
        
        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type type) {
                if (type.getSort() == Type.OBJECT) {
                    String newName = transformer.getClassRedirects()
                        .getOrDefault(type.getInternalName(), type.getInternalName());
                    super.visitLdcInsn(Type.getObjectType(newName));
                    return;
                }
            }
            super.visitLdcInsn(value);
        }
        
        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            String newDesc = redirectDescriptor(descriptor);
            super.visitMultiANewArrayInsn(newDesc, numDimensions);
        }

        private String redirectDescriptor(String descriptor) {
            for (var entry : transformer.getClassRedirects().entrySet()) {
                descriptor = descriptor.replace(
                    "L" + entry.getKey() + ";",
                    "L" + entry.getValue() + ";"
                );
            }
            return descriptor;
        }
    }
    
    /** Heuristic detection of obfuscated classes (short class/method names, default package), which need JIT fallback. */
    private boolean isObfuscated(byte[] classBytes) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            String className = reader.getClassName();

            String simpleName = className.substring(className.lastIndexOf('/') + 1);
            if (simpleName.length() <= 2 && simpleName.matches("[a-z]+")) {
                return true;
            }

            if (!className.contains("/")) {
                return true;
            }

            final boolean[] hasObfuscatedMethods = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (name.length() <= 2 && !name.equals("<init>") && !name.equals("<clinit>")) {
                        hasObfuscatedMethods[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE);

            return hasObfuscatedMethods[0];

        } catch (Exception e) {
            return false;
        }
    }

    private boolean shouldTransformClass(String className, ModVersionInfo modInfo) {
        if (className.startsWith("net/minecraft/") ||
            className.startsWith("com/mojang/")) {
            return false;
        }

        if (className.startsWith("net/fabricmc/") ||
            className.startsWith("net/minecraftforge/") ||
            className.startsWith("net/neoforged/")) {
            return false;
        }

        for (String pkg : modInfo.modPackages()) {
            if (className.startsWith(pkg.replace('.', '/'))) {
                return true;
            }
        }

        return true;
    }

    private Map<String, byte[]> collectEmbeddedShims(ModVersionInfo modInfo) throws IOException {
        Map<String, byte[]> shims = new HashMap<>();

        List<VersionShim> shimChain = isUnknownSourceVersion(modInfo.targetMcVersion())
                ? shimRegistry.findShimsForUnknownSource(
                        modInfo.modLoaderType(), targetMcVersion)
                : shimRegistry.findShimChain(
                        modInfo.modLoaderType(), modInfo.targetMcVersion(), targetMcVersion);

        for (VersionShim shim : withApiShims(
                modInfo.modLoaderType(), targetMcVersion, shimChain)) {
            for (String shimClass : shim.getShimClasses()) {
                try {
                    String resourcePath = shimClass.replace('.', '/') + ".class";
                    try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                        if (is != null) {
                            shims.put(shimClass.replace('.', '/'),
                                    ZipSecurity.safeReadAllBytes(is));
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not load shim class: {}", shimClass);
                }
            }
        }

        // ASM-generated polyfills with MC-typed fields
        shims.putAll(transformer.getSyntheticClasses());

        return shims;
    }

    private byte[] createAotMetadata(ModVersionInfo modInfo,
            Set<String> obfuscatedClasses) {

        StringBuilder sb = new StringBuilder();
        sb.append("# Retromod AOT Compilation Metadata\n");
        sb.append("aot_version=").append(AOT_VERSION).append("\n");
        sb.append("retromod_self_hash=").append(currentSelfHash()).append("\n");
        sb.append("source_mc_version=")
                .append(sourceVersionOrUnknown(modInfo.targetMcVersion())).append("\n");
        sb.append("target_mc_version=").append(targetMcVersion).append("\n");
        sb.append("mod_id=").append(modInfo.modId()).append("\n");
        sb.append("mod_loader=").append(modInfo.modLoaderType()).append("\n");
        sb.append("compiled_time=").append(System.currentTimeMillis()).append("\n");
        sb.append("classes_transformed=").append(classesTransformed).append("\n");
        sb.append("classes_jit_fallback=").append(obfuscatedClasses.size()).append("\n");
        
        if (!obfuscatedClasses.isEmpty()) {
            sb.append("\n# Classes requiring JIT transformation (obfuscated)\n");
            for (String cls : obfuscatedClasses) {
                sb.append("jit_class=").append(cls).append("\n");
            }
        }
        
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String sourceVersionOrUnknown(String sourceVersion) {
        return isUnknownSourceVersion(sourceVersion) ? "unknown" : sourceVersion;
    }
    
    private Path getCachedJar(Path originalJar) throws IOException {
        Path cached = resolveCachePath(originalJar);
        if (!Files.exists(cached, LinkOption.NOFOLLOW_LINKS)) return null;
        refuseSymlinkOrNonRegularTarget(cached, "AOT cache file");
        return cached;
    }

    private Path resolveCachePath(Path originalJar) throws IOException {
        Path normalizedCache = cacheDir.toAbsolutePath().normalize();
        requireRegularDirectory(normalizedCache, "AOT cache directory");

        String inputName = originalJar.getFileName().toString();
        if (!inputName.endsWith(".jar")) {
            throw new IOException("AOT mod input must have a .jar file name: " + originalJar);
        }
        String outputName = inputName.substring(0, inputName.length() - 4) + "-aot.jar";
        Path cached = resolveDirectChild(normalizedCache, outputName, "AOT cache file");
        refuseSymlinkOrNonRegularTarget(cached, "AOT cache file");
        return cached;
    }

    private static Path resolveDirectChild(
            Path root, String childName, String description) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(childName).normalize();
        if (!normalizedRoot.equals(resolved.getParent())) {
            throw new IOException(description + " escapes its intended directory: " + resolved);
        }
        return resolved;
    }

    private static void requireRegularDirectory(Path directory, String description)
            throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular non-symlink directory: "
                    + directory);
        }
    }

    private static void refuseSymlinkOrNonRegularTarget(Path path, String description)
            throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(description + " is a symbolic link: " + path);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular file: " + path);
        }
    }

    private boolean isValidCache(Path cachedJar, Path originalJar) {
        return isValidCache(cachedJar, originalJar, false);
    }

    private boolean isValidCache(Path cachedJar, Path originalJar,
                                 boolean explicitTransform) {
        if (!Files.isRegularFile(cachedJar, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(cachedJar)) {
            return false;
        }
        try (JarFile jar = new JarFile(cachedJar.toFile())) {
            Manifest manifest = readBoundedManifest(jar);
            if (manifest == null) return false;

            String aotVersion = manifest.getMainAttributes().getValue(AOT_MANIFEST_KEY);
            if (!AOT_VERSION.equals(aotVersion)) return false;

            String cachedTarget = manifest.getMainAttributes()
                    .getValue("Retromod-Target-Version");
            if (!targetMcVersion.equals(cachedTarget)) return false;

            String cachedContext = manifest.getMainAttributes().getValue(AOT_CONTEXT_KEY);
            if (!cacheContext(explicitTransform).equals(cachedContext)) return false;

            // Any change to Retromod's own classes shifts the self-hash, making the cached transforms stale.
            // Caches written before this field existed (no header) also invalidate, which is correct.
            String selfHash = currentSelfHash();
            if (!selfHash.isEmpty()) {
                String cachedSelfHash = manifest.getMainAttributes().getValue("Retromod-Self-Hash");
                if (!selfHash.equals(cachedSelfHash)) return false;
            }

            String cachedHash = manifest.getMainAttributes().getValue("Retromod-Source-Hash");
            String currentHash = computeHash(originalJar);

            return isSha256Hash(cachedHash)
                    && isSha256Hash(currentHash)
                    && cachedHash.equals(currentHash);

        } catch (Exception e) {
            return false;
        }
    }

    private String cacheContext(boolean explicitTransform) {
        String registrationMode = isolateOfflineInputs ? "offline" : "runtime";
        String requestMode = explicitTransform ? "explicit" : "automatic";
        return registrationMode + ":" + requestMode;
    }

    private static Manifest readBoundedManifest(JarFile jar) throws IOException {
        JarEntry manifestEntry = jar.getJarEntry(JarFile.MANIFEST_NAME);
        if (manifestEntry == null) return null;
        try (InputStream input = jar.getInputStream(manifestEntry)) {
            byte[] bytes = ZipSecurity.safeReadAllBytes(input, MAX_MANIFEST_BYTES);
            return new Manifest(new ByteArrayInputStream(bytes));
        }
    }

    static String computeHash(Path file) throws IOException {
        Path source = ZipSecurity.requireRegularFileNoFollow(file, "AOT source hash input");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static boolean isSha256Hash(String hash) {
        if (hash == null || hash.length() != 64) {
            return false;
        }
        for (int i = 0; i < hash.length(); i++) {
            char character = hash.charAt(i);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static final class ArchiveBudget {
        private final long maxBytes;
        private final int maxEntries;
        private long usedBytes;
        private int usedEntries;

        ArchiveBudget(long maxBytes, int maxEntries) {
            if (maxBytes < 0 || maxEntries < 0) {
                throw new IllegalArgumentException("archive limits cannot be negative");
            }
            this.maxBytes = maxBytes;
            this.maxEntries = maxEntries;
        }

        void reserve(long bytes, String entryName) throws IOException {
            if (bytes < 0) {
                throw new IllegalArgumentException("archive entry size cannot be negative");
            }
            if (usedEntries >= maxEntries) {
                throw new ArchiveLimitException("archive exceeds " + maxEntries
                        + " entries at " + entryName);
            }
            if (bytes > maxBytes - usedBytes) {
                throw new ArchiveLimitException("archive exceeds " + maxBytes
                        + " expanded bytes at " + entryName);
            }
            usedEntries++;
            usedBytes += bytes;
        }

        long usedBytes() {
            return usedBytes;
        }

        int usedEntries() {
            return usedEntries;
        }
    }

    static final class BoundedArchiveOutput extends OutputStream {
        private static final int MAX_INITIAL_CAPACITY = 1024 * 1024;

        private final ByteArrayOutputStream output;
        private final long maxBytes;
        private long written;

        BoundedArchiveOutput(long maxBytes, int expectedBytes) {
            if (maxBytes < 0) {
                throw new IllegalArgumentException("archive output limit cannot be negative");
            }
            this.maxBytes = maxBytes;
            int initialCapacity = Math.max(32,
                    Math.min(Math.max(expectedBytes, 0), MAX_INITIAL_CAPACITY));
            this.output = new ByteArrayOutputStream(initialCapacity);
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            output.write(bytes, offset, length);
        }

        byte[] toByteArray() {
            return output.toByteArray();
        }

        long size() {
            return written;
        }

        private void reserve(long bytes) throws IOException {
            if (bytes > maxBytes - written) {
                throw new ArchiveLimitException("rewritten nested JAR exceeds "
                        + maxBytes + " bytes");
            }
            written += bytes;
        }
    }

    private static final class ArchiveLimitException extends IOException {
        ArchiveLimitException(String message) {
            super(message);
        }
    }

    public void clearCache() throws IOException {
        requireCacheReady();
        cacheReady = AotCacheStamp.clearCurrent(cacheDir);
        if (!cacheReady) {
            throw new IOException("AOT cache could not be cleared and restamped: " + cacheDir);
        }
    }

    private void requireCacheReady() throws IOException {
        if (!cacheReady) {
            throw new IOException("AOT cache could not be validated: " + cacheDir);
        }
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String currentFile);
    }

    /** Result of AOT compilation for a single mod. */
    public record AotResult(
        Path originalJar,
        Path compiledJar,
        Status status,
        long compilationTimeMs,
        int classesTransformed,
        int classesJitFallback
    ) {
        public enum Status {
            SUCCESS,
            CACHED,
            SKIPPED,
            FAILED
        }
    }

    /** Relax Fabric mod version constraints for 26.1+ compatibility. */
    private static byte[] relaxFabricModDependencies(byte[] jsonData) {
        try {
            jsonData = FabricMetadataCompat.migrateLegacyFabricApiDependency(jsonData);
            String json = new String(jsonData, java.nio.charset.StandardCharsets.UTF_8);
            json = json.replaceAll(
                "(\"minecraft\"\\s*:\\s*)(?:\"[^\"]*\"|\\[[^\\]]*\\]|\\{[^}]*\\})",
                "$1\"*\""
            );
            json = json.replaceAll(
                "(\"fabricloader\"\\s*:\\s*)\"[^\"]*\"",
                "$1\">=0.14.0\""
            );
            json = json.replaceAll(
                "(\"fabric-[a-z-]+(?:-v[0-9]+)?\"\\s*:\\s*)\"(?:>=)?[0-9][^\"]*\"",
                "$1\"*\""
            );
            return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return jsonData;
        }
    }

    /** Relax NeoForge/Forge mod version constraints for 26.1+ compatibility. */
    private static byte[] relaxNeoForgeDependencies(byte[] tomlData) {
        try {
            String toml = new String(tomlData, java.nio.charset.StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder();
            String[] blocks = toml.split("(?=\\[\\[dependencies\\.)");

            for (String block : blocks) {
                if (!block.contains("modId")) {
                    result.append(block);
                    continue;
                }

                boolean isCoreDependent = block.contains("\"minecraft\"") ||
                    block.contains("\"neoforge\"") || block.contains("\"forge\"");

                // Widen Maven version ranges: [1.21,1.21.1) -> [1.21,)
                block = block.replaceAll(
                    "(versionRange\\s*=\\s*\")\\[([^,\\]\"]+)\\]\"",
                    "$1[$2,)\""
                );
                block = block.replaceAll(
                    "(versionRange\\s*=\\s*\")\\[([^,\"]+),[^\"]*\"",
                    "$1[$2,)\""
                );
                // Handle bare version: "1.21.8" -> "[1.21.8,)"
                block = block.replaceAll(
                    "(versionRange\\s*=\\s*\")([0-9][^\"\\[\\]]*)\"",
                    "$1[$2,)\""
                );

                if (!isCoreDependent) {
                    block = block.replaceAll("(type\\s*=\\s*\")required\"", "$1optional\"");
                    block = block.replaceAll("(mandatory\\s*=\\s*)true", "$1false");
                }

                result.append(block);
            }

            return result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return tomlData;
        }
    }
}
