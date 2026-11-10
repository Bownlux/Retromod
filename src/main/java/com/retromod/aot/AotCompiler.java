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
import com.retromod.util.RetromodLog;
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

/** Transforms a mod jar ahead of time, embeds shims for removed APIs, and caches the result. */
public class AotCompiler {

    /**
     * A dotted number anywhere in a version string.
     *
     * <p>Deliberately not {@code String.matches(".*\\d+\\.\\d+.*")}. That form backtracks
     * quadratically on a long run of digits with no dot, and the version can come from a mod's own
     * metadata, so a crafted jar turned a scan of the mods folder into minutes of CPU. Anchoring
     * nothing and using find() is linear.
     */
    private static final java.util.regex.Pattern DOTTED_NUMBER =
            java.util.regex.Pattern.compile("\\d+\\.\\d+");

    private static final Logger LOGGER = LoggerFactory.getLogger("retromod-aot");

    private static final Path AOT_CACHE_DIR = Path.of("config/retromod/aot-cache");

    private static final String AOT_MANIFEST_KEY = "Retromod-AOT-Version";
    private static final String AOT_CONTEXT_KEY = "Retromod-AOT-Context";
    /** The libraries the jar being compiled on this thread references, as {@link SyntheticLibraries#fingerprint}. */
    private final ThreadLocal<String> libraryContext = ThreadLocal.withInitial(() -> "");

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
    
    /** The AOT-compiled jar for {@code modJar}, maybe cached, or the original if nothing applies. */
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
        return compileModAot(modJar, explicitTransform, SyntheticLibraries.NONE);
    }

    /**
     * As above, for a jar that builds on {@code libraries}, the jars a batch translated before it.
     * Their classes answer the addon's frame joins, so the libraries the jar references are part
     * of its cache entry: it is served only to a compile with the same library files.
     */
    public Path compileModAot(Path modJar, boolean explicitTransform, SyntheticLibraries libraries)
            throws IOException {
        // The cache is checked first, as before, so a cache that cannot be used fails before any input is read.
        requireCacheReady();
        Path input = ZipSecurity.requireRegularFileNoFollow(modJar, "AOT mod input");
        String fingerprint = libraries == null ? "" : libraries.fingerprint(input);
        libraryContext.set(fingerprint);
        try {
            return compileWithLibraries(modJar, explicitTransform,
                    fingerprint.isEmpty() ? SyntheticLibraries.NONE : libraries);
        } finally {
            libraryContext.remove();
        }
    }

    private Path compileWithLibraries(Path modJar, boolean explicitTransform, SyntheticLibraries libraries)
            throws IOException {
        requireCacheReady();
        Path validatedModJar = ZipSecurity.requireRegularFileNoFollow(modJar, "AOT mod input");
        LOGGER.debug(RetromodLog.DETAIL, "AOT compiling: {}", validatedModJar.getFileName());

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
            LOGGER.debug(RetromodLog.DETAIL, "Mod {} is already compatible, skipping AOT", modInfo.modId());
            return modJar;
        }

        Path outputJar = resolveCachePath(validatedModJar);
        Path cachedJar = getCachedJar(validatedModJar);
        if (cachedJar != null
                && isValidCache(cachedJar, validatedModJar, explicitTransform,
                        loaderApiIdentity(modInfo))) {
            LOGGER.debug(RetromodLog.DETAIL, "Using cached AOT compilation for: {}", validatedModJar.getFileName());
            return cachedJar;
        }

        // Only the automatic scan of mods/ keeps a copy. The input is never changed, and a jar a
        // user named (aot, batch --aot, the file picker) can sit outside the game directory.
        if (!explicitTransform && !isolateOfflineInputs) {
            backupOriginalMod(validatedModJar);
        }

        if (isolateOfflineInputs) {
            transformer.resetOfflineRegistrations();
        }

        // Set after the offline reset above, which clears both. A Forge mod's SRG ids mean what
        // they meant in its own version.
        transformer.setSrgSourceVersion(sourceVersionUnknown ? null : modInfo.targetMcVersion());
        transformer.setModSourceVersion(sourceVersionUnknown ? null : modInfo.targetMcVersion());
        transformer.setModSourceJar(validatedModJar);

        List<VersionShim> shimChain;
        if (sourceVersionUnknown) {
            shimChain = shimRegistry.findShimsForUnknownSource(
                    modInfo.modLoaderType(), targetMcVersion);
            LOGGER.debug(RetromodLog.DETAIL, "Source Minecraft version is unknown for {}; applying {} "
                    + "loader-compatible shim(s) through {}",
                    modInfo.modId(), shimChain.size(), targetMcVersion);
        } else {
            shimChain = shimRegistry.findTranslationShims(modInfo.modLoaderType(),
                    modInfo.targetMcVersion(), targetMcVersion, com.retromod.util.McReflect.isNeoForge());
        }

        // For 26.x targets the vanilla class-move table below is needed even when the chain is empty,
        // so only bail on an empty chain for non-26.x targets.
        if (shimChain.isEmpty() && apiShims.isEmpty()
                && !RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) {
            LOGGER.warn("No shim chain available for {} ({} -> {})",
                modInfo.modId(), modInfo.targetMcVersion(), targetMcVersion);
            return modJar;
        }

        // As in the game and the CLI batch, an addon's frames read the libraries translated before it.
        // They are set before registration, where the GeckoLib moves read which GeckoLib a library holds.
        transformer.setLinkedLibraries(libraries);
        try {
            // The CLI's registration, in the game's order, so an AOT jar matches a transformed one.
            com.retromod.shim.OfflineRegistration.register(
                    transformer, shimRegistry, modInfo, targetMcVersion, shimChain);
            compileJar(validatedModJar, outputJar, modInfo, explicitTransform);
        } finally {
            transformer.clearLinkedLibraries();
        }
        // Runtime and GUI callers embed nothing themselves, so their stand-ins are relocated here.
        // Offline inputs come from the CLI, which embeds after this call with its batch's libraries.
        String loaderApiIdentity = loaderApiIdentity(modInfo);
        // A jar left naming a stand-in is deleted, so a later cache hit cannot serve it.
        if (!isolateOfflineInputs || loaderApiIdentity != null) {
            SyntheticEmbedder.embedIntoJarOrDiscard(outputJar,
                    validatedModJar.getFileName().toString(), transformer, SyntheticLibraries.NONE);
        }
        // A shaded library's stale descriptor left at the root makes Forge refuse the whole jar.
        com.retromod.core.ModuleInfoRepair.dropInvalid(outputJar);
        if (loaderApiIdentity != null) {
            reportMissingFabricApi(outputJar, validatedModJar);
        }
        
        LOGGER.debug(RetromodLog.DETAIL, "AOT compilation complete: {} classes transformed, {} skipped, {} obfuscated (JIT fallback)",
            classesTransformed, classesSkipped, classesObfuscated);
        
        return outputJar;
    }

    /** The link check the runtime and CLI paths run, on the jar that ships and the jars it nests. */
    private void reportMissingFabricApi(Path jar, Path input) {
        try {
            com.retromod.loaderapi.fabric.v26.FabricApiLinkCheck.reportJar(Files.readAllBytes(jar),
                    input.getFileName().toString(), targetMcVersion);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Could not check Fabric API references of {}: {}", input.getFileName(),
                    e.getMessage());
        }
    }

    private static boolean isUnknownSourceVersion(String sourceVersion) {
        return sourceVersion == null || sourceVersion.isBlank()
                || sourceVersion.contains("$")
                || hasControlCharacter(sourceVersion)
                || !DOTTED_NUMBER.matcher(sourceVersion).find();
    }

    /**
     * Whether a version carries a line terminator or other control character.
     *
     * <p>Checked explicitly because the version is written into a jar manifest, where a line
     * terminator starts a new header. This used to be an accident of the previous regex, which
     * could not match across a newline and so reported such a version as unknown. That regex was
     * replaced because it backtracked quadratically, so the guard it was providing is stated here
     * instead of depending on the matcher's line handling.
     */
    private static boolean hasControlCharacter(String version) {
        for (int i = 0; i < version.length(); i++) {
            if (Character.isISOControl(version.charAt(i))) return true;
        }
        return false;
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
        LOGGER.debug(RetromodLog.DETAIL, "Created backup: {}", backupPath);
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
        // Every AOT compile passes here, whoever registered before it.
        com.retromod.loaderapi.LoaderApiRuntime.prepare(transformer,
                new com.retromod.loaderapi.LoaderApiContext(modInfo.modLoaderType(), targetMcVersion,
                        com.retromod.loaderapi.LoaderApiContext.TransformPath.AOT,
                        modInfo.targetMcVersion()));
        String loaderApiIdentity = loaderApiIdentity(modInfo);
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
        boolean promoteForgeToml;
        com.retromod.core.TomlMixinConfigs.ManifestConfigs manifestMixinConfigs;
        Set<String> inputEntryNamesForConfigs;

        try (JarFile jar = new JarFile(inputJar.toFile())) {
            refmapPlan = prepareRefmapPlan(jar, mixinTransformer, forgeRefmaps,
                    RetromodVersion.isUnobfuscatedTarget(targetMcVersion));
            // Decided on the input jar, before the class pass can rename Forge's @Mod.
            promoteForgeToml = promotesForgeToml(jar, forgeRefmaps);
            // The manifest's mixin configs, which the written neoforge.mods.toml lists where the host ignores the manifest.
            manifestMixinConfigs = com.retromod.core.TomlMixinConfigs.fromManifest(jar.getManifest());
            inputEntryNamesForConfigs = new HashSet<>();
            for (var scan = jar.entries(); scan.hasMoreElements(); ) {
                JarEntry scanned = scan.nextElement();
                if (!scanned.isDirectory()) inputEntryNamesForConfigs.add(scanned.getName());
            }
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
            // Decided for the whole jar before any class is transformed, as the runtime paths do.
            List<byte[]> classBytesForEquipment = new ArrayList<>();
            for (Enumeration<JarEntry> scan = jar.entries(); scan.hasMoreElements(); ) {
                String scanned = scan.nextElement().getName();
                if (scanned.endsWith(".class")) {
                    byte[] bytes = hierarchyProvider.apply(scanned.substring(0, scanned.length() - 6));
                    if (bytes != null) classBytesForEquipment.add(bytes);
                }
            }
            // The hierarchy serves renamed classes, as the runtime renames before its class pass.
            var typeRenames = LegacyJarTypeRenames.forJar(transformer, classBytesForEquipment);
            // The hierarchy also reads the jars it bundles, as the runtime class pass does.
            try (var hierarchyScope = transformer.pushJarClassBytesProvider(com.retromod.core.BundledJarClasses
                    .attach(typeRenames.over(hierarchyProvider), com.retromod.core.BundledJarClasses.fromJar(jar)));
                 // A block filled into a static field of one class can be registered by another.
                 var registryIdScope = transformer.pushJarRegistryIds(
                         transformer.indexJarRegistryIds(classBytesForEquipment));
                 // A final host method the jar's own access transformer or widener opens can still be overridden.
                 var openedFinals = transformer.pushJarOpenedFinals(
                         com.retromod.shim.common.OpenedFinalMethods.read(jar, targetMcVersion))) {
                // A pre-1.19 mod that listens for BiomeLoadingEvent gets a biome modifier that posts the
                // event again.
                var biomeWiring = com.retromod.shim.forge.LegacyBiomeLoadingBridge
                        .forJar(transformer, classBytesForEquipment);
                // Calls that bridges add to the @Mod constructors, decided from the whole jar as on the runtime path.
                var constructorHooks = com.retromod.shim.forge.ModConstructorHooks
                        .forJar(transformer, classBytesForEquipment);
                classBytesForEquipment = null;
                Enumeration<JarEntry> entries = jar.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();

                    String entryName = ZipSecurity.safeEntryName(entry.getName());
                    String canonicalName = ZipSecurity.canonicalEntryName(entryName);
                    // A merged fat jar repeats folder entries, which carry no content.
                    if (!ZipSecurity.firstSighting(inputEntryNames, canonicalName, entry.isDirectory(),
                            () -> "duplicate JAR entry: " + entryName)) {
                        continue;
                    }
                    if (entry.isDirectory()) {
                        inputBudget.reserve(0, entryName);
                        continue;
                    }
                    // NeoForge's own module owns Mixin's synthetic package, so a copy here stops the game at boot.
                    if (com.retromod.core.ForgeModTransformer.dropsMixinSyntheticEntry(entryName, targetMcVersion)) {
                        continue;
                    }

                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] data = ZipSecurity.safeReadAllBytes(is);
                        inputBudget.reserve(data.length, entryName);

                        if (entryName.endsWith(".class")) {
                            String className = entryName.substring(
                                    0, entryName.length() - ".class".length());

                            byte[] preparedMixin = AotMixinRepair.applyPreRemap(mixinTransformer,
                                    typeRenames.apply(data), className);
                            byte[] out = preparedMixin;
                            if (shouldTransformClass(className, modInfo)) {
                                // An obfuscated class cannot be analyzed statically, so it is
                                // left for the runtime transform.
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
                            if (constructorHooks != null) repaired = constructorHooks.apply(repaired);
                            // Last, as on the Forge runtime path, so the registration call is
                            // added to the finished constructor.
                            if (biomeWiring != null) repaired = biomeWiring.wireClass(repaired);
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
                // The biome modifier entry is data, so it goes through the migration with the rest.
                if (biomeWiring != null) {
                    for (Map.Entry<String, byte[]> biomeEntry : biomeWiring.dataEntries().entrySet()) {
                        retainEntry(retainedBudget, biomeEntry.getValue(), biomeEntry.getKey());
                        originalResources.put(biomeEntry.getKey(), biomeEntry.getValue());
                    }
                }
            }
        }

        // Resources are converted once the classes are final, since some converters read them.
        migrateResources(inputJar, modInfo, transformedClasses, originalResources);

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

            // Start from the mod's own manifest, as the transform and runtime paths do. Forge reads
            // MixinConfigs, Implementation-Version, FMLModType and Automatic-Module-Name from it.
            Manifest manifest = inputManifest(inputJar, originalResources.get(JarFile.MANIFEST_NAME));
            manifest.getMainAttributes().putValue(AOT_MANIFEST_KEY, AOT_VERSION);
            manifest.getMainAttributes().putValue(AOT_CONTEXT_KEY,
                    cacheContext(explicitTransform, loaderApiIdentity));
            if (loaderApiIdentity != null) {
                manifest.getMainAttributes().putValue(
                        com.retromod.loaderapi.LoaderApiRuntime.MANIFEST_ATTRIBUTE, loaderApiIdentity);
            }
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
            // Listed after the resource migration, so moved and added folders get entries too.
            java.util.List<String> allNames = new java.util.ArrayList<>(transformedClasses.keySet());
            allNames.addAll(originalResources.keySet());
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
            
            // Fabric applies the wideners its metadata declares under any name, so those are remapped too.
            Set<String> declaredWideners = com.retromod.core.AccessWidenerRemapper.declaredWideners(
                    originalResources.get("fabric.mod.json"));
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
                            LOGGER.debug(RetromodLog.DETAIL, "Processed mixin config: {} (stripped broken entries)", entry.getKey());
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
                try {
                    if (isRefmapEntry(resourceName)) {
                        // The class pass is over, so an entry can follow the handler a repair retyped.
                        data = retargetRefmap(refmapPlan.resources().getOrDefault(resourceName, data),
                                transformedClasses);
                    } else if (RetromodVersion.isUnobfuscatedTarget(targetMcVersion)
                            && com.retromod.core.AccessWidenerRemapper.isWidener(resourceName, declaredWideners)) {
                        data = com.retromod.core.AccessWidenerRemapper.remapToOfficial(
                                new String(data, java.nio.charset.StandardCharsets.UTF_8),
                                com.retromod.mapping.IntermediaryToMojangMapper.getInstance())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    } else if (resourceName.equals("META-INF/accesstransformer.cfg")) {
                        // The class pass is over, so the jar's own classes are named for the class moves here.
                        data = com.retromod.core.ForgeModTransformer.prepareAccessTransformer(
                                new String(data, java.nio.charset.StandardCharsets.UTF_8), targetMcVersion,
                                name -> transformedClasses.containsKey(name + ".class"))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not remap {} ({}). Keeping the original.",
                            resourceName, e.toString());
                }

                // The loader checks these ranges before any class loads, so every target needs
                // them relaxed, as the nested jars below and the CLI transform already are.
                String outputName = entry.getKey();
                if (relaxesLoaderMetadata()) {
                    if (entry.getKey().equals("fabric.mod.json")) {
                        data = relaxFabricModDependencies(data);
                        LOGGER.debug(RetromodLog.DETAIL, "Patched Fabric metadata: {}", entry.getKey());
                    } else if (entry.getKey().equals("quilt.mod.json")) {
                        data = com.retromod.core.QuiltMetadataCompat.updateMinecraftVersion(
                                data, targetMcVersion);
                        LOGGER.debug(RetromodLog.DETAIL, "Patched Quilt metadata: {}", entry.getKey());
                    } else if (promoteForgeToml && entry.getKey().equals(ForgeTomlChoice.NEOFORGE_TOML)) {
                        // A leftover from an earlier NeoForge export; the promoted mods.toml replaces it.
                        LOGGER.debug(RetromodLog.DETAIL, "Dropped the stale {} beside the Forge metadata", entry.getKey());
                        continue;
                    } else if (entry.getKey().equals("META-INF/mods.toml") ||
                               entry.getKey().equals("META-INF/neoforge.mods.toml")) {
                        data = relaxNeoForgeDependencies(data);
                        // NeoForge 1.20.2 and newer read only neoforge.mods.toml, with a neoforge dependency.
                        if (promoteForgeToml) {
                            data = ForgeModTransformer.promoteTomlContentForNeoForge(
                                    new String(data, java.nio.charset.StandardCharsets.UTF_8))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            outputName = ForgeTomlChoice.NEOFORGE_TOML;
                        }
                        if (outputName.equals(ForgeTomlChoice.NEOFORGE_TOML)) {
                            // Only configs this jar ships: FML stops on a config another mod lists too.
                            data = com.retromod.core.TomlMixinConfigs.withConfigs(data, manifestMixinConfigs,
                                    inputEntryNamesForConfigs::contains, targetMcVersion, promoteForgeToml,
                                    transformer.getModSourceVersion());
                        }
                        LOGGER.debug(RetromodLog.DETAIL, "Patched NeoForge/Forge metadata: {}", outputName);
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
                        outputName, data);
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

    /** The resource migration context for a nested jar. */
    private com.retromod.resources.MigrationContext nestedResourceContext(byte[] originalJar,
            Map<String, byte[]> writtenClasses, boolean forgeFamilyHost) {
        // A bundled jar is a mod of its own, built for whatever its own metadata says, so the
        // source comes from its original bytes.
        var source = com.retromod.resources.SourceVersionResolver.inspect(originalJar);
        return com.retromod.resources.MigrationContext.builder(targetMcVersion)
                .source(source)
                .targetLoader(com.retromod.resources.MigrationContext.Loader
                        .forNestedTransform(forgeFamilyHost, source.loader()))
                .equipmentAssets(com.retromod.shim.common.LegacyEquipmentBridge.isRegistered(transformer))
                .classes(writtenClasses.keySet(), writtenClasses::get)
                .nested(true)
                .build();
    }

    /** Runs the shared resource converters over the mod's resources in memory. */
    private void migrateResources(Path inputJar, ModVersionInfo modInfo,
            Map<String, byte[]> transformedClasses, Map<String, byte[]> resources)
            throws IOException {
        // Files the converters handle go into the tree; every other file passes through as is.
        var tree = new com.retromod.resources.MapResourceTree();
        Map<String, byte[]> streamed = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            if (com.retromod.resources.ModResourceMigrations.isMigratedResource(resource.getKey())) {
                tree.write(resource.getKey(), resource.getValue());
            } else {
                streamed.put(resource.getKey(), resource.getValue());
            }
        }
        // The runtime loaders run the same converters over an extracted mod. The source version
        // comes from the input jar, which still holds the original metadata.
        var context = com.retromod.resources.MigrationContext.builder(targetMcVersion)
                .source(com.retromod.resources.SourceVersionResolver.inspect(inputJar))
                .targetLoader(com.retromod.resources.MigrationContext.Loader
                        .forTransform(modInfo.modLoaderType()))
                .equipmentAssets(com.retromod.shim.common.LegacyEquipmentBridge.isRegistered(transformer))
                .classes(transformedClasses.keySet(), transformedClasses::get)
                .build();
        com.retromod.resources.ModResourceMigrations.standard().run(tree, context);
        // The converted files replace the originals in resources.
        resources.clear();
        resources.putAll(streamed);
        resources.putAll(tree.entries());
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

    /** A planned refmap whose stale handler targets follow the classes as written, by entry name. */
    private byte[] retargetRefmap(byte[] refmap, Map<String, byte[]> writtenClasses) {
        String json = new String(refmap, java.nio.charset.StandardCharsets.UTF_8);
        String retargeted = com.retromod.mixin.MixinRefmapTargetRepair.apply(json,
                name -> writtenClasses.get(name + ".class"), transformer.getFuzzyResolver());
        return retargeted.equals(json) ? refmap : retargeted.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

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
            // A merged fat jar repeats folder entries, which carry no content.
            if (!ZipSecurity.firstSighting(entryNames, canonicalName, entry.isDirectory(),
                    () -> "duplicate mod JAR entry: " + name)) {
                continue;
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
                // A repeated folder entry carries no content, so it is skipped.
                if (!ZipSecurity.firstSighting(entryNames, canonicalName, entry.isDirectory(),
                        () -> "duplicate nested JAR entry: " + name)) {
                    continue;
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
            Set<String> streamedNames = new HashSet<>();
            Set<String> nestedWideners = com.retromod.core.AccessWidenerRemapper.declaredWidenersInJar(jarData);
            var nestedMixinConfigs = com.retromod.core.TomlMixinConfigs.fromJarBytes(jarData);
            Set<String> nestedEntryNames = com.retromod.core.TomlMixinConfigs.entryNames(jarData);
            // The bundled jar gets its own rename decision, and its hierarchy is served renamed.
            var typeRenames = LegacyJarTypeRenames.forJar(transformer, classBytes.values());
            // A bundled mod loads its own @Mod class and data, so it gets its own biome modifier.
            var biomeWiring = com.retromod.shim.forge.LegacyBiomeLoadingBridge
                    .forJar(transformer, classBytes.values());
            // And its own constructor calls, decided from its own classes.
            var constructorHooks = com.retromod.shim.forge.ModConstructorHooks
                    .forJar(transformer, classBytes.values());
            // Plain resources are held back and migrated after the loop, with the classes as
            // written, like the outer jar's.
            var resources = new com.retromod.resources.MapResourceTree();
            Map<String, byte[]> writtenClasses = new LinkedHashMap<>();
            Map<String, byte[]> heldRefmaps = new LinkedHashMap<>();
            try (var hierarchyScope = transformer.pushJarClassBytesProvider(typeRenames.over(classBytes::get));
                 // A block filled into a static field of one class can be registered by another.
                 var registryIdScope = transformer.pushJarRegistryIds(
                         transformer.indexJarRegistryIds(classBytes.values()));
                 var openedFinals = transformer.pushJarOpenedFinals(
                         com.retromod.shim.common.OpenedFinalMethods.read(jarData, targetMcVersion));
                 var jis = new java.util.zip.ZipInputStream(bais);
                 var jos = new JarOutputStream(boundedOutput)) {
                java.util.zip.ZipEntry e;
                while ((e = jis.getNextEntry()) != null) {
                    String name = ZipSecurity.safeEntryName(e.getName());
                    String qualifiedName = syntheticKey + "!/" + name;
                    String canonicalName = ZipSecurity.canonicalEntryName(name);
                    // A repeated folder entry carries no content, so it is skipped.
                    if (!ZipSecurity.firstSighting(entryNames, canonicalName, e.isDirectory(),
                            () -> "duplicate nested JAR entry: " + qualifiedName)) {
                        continue;
                    }
                    // A bundled library is its own module, so it cannot keep Mixin's synthetic package either.
                    if (com.retromod.core.ForgeModTransformer.dropsMixinSyntheticEntry(name, targetMcVersion)) {
                        modified = true;
                        continue;
                    }
                    if (e.isDirectory()) {
                        nestedBudget.reserve(0, qualifiedName);
                        jos.putNextEntry(new JarEntry(name));
                        jos.closeEntry();
                        continue;
                    }
                    long allowance = nestedBudget.beginRead(
                            ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, qualifiedName);
                    byte[] d = ZipSecurity.safeReadAllBytes(jis, allowance);
                    nestedBudget.completeRead(allowance, d.length);
                    // Held back until every class is written, so an entry can follow its repaired handler.
                    if (isRefmapEntry(name)) {
                        heldRefmaps.put(name, d);
                        continue;
                    }
                    // Held back for the resource migration after the loop.
                    if (com.retromod.resources.ModResourceMigrations.isMigratedResource(name)
                            && !nestedWideners.contains(name)) {
                        resources.write(name, d);
                        continue;
                    }
                    // Every other entry is written now. Its name is kept so a migrated file
                    // cannot duplicate it later.
                    jos.putNextEntry(new JarEntry(name));
                    streamedNames.add(canonicalName);
                    if (name.endsWith(".class")) {
                        String cn = name.substring(0, name.length() - ".class".length());
                        // A rename alone still changes the jar.
                        byte[] renamed = typeRenames.apply(d);
                        if (renamed != d) { d = renamed; modified = true; }
                        byte[] preparedMixin = AotMixinRepair.applyPreRemap(
                                mixinTransformer, d, cn);
                        if (preparedMixin != d) {
                            d = preparedMixin;
                            modified = true;
                        }
                        try {
                            byte[] t = transformer.transformClass(d, cn);
                            if (t != null && t != d) { d = t; modified = true; }
                        } catch (Exception failure) {
                            // Written untranslated, so the warning names it and its library for a later crash.
                            LOGGER.warn("Could not translate {} in bundled library {} ({}). It is written "
                                    + "untranslated and may fail when the library loads.",
                                    cn, syntheticKey, failure.toString());
                        }
                        // A bundled library ships its own Mixins, which need the same
                        // repairs as the mod's, or they cannot resolve their targets.
                        byte[] repaired = AotMixinRepair.apply(
                                mixinTransformer, d, cn, refmapPlan.repairs());
                        if (repaired != d) { d = repaired; modified = true; }
                        if (constructorHooks != null) {
                            byte[] hooked = constructorHooks.apply(d);
                            if (hooked != d) { d = hooked; modified = true; }
                        }
                        // Wired last, so the registration call lands in the finished constructor.
                        if (biomeWiring != null) {
                            byte[] wired = biomeWiring.wireClass(d);
                            if (wired != d) { d = wired; modified = true; }
                        }
                        writtenClasses.put(name, d);
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
                        if (name.equals(ForgeTomlChoice.NEOFORGE_TOML)) {
                            d = com.retromod.core.TomlMixinConfigs.withConfigs(d, nestedMixinConfigs,
                                    nestedEntryNames::contains, targetMcVersion, false, transformer.getModSourceVersion());
                        }
                    } else if (official && com.retromod.core.AccessWidenerRemapper.isWidener(name, nestedWideners)) {
                        byte[] t = com.retromod.core.AccessWidenerRemapper.remapToOfficial(
                                new String(d, java.nio.charset.StandardCharsets.UTF_8),
                                com.retromod.mapping.IntermediaryToMojangMapper.getInstance())
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if (!java.util.Arrays.equals(t, d)) { d = t; modified = true; }
                    } else if (name.equals("META-INF/accesstransformer.cfg")) {
                        byte[] t = com.retromod.core.ForgeModTransformer
                                .prepareAccessTransformer(new String(
                                        d, java.nio.charset.StandardCharsets.UTF_8), targetMcVersion)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        if (!java.util.Arrays.equals(t, d)) { d = t; modified = true; }
                    } else if (depth < MAX_JIJ_DEPTH_AOT
                            && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))
                            && name.endsWith(".jar")) {
                        byte[] t = transformNestedJarAot(
                                d, depth + 1, mixinTransformer, forgeRefmaps,
                                nestedBudget, syntheticKey + "!/" + name);
                        if (t != d) { d = t; modified = true; }
                    }
                    jos.write(d);
                    jos.closeEntry();
                }

                for (Map.Entry<String, byte[]> refmap : heldRefmaps.entrySet()) {
                    byte[] written = retargetRefmap(
                            refmapPlan.resources().getOrDefault(refmap.getKey(), refmap.getValue()), writtenClasses);
                    if (!java.util.Arrays.equals(written, refmap.getValue())) modified = true;
                    jos.putNextEntry(new JarEntry(refmap.getKey()));
                    jos.write(written);
                    jos.closeEntry();
                    streamedNames.add(ZipSecurity.canonicalEntryName(refmap.getKey()));
                }

                // The biome modifier entry is data, so it goes through the migration with the rest.
                if (biomeWiring != null) {
                    for (Map.Entry<String, byte[]> biomeEntry : biomeWiring.dataEntries().entrySet()) {
                        resources.write(biomeEntry.getKey(), biomeEntry.getValue());
                        modified = true;
                    }
                }
                // A renamed or added file changes the jar even when no class did. The source is
                // read from the original bytes, since the loop relaxed the minecraft dependency.
                if (com.retromod.resources.ModResourceMigrations.standard().run(resources,
                        nestedResourceContext(jarData, writtenClasses, forgeRefmaps)) > 0) {
                    modified = true;
                }
                // Write the migrated files. One that matches a streamed entry is a duplicate.
                for (Map.Entry<String, byte[]> resource : resources.entries().entrySet()) {
                    if (!streamedNames.add(resource.getKey())) {
                        throw new IOException("duplicate nested JAR entry: "
                                + syntheticKey + "!/" + resource.getKey());
                    }
                    jos.putNextEntry(new JarEntry(resource.getKey()));
                    jos.write(resource.getValue());
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
    
    /** Whether a class looks obfuscated (short names, default package), which needs the JIT fallback. */
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
        return isValidCache(cachedJar, originalJar, false, null);
    }

    private boolean isValidCache(Path cachedJar, Path originalJar, boolean explicitTransform) {
        return isValidCache(cachedJar, originalJar, explicitTransform, null);
    }

    private boolean isValidCache(Path cachedJar, Path originalJar,
                                 boolean explicitTransform, String loaderApiIdentity) {
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
            if (!cacheContext(explicitTransform, loaderApiIdentity).equals(cachedContext)) return false;

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

    private String cacheContext(boolean explicitTransform, String loaderApiIdentity) {
        String registrationMode = isolateOfflineInputs ? "offline" : "runtime";
        String requestMode = explicitTransform ? "explicit" : "automatic";
        // A jar built with the loader API runtime is a different translation from one without.
        String loaderApi = loaderApiIdentity == null ? "" : "|loaderApi=" + loaderApiIdentity;
        // A NeoForge target renames a Forge mod's toml, so its jar is not the Forge one. A Forge
        // target keeps classes that a compile with no target loader replaces.
        String loader = com.retromod.util.McReflect.isNeoForge() ? "|neoforge"
                : com.retromod.util.McReflect.isForgeTarget() ? "|forge" : "";
        // The library files a compile read for the jar's frames; empty for a jar that uses none.
        String linked = libraryContext.get().isEmpty() ? "" : "|libraries=" + libraryContext.get();
        return registrationMode + ":" + requestMode + loaderApi + loader + linked;
    }

    /** Whether the outer jar's loader metadata is relaxed, which every known target needs. */
    private boolean relaxesLoaderMetadata() {
        // QuiltMetadataCompat throws on a null target, which would abort the whole compile.
        return targetMcVersion != null;
    }

    /** Whether this compile writes a Forge mod's mods.toml as neoforge.mods.toml. */
    private boolean promotesForgeToml(JarFile jar, boolean forgeFamily) throws IOException {
        // NeoForge 1.20.2 and newer skip a jar with only mods.toml, as the CLI and runtime know.
        return forgeFamily && relaxesLoaderMetadata()
                && com.retromod.util.McReflect.isNeoForge()
                && !RetromodVersion.mcVersionExceeds("1.20.2", targetMcVersion)
                && ForgeTomlChoice.FORGE_TOML.equals(ForgeTomlChoice.metadataEntry(jar));
    }

    /** The loader API runtime identity for {@code modInfo} on this target, or null while it is off. */
    private String loaderApiIdentity(ModVersionInfo modInfo) {
        return com.retromod.loaderapi.LoaderApiRuntime.translationIdentity(
                modInfo.modLoaderType(), targetMcVersion);
    }

    /** The mod's manifest without signing digests or older Retromod keys, or a new one. */
    private static Manifest inputManifest(Path inputJar, byte[] bytes) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (bytes != null) {
            try {
                manifest = new Manifest(new ByteArrayInputStream(
                        JarSignatureSanitizer.sanitizeManifest(bytes)));
            } catch (IOException unreadable) {
                // A manifest too large or broken to parse is replaced, as before.
                LOGGER.warn("Could not read the manifest of {} ({}). The AOT jar gets a new one.",
                        inputJar.getFileName(), unreadable.getMessage());
            }
        }
        // Every Retromod key in an AOT jar must describe this compile. The cache check and
        // FabricTranslationIdentity read them.
        manifest.getMainAttributes().keySet().removeIf(name -> name.toString()
                .regionMatches(true, 0, "Retromod-", 0, "Retromod-".length()));
        return manifest;
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

    /** Relax Fabric mod version constraints so the target loader accepts the jar. */
    private static byte[] relaxFabricModDependencies(byte[] jsonData) {
        try {
            jsonData = FabricMetadataCompat.migrateLegacyFabricApiDependency(jsonData);
            // A Java pin such as "21" refuses the newer Java the target needs.
            jsonData = FabricMetadataCompat.openJavaDependency(jsonData);
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

    /** The toml with the shared offline relaxation, so the target loader accepts the jar. */
    private static byte[] relaxNeoForgeDependencies(byte[] tomlData) {
        try {
            return ForgeModTransformer.relaxDependencies(
                    new String(tomlData, java.nio.charset.StandardCharsets.UTF_8))
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return tomlData;
        }
    }
}
