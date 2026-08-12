/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.regex.*;

/**
 * Transforms Fabric mods to work on newer Minecraft versions.
 *
 * <p>We rewrite the mod's own fabric.mod.json to declare the target version
 * (fabric_loader_dependencies.json has timing issues), same as the
 * Forge/NeoForge path. Flow: extract JAR, transform bytecode, patch
 * fabric.mod.json, repackage with a -retromod suffix.
 */
public class FabricModTransformer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-FabricTransform");

    /** Per-entry extraction cap (zip-bomb guard). */
    private static final long MAX_ENTRY_SIZE = 50 * 1024 * 1024;
    /** Total extraction cap (zip-bomb guard). */
    private static final long MAX_TOTAL_SIZE = 500 * 1024 * 1024;

    /**
     * Fabric mod IDs for APIs Retromod ships compatibility shims for. A mod with a
     * restrictive range on one of these (e.g. "cloth-config2": ">=6.0.0 <7.0.0")
     * would be blocked by Fabric Loader against the installed version, so we relax
     * the constraint since the bytecode is already shimmed.
     */
    private static final Set<String> SHIMMED_API_MOD_IDS = Set.of(
        // Config libraries
        "cloth-config", "cloth-config2",
        "yacl", "yet-another-config-lib", "yet_another_config_lib",
        "forge-config-api-port", "forge_config_api_port",

        // Menu / UI libraries
        "modmenu", "mod-menu",
        "libgui", "lib-gui",
        "owo-lib", "owo_lib",

        // Recipe viewers
        "roughlyenoughitems", "rei",
        "emi",
        "jei", "just-enough-items",

        // Equipment / trinkets
        "trinkets",
        "curios", "curiosapi",

        // Component / data libraries
        "cardinal-components", "cardinal-components-api",
        "cardinal-components-base", "cardinal-components-entity",
        "cardinal-components-block", "cardinal-components-world",

        // Rendering / performance
        "sodium", "iris",
        "fabric-rendering-v1", "fabric-rendering-data-attachment-v1",

        // Animation / model libraries
        "geckolib", "geckolib3", "geckolib4",

        // Cross-platform
        "architectury", "architectury-api",

        // Guide / documentation
        "patchouli",

        // Tech mod APIs
        "ae2", "appliedenergistics2",
        "botania",
        "create",
        "mekanism",
        "thermal", "thermal_foundation", "thermal_expansion",

        // Tooltips / overlays
        "jade", "waila", "wthit",

        // Compatibility / utility
        "fabric-shield-lib", "fabricshieldlib",
        "lba", "libblockattributes",
        "mixinextras", "mixin-extras",
        "autoreglib", "auto-reg-lib"
    );

    private final String targetMcVersion;
    private final RetromodTransformer bytecodeTransformer;

    public FabricModTransformer(String targetMcVersion) {
        this.targetMcVersion = targetMcVersion;
        this.bytecodeTransformer = RetromodTransformer.getInstance();
    }

    /**
     * Transform a Fabric mod JAR.
     *
     * @param sourceJar the original mod JAR
     * @param outputDir where to write the transformed JAR
     * @return the transformed JAR, or null if failed/skipped
     */
    public Path transformMod(Path sourceJar, Path outputDir) throws IOException {
        String originalName = sourceJar.getFileName().toString();
        String baseName = originalName.replace(".jar", "");
        String outputName = baseName + "-retromod.jar";
        Path outputJar = outputDir.resolve(outputName);

        LOGGER.info("Checking Fabric mod: {}", originalName);

        // An author opt-out means byte-for-byte pass-through.
        if (com.retromod.util.OptOutCheck.isOptedOut(sourceJar)) {
            com.retromod.util.OptOutCheck.logSkipped(sourceJar);
            Path passthrough = outputDir.resolve(originalName);
            Files.copy(sourceJar, passthrough, StandardCopyOption.REPLACE_EXISTING);
            return passthrough;
        }

        String modMcVersion = extractMinecraftVersion(sourceJar);
        if (isNativeVersionMod(modMcVersion)) {
            LOGGER.info("{} already targets Minecraft {}; copying it unchanged",
                originalName, targetMcVersion);

            Path directCopy = outputDir.resolve(originalName);
            Files.copy(sourceJar, directCopy, StandardCopyOption.REPLACE_EXISTING);
            return directCopy;
        }

        boolean hasMixins = checkForMixins(sourceJar);
        if (hasMixins) {
            LOGGER.info("Mixin transforms are enabled for {}", originalName);
        }

        LOGGER.info("Updating Fabric mod {} as {}", originalName, outputName);

        ModEnvironmentDetector.logModEnvironment(sourceJar);

        try {
            if (com.retromod.compat.OptiFineCompat.isOptiFine(sourceJar)) {
                com.retromod.compat.OptiFineCompat.handleOptiFineDetected(
                    sourceJar,
                    com.retromod.core.EnvironmentDetector.isDedicatedServer()
                );
            }
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("cancelled")) {
                throw new IOException("User cancelled OptiFine installation");
            }
        } catch (Exception e) {
            LOGGER.debug("Could not check for OptiFine: {}", e.getMessage());
        }

        Path tempDir = Files.createTempDirectory("retromod-fabric-");

        // For error reporting
        String modId = extractModId(sourceJar);
        String sourceVersion = extractMinecraftVersion(sourceJar);

        try {
            extractJar(sourceJar, tempDir);

            com.retromod.mixin.MixinRefmapRepairIndex refmapRepairs =
                    collectRefmapRepairs(tempDir);
            int classesTransformed = transformClasses(tempDir, refmapRepairs);
            LOGGER.info("Transformed {} class files", classesTransformed);

            // Wrap entrypoints in try-catch so one mod's init failure doesn't kill the game.
            wrapEntrypoints(tempDir);

            // 26.1+ uses the official namespace; remap class_/field_/method_ in mixin
            // configs, refmaps, and access wideners.
            remapIntermediaryNames(tempDir);

            // Old mods bundle stale Fabric API modules that clash with the installed one.
            stripBundledFabricApiJars(tempDir);

            updateFabricModJson(tempDir);

            // Data-only 1.21.x -> 26.x format changes the bytecode pass can't reach
            // (chain -> iron_chain, dyed_color object -> int, advancement icon item -> id,
            // custom_model_data int -> object, entity_type tag potion split). Gated to
            // 26.x inside ModDataMigrator.
            int dataMigrated = com.retromod.resources.ModDataMigrator.migrateTree(tempDir, targetMcVersion);
            if (dataMigrated > 0) {
                LOGGER.info("Migrated 26.x data formats in {} data file(s)", dataMigrated);
            }

            repackageJar(tempDir, outputJar, sourceJar);

            LOGGER.info("Created transformed mod: {}", outputJar.getFileName());

            if (!ModHealthChecker.validateTransformation(sourceJar, outputJar)) {
                LOGGER.warn("Transformation validation failed for {}, but continuing anyway", originalName);
            }

            String modName = extractModName(sourceJar);
            ModHealthChecker.registerTransformedMod(
                modId != null ? modId : baseName,
                modName != null ? modName : originalName,
                outputJar,
                sourceJar,
                outputDir.getParent() // game dir
            );

            // Debug scan: log unresolved classes/methods/fields/mixin targets.
            if (isDebugEnabled()) {
                debugScanTransformedMod(outputJar, modName != null ? modName : originalName);
            }

            if (TransformVerifier.isEnabled()) {
                TransformVerifier.verifyAndReport(outputJar,
                        modName != null ? modName : originalName, targetMcVersion);
            }

            if (ModEnvironmentDetector.isServerOnly(sourceJar)) {
                LOGGER.info("{} is server-only. Joining clients do not need Retromod for this mod.",
                    originalName);
            }

            return outputJar;

        } catch (Exception e) {
            LOGGER.error("Failed to transform mod: {}", originalName);

            TransformationErrorHandler.handleError(
                sourceJar, e, modId, "fabric", sourceVersion
            );

            if (EnvironmentDetector.isDedicatedServer()) {
                TransformationErrorHandler.logBugReportToConsole(
                    new TransformationErrorHandler.FailedMod(
                        originalName, modId, "fabric", sourceVersion,
                        e.getClass().getSimpleName(),
                        e.getMessage() != null ? e.getMessage() : "Unknown error",
                        ""
                    )
                );
            }

            throw new IOException("Transformation failed for " + originalName + ": " + e.getMessage(), e);

        } finally {
            deleteRecursively(tempDir);
        }
    }

    /** Read the mod ID from fabric.mod.json. */
    private String extractModId(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry != null) {
                String content = new String(jar.getInputStream(entry).readAllBytes());
                Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract mod ID");
        }
        return null;
    }

    /** Read the Minecraft version requirement from fabric.mod.json. */
    private String extractMinecraftVersion(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry != null) {
                String content = new String(jar.getInputStream(entry).readAllBytes());
                Pattern pattern = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract MC version");
        }
        return null;
    }

    /** Read the mod display name from fabric.mod.json. */
    private String extractModName(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry != null) {
                String content = new String(jar.getInputStream(entry).readAllBytes());
                Pattern pattern = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
                Matcher matcher = pattern.matcher(content);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not extract mod name");
        }
        return null;
    }

    /**
     * Whether a mod already targets the native version. Strict: only an exact
     * target match, a range starting at the target (">=1.21.11"), or a matching
     * major.minor wildcard ("1.21.x") passes through untouched. When unsure we
     * transform, since transforming a native mod still works but skipping an old
     * one crashes.
     */
    private boolean isNativeVersionMod(String modMcVersion) {
        if (modMcVersion == null) return false;

        String cleanVersion = modMcVersion
            .replace(">=", "")
            .replace("<=", "")
            .replace(">", "")
            .replace("<", "")
            .replace("~", "")
            .replace("^", "")
            .trim();

        // Exact match
        if (cleanVersion.equals(targetMcVersion)) {
            LOGGER.debug("Native: exact match {}", modMcVersion);
            return true;
        }

        // Range starting at the target (">=1.21.11" when target is 1.21.11).
        // ">=1.20.4" does not count: it only means "1.20.4+ of that major line".
        if (modMcVersion.startsWith(">=")) {
            String minVersion = modMcVersion.substring(2).trim();
            if (minVersion.equals(targetMcVersion)) {
                LOGGER.debug("Native: range starts at target {}", modMcVersion);
                return true;
            }
        }

        // Wildcard matching the target's major.minor ("1.21.x" for 1.21.11).
        if (cleanVersion.endsWith(".x") || cleanVersion.endsWith(".*")) {
            String base = cleanVersion.substring(0, cleanVersion.length() - 2);
            if (targetMcVersion.startsWith(base + ".")) {
                LOGGER.debug("Native: wildcard match {}", modMcVersion);
                return true;
            }
        }

        return false;
    }

    /** Returns >0 if v1 > v2, 0 if equal, &lt;0 if v1 &lt; v2. */
    private int compareVersions(String v1, String v2) {
        try {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");

            int maxLen = Math.max(parts1.length, parts2.length);
            for (int i = 0; i < maxLen; i++) {
                int p1 = i < parts1.length ? Integer.parseInt(parts1[i].replaceAll("[^0-9]", "")) : 0;
                int p2 = i < parts2.length ? Integer.parseInt(parts2[i].replaceAll("[^0-9]", "")) : 0;
                if (p1 != p2) return p1 - p2;
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Whether a mod uses Mixins. */
    private boolean checkForMixins(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            ZipEntry fabricJson = jar.getEntry("fabric.mod.json");
            if (fabricJson != null) {
                String content = new String(jar.getInputStream(fabricJson).readAllBytes());
                if (content.contains("\"mixins\"") && !content.contains("\"mixins\": []")) {
                    return true;
                }
            }

            if (jar.getEntry("mixins.json") != null) return true;
            if (jar.getEntry("modid.mixins.json") != null) return true;

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".mixins.json")) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Could not check for mixins: {}", e.getMessage());
        }
        return false;
    }

    /**
     * Extract a JAR, enforcing the per-entry and total caps against real bytes
     * written rather than the attacker-controlled {@link JarEntry#getSize()}.
     */
    private void extractJar(Path jarPath, Path outputDir) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            var entries = jar.entries();
            long totalSize = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                Path outputPath = ZipSecurity.safeResolve(outputDir, entry.getName());

                if (entry.isDirectory()) {
                    Files.createDirectories(outputPath);
                } else {
                    Files.createDirectories(outputPath.getParent());
                    long writtenBytes;
                    try (InputStream is = jar.getInputStream(entry)) {
                        writtenBytes = ZipSecurity.copyBounded(is, outputPath, MAX_ENTRY_SIZE, entry.getName());
                    }
                    totalSize += writtenBytes;
                    if (totalSize > MAX_TOTAL_SIZE) {
                        throw new IOException("ZIP total extracted size exceeds limit ("
                            + MAX_TOTAL_SIZE + " bytes) - possible zip bomb (decompressed "
                            + totalSize + " bytes so far)");
                    }
                }
            }
        }
    }

    /**
     * Transform every class file in a directory, giving Mixin classes the
     * extra annotation pass.
     */
    private int transformClasses(Path dir) throws IOException {
        return transformClasses(dir, com.retromod.mixin.MixinRefmapRepairIndex.empty());
    }

    private int transformClasses(Path dir,
            com.retromod.mixin.MixinRefmapRepairIndex refmapRepairs) throws IOException {
        final com.retromod.mixin.MixinCompatibilityTransformer mixinTransformer;
        com.retromod.mixin.MixinCompatibilityTransformer mt = null;
        try {
            mt = new com.retromod.mixin.MixinCompatibilityTransformer(bytecodeTransformer);
        } catch (Exception e) {
            LOGGER.debug("Mixin transformer not available");
        }
        mixinTransformer = mt;

        final Set<String> mixinClasses = findMixinClasses(dir);

        final java.util.List<Path> classFiles;
        try (var stream = Files.walk(dir)) {
            classFiles = stream
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.toString().contains("META-INF"))
                .toList();
        }

        // Classes are independent, so they can share the transform pool.
        final java.util.concurrent.atomic.AtomicInteger counter =
                new java.util.concurrent.atomic.AtomicInteger();

        // ASM needs the mod's own class hierarchy before those classes are on the classpath.
        bytecodeTransformer.setJarClassBytesProvider(name -> {
            try {
                Path cf = dir.resolve(name + ".class");
                return Files.exists(cf) ? Files.readAllBytes(cf) : null;
            } catch (IOException e) {
                return null;
            }
        });
        try {
            com.retromod.core.parallel.RetromodExecutors.parallelForEach(classFiles, classFile -> {
                try {
                    byte[] original = Files.readAllBytes(classFile);
                    String className = dir.relativize(classFile).toString()
                        .replace(".class", "")
                        .replace(File.separator, "/");

                    byte[] transformed;

                    if (mixinTransformer != null && mixinClasses.contains(className)) {
                        // Mixin selectors are annotation text, so remap them before
                        // the normal bytecode pass.
                        transformed = mixinTransformer.transformMixinClass(original);
                        if (transformed != original) {
                            LOGGER.debug("Transformed Mixin annotations: {}", className);
                        }
                        byte[] remapped = bytecodeTransformer.transformClass(
                            transformed != null ? transformed : original, className);
                        if (remapped != null && remapped != transformed) {
                            transformed = remapped;
                        }
                        // The member bridges and the ValueIO adapter expect Mojang names,
                        // which are only available after the main Fabric remap.
                        if (transformed != null) {
                            byte[] repaired = mixinTransformer.applyPostRemapRepairs(
                                    transformed, refmapRepairs);
                            if (repaired != null && repaired != transformed) {
                                transformed = repaired;
                            }
                        }
                    } else {
                        transformed = bytecodeTransformer.transformClass(original, className);
                    }

                    // GUI migration also works in Mojang names and must follow the remap.
                    if (transformed != null && RetromodPreLaunch.isUnobfuscatedTarget(targetMcVersion)) {
                        transformed = com.retromod.shim.common.Gui2DTransformMigration.migrate(transformed);
                    }

                    boolean wroteFirst = false;
                    if (transformed != null && transformed != original) {
                        Files.write(classFile, transformed);
                        counter.incrementAndGet();
                        wroteFirst = true;
                    }

                    // Inject missing abstract methods for Button subclasses.
                    byte[] current = (transformed != null && transformed != original) ? transformed : original;
                    byte[] patched = com.retromod.shim.common.LegacyAbstractButtonBridge
                            .apply(current);
                    if (patched != null && patched != current) {
                        Files.write(classFile, patched);
                        if (!wroteFirst) counter.incrementAndGet();
                    }
                } catch (Exception e) {
                    LOGGER.warn("Could not transform class: {} ({}: {})",
                            classFile.getFileName(), e.getClass().getSimpleName(), e.getMessage());
                }
            });
        } finally {
            bytecodeTransformer.clearJarClassBytesProvider();
        }

        return counter.get();
    }

    /** Adds the no-op abstract button method required by current hosts. */
    private static final Set<String> BUTTON_SUPERCLASSES = Set.of(
        "net/minecraft/client/gui/components/Button",
        "net/minecraft/client/gui/components/AbstractButton",
        "net/minecraft/client/gui/components/AbstractWidget"
    );

    private byte[] injectMissingAbstractMethods(byte[] classBytes, String className) {
        try {
            ClassReader reader = new ClassReader(classBytes);
            String superName = reader.getSuperName();
            if (superName == null) return null;

            if (!BUTTON_SUPERCLASSES.contains(superName)) {
                return null;
            }

            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            String methodName = "extractContents";
            String methodDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

            boolean hasMethod = false;
            for (MethodNode method : classNode.methods) {
                if (method.name.equals(methodName)) {
                    hasMethod = true;
                    break;
                }
            }

            if (hasMethod) {
                return null;
            }

            // No-op override (super is abstract, can't delegate).
            MethodNode newMethod = new MethodNode(
                Opcodes.ACC_PUBLIC,
                methodName,
                methodDesc,
                null,
                null
            );

            newMethod.instructions = new InsnList();
            newMethod.instructions.add(new InsnNode(Opcodes.RETURN));
            newMethod.maxStack = 0;
            newMethod.maxLocals = 5;

            classNode.methods.add(newMethod);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(writer);
            byte[] result = writer.toByteArray();
            LOGGER.info("Injected missing extractContents() into {} (extends {})", className, superName);
            return result;
        } catch (Exception e) {
            LOGGER.debug("Could not inject abstract methods into {}: {}", className, e.getMessage());
            return null;
        }
    }

    /**
     * Wrap Fabric entrypoint methods (onInitialize/Client/Server) in try-catch so
     * one mod referencing a removed class during init doesn't take down the game.
     */
    // Package-private for AutoClickyFrameMergeTest, which pins the frame merge this rebuilds.
    void wrapEntrypoints(Path dir) {
        Set<String> entrypointMethods = Set.of(
            "onInitialize", "onInitializeClient", "onInitializeServer",
            "onPreLaunch"
        );

        // Rebuilding a frame needs the mod's own class hierarchy, and those classes are not on the
        // transform classpath. Reading them from the jar being unpacked is what keeps a merge of two
        // mod-owned types from widening to Object.
        final java.util.function.Function<String, byte[]> ownClasses = name -> {
            try {
                Path cf = dir.resolve(name + ".class");
                return Files.exists(cf) ? Files.readAllBytes(cf) : null;
            } catch (IOException e) {
                return null;
            }
        };

        try (var stream = Files.walk(dir)) {
            var classFiles = stream
                .filter(p -> p.toString().endsWith(".class"))
                .filter(p -> !p.toString().contains("META-INF"))
                .filter(p -> !p.toString().contains("com/retromod/"))
                .toList();

            for (Path classFile : classFiles) {
                try {
                    byte[] original = Files.readAllBytes(classFile);
                    ClassReader reader = new ClassReader(original);
                    ClassNode classNode = new ClassNode();
                    reader.accept(classNode, 0);

                    boolean modified = false;
                    boolean hasEntrypoint = false;
                    for (MethodNode method : classNode.methods) {
                        if (entrypointMethods.contains(method.name)
                                && method.desc.equals("()V")
                                && (method.access & Opcodes.ACC_PUBLIC) != 0
                                && (method.access & Opcodes.ACC_STATIC) == 0) {
                            if (wrapMethodInTryCatch(method, classNode.name)) {
                                modified = true;
                                hasEntrypoint = true;
                                LOGGER.info("Wrapped entrypoint {}.{}() in try-catch for safety",
                                    classNode.name.replace('/', '.'), method.name);
                            } else {
                                hasEntrypoint = true;
                            }
                        }
                    }

                    // Lifecycle callbacks registered in an entrypoint (CLIENT_STARTED,
                    // END_CLIENT_TICK, ...) fire independently, so wrap them too:
                    // lambda$onInitialize* bodies and static void method-reference
                    // callbacks passed to Event.register().
                    if (hasEntrypoint) {
                        for (MethodNode method : classNode.methods) {
                            boolean isCallbackLambda = method.name.startsWith("lambda$onInitialize")
                                    && method.desc.endsWith(")V")
                                    && (method.access & Opcodes.ACC_STATIC) != 0;

                            // Allowlisted callback names only, so render/tick/mouse/key
                            // methods keep propagating errors.
                            boolean isCallbackMethod = !method.name.contains("$")
                                    && !method.name.equals("<init>")
                                    && !method.name.equals("<clinit>")
                                    && !entrypointMethods.contains(method.name)
                                    && method.desc.endsWith(")V")
                                    && isLikelyCallbackMethod(method.name);

                            if (isCallbackLambda || isCallbackMethod) {
                                if (wrapMethodInTryCatch(method, classNode.name)) {
                                    modified = true;
                                    LOGGER.info("Wrapped callback {}.{}() in try-catch",
                                        classNode.name.replace('/', '.'), method.name);
                                }
                            }
                        }
                    }

                    if (modified) {
                        // COMPUTE_FRAMES, but override getCommonSuperClass: MC classes can't
                        // be loaded offline, and returning Object corrupts exception-merge
                        // frames into a VerifyError (#94).
                        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                try {
                                    return super.getCommonSuperClass(type1, type2);
                                } catch (Exception | LinkageError e) {
                                    // Two of the mod's own types merge here when it branches between
                                    // them. Their shared type is in the jar, so read it rather than
                                    // widening to Object, which the verifier rejects as soon as the
                                    // value is passed somewhere typed (#180: two screens handed to
                                    // Minecraft.setScreen).
                                    String shared = RetromodTransformer.commonSuperViaBytes(
                                            type1, type2, ownClasses);
                                    if (shared != null) {
                                        return shared;
                                    }
                                    return RetromodTransformer.commonSuperFallback(type1, type2);
                                }
                            }
                        };
                        classNode.accept(writer);
                        Files.write(classFile, writer.toByteArray());
                    }
                } catch (Exception e) {
                    LOGGER.debug("Could not wrap entrypoints in: {}", classFile.getFileName());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan for entrypoints: {}", e.getMessage());
        }
    }

    /**
     * Allowlist of method names that look like Fabric lifecycle/event callbacks,
     * safe to wrap. Excludes render/tick/draw/mouse/key/init/resize, which are
     * core Screen/Widget methods that must propagate errors.
     */
    private static boolean isLikelyCallbackMethod(String name) {
        return name.startsWith("on")           // onKeyPressed, onEntityJoin, onClientStarted...
            || name.equals("loadComplete")
            || name.equals("registerClientCommand")
            || name.equals("registerServerCommand")
            || name.equals("playerJoin")
            || name.equals("playerLeave")
            || name.equals("reload");
    }

    /**
     * Wrap a method body in try { ... } catch (Throwable). Returns true if modified.
     */
    private boolean wrapMethodInTryCatch(MethodNode method, String className) {
        if (method.instructions == null || method.instructions.size() == 0) {
            return false;
        }

        // Don't double-wrap.
        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : method.tryCatchBlocks) {
                if ("java/lang/Throwable".equals(tcb.type)) {
                    return false;
                }
            }
        }

        InsnList newInsns = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode catchHandler = new LabelNode();
        LabelNode methodEnd = new LabelNode();

        newInsns.add(tryStart);

        // Move the original body inside the try region.
        InsnList originalInsns = method.instructions;
        while (originalInsns.size() > 0) {
            AbstractInsnNode insn = originalInsns.getFirst();
            originalInsns.remove(insn);
            newInsns.add(insn);
        }

        newInsns.add(tryEnd);
        newInsns.add(new JumpInsnNode(Opcodes.GOTO, methodEnd));

        // catch (Throwable t) { RetromodErrorHandler.handleNonFatal(className, t); }
        // handleNonFatal dedups so each unique error logs once.
        newInsns.add(catchHandler);
        newInsns.add(new VarInsnNode(Opcodes.ASTORE, method.maxLocals));
        newInsns.add(new LdcInsnNode(className.replace('/', '.')));
        newInsns.add(new VarInsnNode(Opcodes.ALOAD, method.maxLocals));
        newInsns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "com/retromod/core/RetromodErrorHandler", "handleNonFatal",
            "(Ljava/lang/String;Ljava/lang/Throwable;)V", false));
        newInsns.add(new InsnNode(Opcodes.RETURN));

        newInsns.add(methodEnd);
        newInsns.add(new InsnNode(Opcodes.RETURN));

        method.instructions = newInsns;

        if (method.tryCatchBlocks == null) {
            method.tryCatchBlocks = new ArrayList<>();
        }
        method.tryCatchBlocks.add(new TryCatchBlockNode(
            tryStart, tryEnd, catchHandler, "java/lang/Throwable"));

        method.maxLocals++; // extra slot for the caught exception
        method.maxStack = Math.max(method.maxStack, 4);

        return true;
    }

    /**
     * Delete bundled Fabric API modules from META-INF/jars/: they clash with the
     * installed Fabric API and carry old field/method names gone in 26.1. The "jars"
     * array entry is removed in updateFabricModJson.
     */
    private void stripBundledFabricApiJars(Path dir) {
        Path jarsDir = dir.resolve("META-INF/jars");
        if (!Files.isDirectory(jarsDir)) return;

        try (var stream = Files.list(jarsDir)) {
            List<Path> toDelete = stream
                .filter(p -> p.getFileName().toString().startsWith("fabric-"))
                .toList();
            for (Path jar : toDelete) {
                LOGGER.info("Stripping bundled Fabric API module: {}", jar.getFileName());
                Files.delete(jar);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not strip bundled Fabric API JARs: {}", e.getMessage());
        }
    }

    /**
     * Remap intermediary names (class_/field_/method_) to Mojang official names in
     * access wideners, mixin configs, refmaps, and nested JARs.
     */
    private void remapIntermediaryNames(Path dir) {
        // 26.1+ only. A pre-26.1 Fabric runtime still uses intermediary names, so the
        // mod's metadata already matches it and remapping would make every mixin target
        // miss (#29). Same gate as the bytecode remap in RetromodPreLaunch.
        if (!RetromodPreLaunch.isUnobfuscatedTarget(targetMcVersion)) {
            LOGGER.info("Host MC {} is pre-26.1 - skipping intermediary→Mojang metadata "
                + "remap (mods keep their working intermediary names)", targetMcVersion);
            return;
        }

        com.retromod.mapping.IntermediaryToMojangMapper mapper =
            com.retromod.mapping.IntermediaryToMojangMapper.getInstance();

        if (!mapper.isLoaded()) {
            LOGGER.warn("Intermediary→Mojang mapper not loaded, skipping metadata remap");
            return;
        }

        int remappedFiles = 0;

        try (var stream = Files.walk(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                String entryPath = dir.relativize(file).toString()
                    .replace(File.separator, "/");

                String nameLower = name.toLowerCase(java.util.Locale.ROOT);
                if (nameLower.endsWith(".accesswidener") || nameLower.endsWith(".classtweaker")) {
                    remapAccessWidener(file, mapper);
                    remappedFiles++;
                } else if (isMixinConfigFile(entryPath)) {
                    remapMixinConfig(file, mapper);
                    remappedFiles++;
                } else if (name.endsWith("-refmap.json") || name.contains("refmap")) {
                    remapRefmap(file, mapper);
                    remappedFiles++;
                } else if (name.endsWith(".jar")) {
                    remapNestedJar(file, mapper);
                    remappedFiles++;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan for intermediary metadata: {}", e.getMessage());
        }

        if (remappedFiles > 0) {
            LOGGER.info("Remapped intermediary→Mojang in {} metadata files", remappedFiles);
        }
    }

    /** Remap an access widener from the intermediary namespace to official. */
    private void remapAccessWidener(Path awFile,
                                     com.retromod.mapping.IntermediaryToMojangMapper mapper) {
        try {
            String content = Files.readString(awFile);
            String remapped = AccessWidenerRemapper.remapToOfficial(content, mapper);
            if (!remapped.equals(content)) {
                Files.writeString(awFile, remapped);
                LOGGER.info("  Remapped access widener: {}", awFile.getFileName());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to remap access widener {}: {}", awFile.getFileName(), e.getMessage());
        }
    }

    /** Remap intermediary references in a mixin config and make it non-fatal. */
    private void remapMixinConfig(Path configFile,
                                   com.retromod.mapping.IntermediaryToMojangMapper mapper) {
        try {
            String content = Files.readString(configFile);
            String remapped = mapper.remapString(content);
            // Non-fatal (required=false, defaultRequire=0): broken mixins log and continue.
            remapped = makeMixinConfigNonFatal(remapped);
            if (!remapped.equals(content)) {
                Files.writeString(configFile, remapped);
                LOGGER.debug("  Remapped mixin config (non-fatal): {}", configFile.getFileName());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to remap mixin config {}: {}", configFile.getFileName(), e.getMessage());
        }
    }

    /**
     * Remap a mixin refmap JSON, replacing intermediary names with official ones.
     * Refmaps look like
     * {@code {"mappings":{"MixinClass":{"field_XXXX:Ldesc;":"target"}},"data":{"intermediary":{...}}}}.
     */
    private void remapRefmap(Path refmapFile,
                              com.retromod.mapping.IntermediaryToMojangMapper mapper) {
        try {
            String content = Files.readString(refmapFile);
            MixinCompatibilityTransformer mixinTransformer =
                    new MixinCompatibilityTransformer(bytecodeTransformer);
            String remapped = MixinRefmapRemapper.remapWithRepairs(
                    content, mapper, mixinTransformer).json();
            if (!remapped.equals(content)) {
                Files.writeString(refmapFile, remapped);
                LOGGER.info("  Remapped refmap: {}", refmapFile.getFileName());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to remap refmap {}: {}", refmapFile.getFileName(), e.getMessage());
        }
    }

    /**
     * Builds the immutable handler plan before classes are transformed. Refmap resources are
     * written later by {@link #remapIntermediaryNames(Path)}, but both passes use the same exact
     * selector planner so the class and resource decisions cannot drift.
     */
    private com.retromod.mixin.MixinRefmapRepairIndex collectRefmapRepairs(Path dir) {
        com.retromod.mixin.MixinRefmapRepairIndex repairs =
                com.retromod.mixin.MixinRefmapRepairIndex.empty();
        if (!RetromodPreLaunch.isUnobfuscatedTarget(targetMcVersion)) return repairs;

        com.retromod.mapping.IntermediaryToMojangMapper mapper =
                com.retromod.mapping.IntermediaryToMojangMapper.getInstance();
        if (!mapper.isLoaded()) return repairs;

        MixinCompatibilityTransformer mixins =
                new MixinCompatibilityTransformer(bytecodeTransformer);
        try (var files = Files.walk(dir)) {
            for (Path refmap : files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith("-refmap.json") || name.contains("refmap");
                    }).toList()) {
                MixinRefmapRemapper.RemapResult result =
                        MixinRefmapRemapper.remapWithRepairs(
                                Files.readString(refmap), mapper, mixins);
                repairs = repairs.merge(result.repairs());
            }
        } catch (Exception e) {
            LOGGER.debug("Could not prepare refmap-aware Mixin repairs: {}", e.getMessage());
        }
        return repairs;
    }

    /**
     * Remap a nested JAR (Jar-in-Jar): extract, remap, strip broken mixin entries,
     * repack.
     */
    private void remapNestedJar(Path jarFile,
                                 com.retromod.mapping.IntermediaryToMojangMapper mapper) {
        try {
            Path tempDir = Files.createTempDirectory("retromod-jij-");
            try {
                extractJar(jarFile, tempDir);

                // Count class transforms toward the repackage decision below. A pure
                // registration/helper JIJ has no metadata to change, so its remapped
                // bytecode would otherwise be discarded, leaving intermediary
                // Registry.register calls dead on a 26.1 host (#71).
                com.retromod.mixin.MixinRefmapRepairIndex refmapRepairs =
                        collectRefmapRepairs(tempDir);
                int classesTransformed = transformClasses(tempDir, refmapRepairs);

                int remapped = classesTransformed;
                Map<String, byte[]> nestedClassLookup = null;

                try (var stream = Files.walk(tempDir)) {
                    for (Path file : stream.filter(Files::isRegularFile).toList()) {
                        String name = file.getFileName().toString();
                        String entryPath = tempDir.relativize(file).toString()
                            .replace(File.separator, "/");

                        String nameLower = name.toLowerCase(java.util.Locale.ROOT);
                        if (nameLower.endsWith(".accesswidener") || nameLower.endsWith(".classtweaker")) {
                            remapAccessWidener(file, mapper);
                            remapped++;
                        } else if (isMixinConfigFile(entryPath)) {
                            remapMixinConfig(file, mapper);
                            // Strip broken mixin entries from nested JARs with corrupted
                            // mixin bytecode.
                            try {
                                String json = Files.readString(file);
                                if (nestedClassLookup == null) {
                                    nestedClassLookup = buildClassLookup(tempDir);
                                }
                                MixinCompatibilityTransformer mixinTransformer =
                                    new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
                                String transformed = mixinTransformer.transformMixinConfig(json, nestedClassLookup);
                                // Re-run on the stripped version (remapMixinConfig handled the original).
                                transformed = makeMixinConfigNonFatal(transformed);
                                Files.writeString(file, transformed, java.nio.charset.StandardCharsets.UTF_8);
                                LOGGER.debug("  Stripped broken mixins in nested JAR config: {}", entryPath);
                            } catch (Exception e) {
                                LOGGER.debug("Could not strip mixins in nested JAR config {}: {}", entryPath, e.getMessage());
                            }
                            remapped++;
                        } else if (name.endsWith("-refmap.json") || name.contains("refmap")) {
                            remapRefmap(file, mapper);
                            remapped++;
                        }
                    }
                }

                // Remap the nested fabric.mod.json, tracking whether it changed so we
                // repackage even with no class transforms. A pure config lib bundled as
                // a JIJ only needs its minecraft constraint loosened; skipping that lets
                // it reject the whole parent mod.
                boolean metadataChanged = false;
                Path nestedModJson = tempDir.resolve("fabric.mod.json");
                if (Files.exists(nestedModJson)) {
                    String before = Files.readString(nestedModJson);
                    updateFabricModJson(tempDir);
                    String after = Files.readString(nestedModJson);
                    metadataChanged = !before.equals(after);
                }

                // Migrate data-pack JSON inside the JIJ too; counts toward repackage.
                int nestedDataMigrated =
                        com.retromod.resources.ModDataMigrator.migrateTree(tempDir, targetMcVersion);
                remapped += nestedDataMigrated;

                if (remapped > 0 || metadataChanged) {
                    Path tempJar = Files.createTempFile("retromod-jij-", ".jar");
                    repackageJar(tempDir, tempJar, jarFile);
                    Files.move(tempJar, jarFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("  Updated nested JAR: {} (classes remapped: {}, metadata changed: {})",
                            jarFile.getFileName(), remapped, metadataChanged);
                }

            } finally {
                deleteRecursively(tempDir);
            }
        } catch (Exception e) {
            LOGGER.debug("Could not remap nested JAR {}: {}", jarFile.getFileName(), e.getMessage());
        }
    }

    private static final Pattern MIXIN_PKG_PATTERN = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MIXIN_ARRAY_PATTERN = Pattern.compile("\"mixins\"\\s*:\\s*\\[([^\\]]+)\\]");
    private static final Pattern QUOTED_STRING_PATTERN = Pattern.compile("\"([^\"]+)\"");

    /** Collect every Mixin class declared in the mod's mixin config files. */
    private Set<String> findMixinClasses(Path dir) {
        Set<String> mixinClasses = new HashSet<>();

        try {
            try (var stream = Files.walk(dir)) {
                // Same naming rules the rest of the pipeline uses. The narrower check here
                // missed modid.mixin.json, and a mod named that way got no Mixin repairs at all.
                stream.filter(p -> isMixinConfigFile(
                        dir.relativize(p).toString().replace(File.separator, "/")))
                    .forEach(mixinConfig -> {
                        try {
                            String content = Files.readString(mixinConfig);
                            Matcher pkgMatcher = MIXIN_PKG_PATTERN.matcher(content);
                            String pkg = pkgMatcher.find() ? pkgMatcher.group(1).replace('.', '/') + "/" : "";

                            Matcher mixinMatcher = MIXIN_ARRAY_PATTERN.matcher(content);
                            if (mixinMatcher.find()) {
                                String mixinsStr = mixinMatcher.group(1);
                                Matcher classMatcher = QUOTED_STRING_PATTERN.matcher(mixinsStr);
                                while (classMatcher.find()) {
                                    mixinClasses.add(pkg + classMatcher.group(1).replace('.', '/'));
                                }
                            }

                            for (String arrayName : new String[]{"client", "server"}) {
                                Pattern arrayPattern = Pattern.compile("\"" + arrayName + "\"\\s*:\\s*\\[([^\\]]+)\\]");
                                Matcher arrayMatcher = arrayPattern.matcher(content);
                                if (arrayMatcher.find()) {
                                    String arrayStr = arrayMatcher.group(1);
                                    Matcher classMatcher2 = QUOTED_STRING_PATTERN.matcher(arrayStr);
                                    while (classMatcher2.find()) {
                                        mixinClasses.add(pkg + classMatcher2.group(1).replace('.', '/'));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Could not parse mixin config: {}", mixinConfig.getFileName());
                        }
                    });
            }
        } catch (Exception e) {
            LOGGER.debug("Could not scan for mixin configs");
        }

        if (!mixinClasses.isEmpty()) {
            LOGGER.debug("Found {} Mixin classes to handle specially", mixinClasses.size());
        }

        return mixinClasses;
    }

    /** Patch fabric.mod.json for the target version and strip incompatible declarations. */
    private void updateFabricModJson(Path dir) throws IOException {
        Path modJson = dir.resolve("fabric.mod.json");

        if (!Files.exists(modJson)) {
            LOGGER.warn("No fabric.mod.json found - skipping metadata update");
            return;
        }

        String content = Files.readString(modJson);
        String originalMcVersion = extractVersionFromContent(content);
        String updated = updateVersionRequirements(content, originalMcVersion);

        updated = stripFabricApiJarReferences(updated);

        // "breaks"/"conflicts" are usually stale, but Fabric Loader enforces them as hard
        // incompatibilities before any transform runs. Without stripping, translated mods
        // reject each other on old vendor declarations.
        updated = stripBreaksAndConflicts(updated);

        // "classTweakers"/"accessWidener" name files whose namespace header (intermediary
        // vs official) can mismatch the runtime and throw ClassTweakerFormatException at
        // launch. remapAccessWidener only covers one direction, so for the rest we drop
        // the declaration: the mod loses class-opening but loads.
        updated = stripClassTweakers(updated);

        Files.writeString(modJson, updated);
        LOGGER.info("Updated fabric.mod.json: {} → {}", originalMcVersion, targetMcVersion);
    }

    /**
     * Remove {@code "classTweakers"} (array) and {@code "accessWidener"} (string) from
     * fabric.mod.json. Three regex variants (middle, first, sole key) keep the JSON
     * valid wherever the field sat.
     */
    private String stripClassTweakers(String json) {
        String result = json;

        result = result.replaceAll(
                "(?s),\\s*\"classTweakers\"\\s*:\\s*\\[[^\\]]*\\]",
                "");
        result = result.replaceAll(
                "(?s)\"classTweakers\"\\s*:\\s*\\[[^\\]]*\\]\\s*,\\s*",
                "");
        result = result.replaceAll(
                "(?s)\"classTweakers\"\\s*:\\s*\\[[^\\]]*\\]",
                "");

        result = result.replaceAll(
                "(?s),\\s*\"accessWidener\"\\s*:\\s*\"[^\"]*\"",
                "");
        result = result.replaceAll(
                "(?s)\"accessWidener\"\\s*:\\s*\"[^\"]*\"\\s*,\\s*",
                "");
        result = result.replaceAll(
                "(?s)\"accessWidener\"\\s*:\\s*\"[^\"]*\"",
                "");

        return result;
    }

    /**
     * Remove the top-level {@code "breaks"}/{@code "conflicts"} objects from
     * fabric.mod.json. The inner {@code [^{}]*} only matches flat values, so a
     * nested (environment-scoped) form is left alone rather than half-stripped.
     * Each variant absorbs the adjoining comma so the JSON stays valid without a
     * separate trailing-comma cleanup pass.
     */
    private String stripBreaksAndConflicts(String json) {
        String[] targets = {"breaks", "conflicts"};
        String result = json;
        for (String target : targets) {
            // middle or last key (leading comma)
            result = result.replaceAll(
                    "(?s),\\s*\"" + target + "\"\\s*:\\s*\\{[^{}]*\\}",
                    "");
            // first key (trailing comma)
            result = result.replaceAll(
                    "(?s)\"" + target + "\"\\s*:\\s*\\{[^{}]*\\}\\s*,\\s*",
                    "");
            // sole key
            result = result.replaceAll(
                    "(?s)\"" + target + "\"\\s*:\\s*\\{[^{}]*\\}",
                    "");
        }
        return result;
    }

    /**
     * Drop bundled Fabric API JAR references from the "jars" array (matching
     * stripBundledFabricApiJars, which deletes the files themselves).
     */
    private String stripFabricApiJarReferences(String json) {
        String result = json.replaceAll(
            "\\{\\s*\"file\"\\s*:\\s*\"META-INF/jars/fabric-[^\"]+\\.jar\"\\s*\\}\\s*,?",
            ""
        );
        result = result.replaceAll(",\\s*]", "]");
        result = result.replaceAll("\"jars\"\\s*:\\s*\\[\\s*\\]\\s*,?", "");
        return result;
    }

    /** Read the minecraft version from fabric.mod.json text. */
    private String extractVersionFromContent(String json) {
        Pattern p = Pattern.compile("\"minecraft\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        return m.find() ? m.group(1) : "unknown";
    }

    /**
     * Pin minecraft to the exact target version and relax loader/API constraints.
     * Third-party shimmed APIs are relaxed too: their constraints would block the
     * mod at startup even though the bytecode is already transformed for the
     * installed version.
     */
    private String updateVersionRequirements(String json, String originalVersion) {
        // minecraft can be a string or an array; handle both, pinning to the target.
        Pattern minecraftArrayPattern = Pattern.compile(
            "(\"minecraft\"\\s*:\\s*)\\[[^\\]]+\\]",
            Pattern.MULTILINE
        );
        String updated = minecraftArrayPattern.matcher(json).replaceAll(
            "$1\"" + targetMcVersion + "\""
        );

        Pattern minecraftPattern = Pattern.compile(
            "(\"minecraft\"\\s*:\\s*)\"[^\"]+\"",
            Pattern.MULTILINE
        );
        updated = minecraftPattern.matcher(updated).replaceAll(
            "$1\"" + targetMcVersion + "\""
        );

        Pattern loaderPattern = Pattern.compile(
            "(\"fabricloader\"\\s*:\\s*)\"[^\"]+\"",
            Pattern.MULTILINE
        );
        updated = loaderPattern.matcher(updated).replaceAll(
            "$1\">=0.14.0\""
        );

        Pattern fabricApiPattern = Pattern.compile(
            "(\"fabric-api\"\\s*:\\s*)\"[^\"]+\"",
            Pattern.MULTILINE
        );
        updated = fabricApiPattern.matcher(updated).replaceAll(
            "$1\"*\""
        );

        // "fabric" is an alternate id for fabric-api
        Pattern fabricPattern = Pattern.compile(
            "(\"fabric\"\\s*:\\s*)\"[^\"]+\"",
            Pattern.MULTILINE
        );
        updated = fabricPattern.matcher(updated).replaceAll(
            "$1\"*\""
        );

        updated = updated.replace("\"fabric-key-binding-api-v1\"", "\"fabric-key-mapping-api-v1\"");

        updated = relaxShimmedApiDependencies(updated);

        // Relax unknown strict API deps, then move non-core deps to "suggests" so a
        // missing optional API doesn't block load. Only minecraft/fabricloader/java stay
        // required.
        updated = moveBlockingDepsToSuggests(updated);
        updated = moveNonCoreDepsToSuggests(updated);

        if (!updated.contains("\"retromod_transformed\"")) {
            updated = updated.replaceFirst(
                "\\}\\s*$",
                ",\n  \"custom\": {\n    \"retromod_transformed\": true,\n    \"original_mc_version\": \"" + originalVersion + "\",\n    \"target_mc_version\": \"" + targetMcVersion + "\"\n  }\n}"
            );
        }

        return updated;
    }

    /**
     * Replace the version constraint of every known shimmed API dependency with "*",
     * e.g. "cloth-config2": ">=6.0.0 <7.0.0" becomes "cloth-config2": "*".
     */
    private String relaxShimmedApiDependencies(String json) {
        String updated = json;
        int relaxedCount = 0;

        for (String modId : SHIMMED_API_MOD_IDS) {
            Pattern apiPattern = Pattern.compile(
                "(\"" + Pattern.quote(modId) + "\"\\s*:\\s*)\"[^\"]+\"",
                Pattern.MULTILINE
            );

            Matcher matcher = apiPattern.matcher(updated);
            if (matcher.find()) {
                String originalConstraint = matcher.group(0);
                String relaxed = matcher.replaceAll("$1\"*\"");

                if (!relaxed.equals(updated)) {
                    LOGGER.info("  Relaxed API dependency: {} → \"*\" (Retromod has shims)", modId);
                    updated = relaxed;
                    relaxedCount++;
                }
            }
        }

        if (relaxedCount > 0) {
            LOGGER.info("Relaxed {} third-party API version constraint(s)", relaxedCount);
        }

        return updated;
    }

    /**
     * Relax any unlisted dependency that carries an upper-bounded range (likely an API
     * pin that would block loading), e.g. a Cardinal Components sub-module. Replaces its
     * constraint with "*". Core deps and known shimmed APIs are left as-is.
     */
    private String moveBlockingDepsToSuggests(String json) {
        Set<String> keepStrict = Set.of(
            "minecraft", "fabricloader", "fabric-api", "fabric", "java"
        );

        String updated = json;

        // "modid": "...< ..." pairs: an upper bound that would reject newer versions.
        Pattern depPattern = Pattern.compile(
            "\"([a-z0-9_-]+)\"\\s*:\\s*\"([^\"]*<[^\"]+)\"",
            Pattern.MULTILINE
        );

        Matcher matcher = depPattern.matcher(updated);
        StringBuffer sb = new StringBuffer();

        while (matcher.find()) {
            String depId = matcher.group(1);
            String constraint = matcher.group(2);

            if (!keepStrict.contains(depId) && !SHIMMED_API_MOD_IDS.contains(depId)) {
                LOGGER.info("  Relaxed unknown API dependency: {} (was: {})", depId, constraint);
                matcher.appendReplacement(sb, "\"" + depId + "\": \"*\"");
            }
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Move every non-core dependency from "depends" to "suggests" (via Gson), so a
     * missing or mismatched optional API doesn't block launch. Only minecraft,
     * fabricloader, and java stay in "depends".
     */
    private String moveNonCoreDepsToSuggests(String json) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            Set<String> coreDeps = Set.of("minecraft", "fabricloader", "java");

            if (!root.has("depends") || !root.get("depends").isJsonObject()) {
                return json;
            }

            com.google.gson.JsonObject depends = root.getAsJsonObject("depends");
            com.google.gson.JsonObject suggests = root.has("suggests") && root.get("suggests").isJsonObject()
                ? root.getAsJsonObject("suggests")
                : new com.google.gson.JsonObject();

            List<String> toMove = new ArrayList<>();
            for (String key : depends.keySet()) {
                if (!coreDeps.contains(key)) {
                    toMove.add(key);
                }
            }

            if (toMove.isEmpty()) {
                return json;
            }

            for (String key : toMove) {
                suggests.add(key, depends.get(key));
                depends.remove(key);
                LOGGER.info("  Moved dependency to suggests: {}", key);
            }

            root.add("suggests", suggests);

            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
            String result = gson.toJson(root);

            LOGGER.info("Moved {} non-core dependencies from depends to suggests", toMove.size());
            return result;

        } catch (Exception e) {
            LOGGER.warn("Could not restructure dependencies (regex fallback): {}", e.getMessage());
            return json;
        }
    }

    /** Repackage the extracted directory back into a JAR. */
    private void repackageJar(Path sourceDir, Path outputJar, Path originalJar) throws IOException {
        Manifest manifest = null;
        try (JarFile original = new JarFile(originalJar.toFile())) {
            manifest = original.getManifest();
        } catch (Exception e) {
            // fall through to a default manifest
        }

        if (manifest == null) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        }

        manifest.getMainAttributes().putValue("Retromod-Transformed", "true");
        manifest.getMainAttributes().putValue("Retromod-Target-Version", targetMcVersion);

        // Fabric uses one Knot class loader for every mod. Keeping generated helpers at
        // their shared registration names lets an older transformed jar win class lookup
        // for every other mod. Relocate only the helpers this jar references, just as the
        // module-based loaders do, so their bytecode and listener types stay per-mod.
        SyntheticEmbedder.embed(sourceDir, originalJar.getFileName().toString(),
                bytecodeTransformer);
        if (SyntheticEmbedder.hasRegisteredSyntheticReferences(
                sourceDir, bytecodeTransformer)) {
            throw new IOException("could not relocate every generated compatibility class in "
                    + originalJar.getFileName());
        }

        Path parent = outputJar.toAbsolutePath().getParent();
        if (parent == null) throw new IOException("JAR output has no parent: " + outputJar);
        Files.createDirectories(parent);
        Path stagedJar = Files.createTempFile(parent,
                "." + outputJar.getFileName() + ".", ".tmp");
        try {
            try (JarOutputStream jos = new JarOutputStream(
                    Files.newOutputStream(stagedJar), manifest)) {

            // ZIP directory entries: package resources (ClassLoader.getResources) and classpath
            // scanners (Reflections - YungsApi @AutoRegister) silently find nothing without them.
            com.retromod.util.JarDirectoryEntries.writeAll(jos, sourceDir);

            // Populated lazily while processing mixin configs; modified class bytes are
            // written from here rather than re-read from disk.
            Map<String, byte[]> classLookupForStripping = null;

            // Strip broken mixin entries before writing classes, so the stripped
            // bytes are the ones that get packed.
            try (var preStream = Files.walk(sourceDir)) {
                for (Path file : preStream.filter(Files::isRegularFile).toList()) {
                    String entryName = sourceDir.relativize(file).toString()
                        .replace(File.separator, "/");
                    if (isMixinConfigFile(entryName)) {
                        try {
                            String json = Files.readString(file);
                            if (classLookupForStripping == null) {
                                classLookupForStripping = buildClassLookup(sourceDir);
                            }
                            MixinCompatibilityTransformer mixinTransformer =
                                new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
                            String transformed = mixinTransformer.transformMixinConfig(json, classLookupForStripping);
                            transformed = makeMixinConfigNonFatal(transformed);
                            Files.writeString(file, transformed, java.nio.charset.StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to process mixin config {}: {}", entryName, e.getMessage());
                        }
                    }
                }
            }

            // Process JiJ (Jar-in-Jar) nested JARs in META-INF/jars/. Fabric Loader loads
            // these as separate mods, so their mixin configs need the same non-fatal
            // treatment, bytecode transformation, and refmap cleaning.
            Path jijDir = sourceDir.resolve("META-INF/jars");
            if (Files.isDirectory(jijDir)) {
                try (var jijStream = Files.list(jijDir)) {
                    for (Path jijJar : jijStream.filter(p -> p.toString().endsWith(".jar")).toList()) {
                        String nestedEntry = sourceDir.relativize(jijJar).toString()
                                .replace(File.separator, "/");
                        String syntheticKey = originalJar.getFileName() + "!/" + nestedEntry;
                        processNestedJiJJar(jijJar, syntheticKey);
                    }
                }
            }

            // Strip refmap entries whose targets still contain class_/field_/method_
            // after remapping (they reference removed MC members).
            try (var refmapStream = Files.walk(sourceDir)) {
                for (Path file : refmapStream.filter(Files::isRegularFile).toList()) {
                    String entryName = sourceDir.relativize(file).toString()
                        .replace(File.separator, "/");
                    if (entryName.endsWith("-refmap.json") || entryName.contains("refmap")) {
                        try {
                            stripBrokenRefmapEntries(sourceDir, file);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to process refmap {}: {}", entryName, e.getMessage());
                        }
                    }
                }
            }

            if (classLookupForStripping != null) {
                for (var entry : classLookupForStripping.entrySet()) {
                    Path classFile = sourceDir.resolve(entry.getKey());
                    if (Files.exists(classFile)) {
                        Files.write(classFile, entry.getValue());
                    }
                }
            }

            // Dedup entry names: a second putNextEntry for the same name throws
            // ZipException and aborts the transform. Collisions come from a source mod
            // that lists the same entry twice (case-insensitive FS too). First copy wins.
            Set<String> writtenEntries = new HashSet<>();

            try (var stream = Files.walk(sourceDir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    String entryName = sourceDir.relativize(file).toString()
                        .replace(File.separator, "/");

                    // We wrote our own manifest.
                    if (entryName.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                        continue;
                    }

                    if (!writtenEntries.add(entryName)) {
                        LOGGER.warn("Skipping duplicate JAR entry from source: {} "
                                + "(the source mod's central directory lists this "
                                + "entry more than once - keeping the first copy)",
                                entryName);
                        continue;
                    }

                    JarEntry entry = new JarEntry(entryName);
                    jos.putNextEntry(entry);
                    Files.copy(file, jos);
                    jos.closeEntry();
                }
            }
            }
            try (JarFile ignored = new JarFile(stagedJar.toFile())) {
                // Opening the completed archive validates its central directory.
            }
            moveReplacing(stagedJar, outputJar);
        } finally {
            Files.deleteIfExists(stagedJar);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Drop refmap entries that still hold unresolved intermediary references
     * (class_/method_/field_), recording the mixin classes that used them.
     */
    private void stripBrokenRefmapEntries(Path sourceDir, Path refmapFile) throws IOException {
        String content = Files.readString(refmapFile);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(content).getAsJsonObject();

        boolean changed = false;
        Set<String> brokenMixinClasses = new HashSet<>();

        for (String sectionName : List.of("mappings", "data")) {
            com.google.gson.JsonElement sectionEl = root.get(sectionName);
            if (sectionEl == null || !sectionEl.isJsonObject()) continue;

            com.google.gson.JsonObject section = sectionEl.getAsJsonObject();

            // "data" holds sub-sections like "intermediary"/"named".
            if ("data".equals(sectionName)) {
                for (String sub : new ArrayList<>(section.keySet())) {
                    if (!section.get(sub).isJsonObject()) continue;
                    com.google.gson.JsonObject subSection = section.getAsJsonObject(sub);
                    for (String mixinClass : new ArrayList<>(subSection.keySet())) {
                        if (!subSection.get(mixinClass).isJsonObject()) continue;
                        com.google.gson.JsonObject entries = subSection.getAsJsonObject(mixinClass);
                        for (String key : new ArrayList<>(entries.keySet())) {
                            String value = entries.get(key).getAsString();
                            if (containsUnresolvedIntermediary(key) || containsUnresolvedIntermediary(value)) {
                                brokenMixinClasses.add(mixinClass);
                                entries.remove(key);
                                changed = true;
                            }
                        }
                    }
                }
            } else {
                for (String mixinClass : new ArrayList<>(section.keySet())) {
                    if (!section.get(mixinClass).isJsonObject()) continue;
                    com.google.gson.JsonObject entries = section.getAsJsonObject(mixinClass);
                    for (String key : new ArrayList<>(entries.keySet())) {
                        String value = entries.get(key).getAsString();
                        if (containsUnresolvedIntermediary(key) || containsUnresolvedIntermediary(value)) {
                            brokenMixinClasses.add(mixinClass);
                            entries.remove(key);
                            changed = true;
                        }
                    }
                }
            }
        }

        if (changed) {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();
            Files.writeString(refmapFile, gson.toJson(root));
            LOGGER.info("Cleaned {} broken refmap entries for mixin classes: {}",
                brokenMixinClasses.size(), brokenMixinClasses);
        }
    }

    /** Repairs a nested library's Mixin, on bytes the class remap has already been through. */
    private static byte[] repairNestedMixin(
            com.retromod.mixin.MixinCompatibilityTransformer mixins, byte[] classBytes, String className) {
        try {
            byte[] out = mixins.stripBlocklistedHandlers(classBytes);
            return mixins.applyPostRemapRepairs(out);
        } catch (Throwable t) {
            LOGGER.debug("Could not repair the Mixin in JiJ class {}: {}", className, t.toString());
            return classBytes;
        }
    }

    /**
     * Whether a path looks like a mixin config JSON. Covers standard
     * (modid.mixins.json) and non-standard names (mixins.modmenu.json) plus configs
     * under a "mixins/" directory.
     */
    private boolean isMixinConfigFile(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (name.endsWith(".mixins.json")) return true;
        if (name.endsWith("mixin.json")) return true;
        if (name.startsWith("mixins.") && name.endsWith(".json")) return true;
        if (path.contains("mixins/") && name.endsWith(".json") && name.contains("mixin")) return true;
        return false;
    }

    /** Whether a refmap key/value still holds an unresolved intermediary reference. */
    private boolean containsUnresolvedIntermediary(String s) {
        if (s == null) return false;
        return s.contains("class_") || s.contains("field_") || s.contains("method_");
    }

    /**
     * Make a mixin config non-fatal: "required": false plus "injectors":
     * {"defaultRequire": 0}. @Accessor/@Invoker against removed members would
     * otherwise crash, and @Accessor has no per-annotation require flag.
     */
    private String makeMixinConfigNonFatal(String json) {
        try {
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();

            root.addProperty("required", false);

            com.google.gson.JsonObject injectors = root.has("injectors") && root.get("injectors").isJsonObject()
                ? root.getAsJsonObject("injectors")
                : new com.google.gson.JsonObject();
            injectors.addProperty("defaultRequire", 0);
            root.add("injectors", injectors);

            com.google.gson.Gson gson = new com.google.gson.GsonBuilder()
                .setPrettyPrinting().disableHtmlEscaping().create();
            String result = gson.toJson(root);
            LOGGER.debug("Set mixin config to non-fatal (required=false, defaultRequire=0)");
            return result;
        } catch (Exception e) {
            LOGGER.warn("Failed to make mixin config non-fatal: {}", e.getMessage());
            return json;
        }
    }

    /**
     * Process a nested JiJ mod JAR: extract, transform bytecode, make mixin configs
     * non-fatal, clean refmaps, repack.
     */
    private void processNestedJiJJar(Path jijJar, String syntheticKey) {
        String name = jijJar.getFileName().toString();
        try {
            Path tempDir = Files.createTempDirectory("retromod-jij-");
            try {
                // Bounded extraction (same caps as extractJar): a JIJ entry lying about
                // its size can't stream gigabytes to disk.
                long jijTotalSize = 0;
                try (JarFile jf = new JarFile(jijJar.toFile())) {
                    var entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        Path target = ZipSecurity.safeResolve(tempDir, entry.getName());
                        if (entry.isDirectory()) {
                            Files.createDirectories(target);
                        } else {
                            Files.createDirectories(target.getParent());
                            long writtenBytes;
                            try (InputStream is = jf.getInputStream(entry)) {
                                writtenBytes = ZipSecurity.copyBounded(
                                    is, target, MAX_ENTRY_SIZE, entry.getName());
                            }
                            jijTotalSize += writtenBytes;
                            if (jijTotalSize > MAX_TOTAL_SIZE) {
                                throw new IOException("JIJ JAR total extracted size exceeds limit ("
                                    + MAX_TOTAL_SIZE + " bytes) - possible zip bomb in nested "
                                    + "JAR " + name + " (decompressed " + jijTotalSize
                                    + " bytes so far)");
                            }
                        }
                    }
                }

                boolean modified = false;

                // Built here rather than reused, because it snapshots the redirects registered so far.
                var nestedMixins = new com.retromod.mixin.MixinCompatibilityTransformer(
                        bytecodeTransformer);

                // Transform class files (intermediary→Mojang remapping)
                Map<String, byte[]> hierarchyClassLookup = buildClassLookup(tempDir);
                try (var hierarchyScope = bytecodeTransformer
                            .pushJarClassBytesProvider(hierarchyClassLookup::get);
                     var classStream = Files.walk(tempDir)) {
                    for (Path file : classStream.filter(f -> f.toString().endsWith(".class")).toList()) {
                        try {
                            byte[] original = Files.readAllBytes(file);
                            String className = tempDir.relativize(file).toString()
                                .replace(File.separator, "/").replace(".class", "");
                            byte[] transformed = bytecodeTransformer.transformClass(original, className);
                            byte[] current = transformed != null ? transformed : original;
                            // A bundled library ships its own mixins, which need the same repairs
                            // as the mod's or they cannot resolve their targets.
                            byte[] repaired = repairNestedMixin(nestedMixins, current, className);
                            if (repaired != original) {
                                Files.write(file, repaired);
                                modified = true;
                            }
                        } catch (Exception e) {
                            LOGGER.debug("Failed to transform class in JiJ {}: {}", name, e.getMessage());
                        }
                    }
                }

                // Fabric Loader treats a JiJ as a separate mod, but it still shares Knot's
                // class space. Give each nested entry its own helper copies after its classes
                // have received redirects. The outer jar name plus entry path stays stable and
                // distinguishes common nested names such as library.jar across parent mods.
                int embedded = SyntheticEmbedder.embed(tempDir, syntheticKey, bytecodeTransformer);
                if (SyntheticEmbedder.hasRegisteredSyntheticReferences(
                        tempDir, bytecodeTransformer)) {
                    throw new IOException("could not relocate every generated compatibility "
                            + "class in nested JAR " + name);
                }
                if (embedded > 0) {
                    modified = true;
                    LOGGER.info("  Embedded {} referenced synthetic class(es) in JiJ {}",
                            embedded, name);
                }

                // Patch mixin configs to be non-fatal
                try (var mixinStream = Files.walk(tempDir)) {
                    for (Path file : mixinStream.filter(Files::isRegularFile).toList()) {
                        String entryName = tempDir.relativize(file).toString()
                            .replace(File.separator, "/");
                        if (isMixinConfigFile(entryName)) {
                            try {
                                String json = Files.readString(file);
                                Map<String, byte[]> jijClassLookup = buildClassLookup(tempDir);
                                MixinCompatibilityTransformer mixinTransformer =
                                    new MixinCompatibilityTransformer(RetromodTransformer.getInstance());
                                String transformed = mixinTransformer.transformMixinConfig(json, jijClassLookup);
                                transformed = makeMixinConfigNonFatal(transformed);
                                Files.writeString(file, transformed, java.nio.charset.StandardCharsets.UTF_8);
                                modified = true;
                                LOGGER.info("  Patched JiJ mixin config: {} in {}", entryName, name);
                            } catch (Exception e) {
                                LOGGER.warn("Failed to process JiJ mixin config {}: {}", entryName, e.getMessage());
                            }
                        }
                    }
                }

                // Clean refmaps
                try (var refmapStream = Files.walk(tempDir)) {
                    for (Path file : refmapStream.filter(Files::isRegularFile).toList()) {
                        String entryName = tempDir.relativize(file).toString()
                            .replace(File.separator, "/");
                        if (entryName.endsWith("-refmap.json") || entryName.contains("refmap")) {
                            try {
                                stripBrokenRefmapEntries(tempDir, file);
                                modified = true;
                            } catch (Exception e) {
                                LOGGER.debug("Failed to process JiJ refmap {}: {}", entryName, e.getMessage());
                            }
                        }
                    }
                }

                // Repack the JiJ JAR if we modified anything
                if (modified) {
                    Manifest manifest = null;
                    Path manifestFile = tempDir.resolve("META-INF/MANIFEST.MF");
                    if (Files.exists(manifestFile)) {
                        try (InputStream is = Files.newInputStream(manifestFile)) {
                            manifest = new Manifest(is);
                        }
                    }
                    if (manifest == null) {
                        manifest = new Manifest();
                        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                    }

                    Path tempJar = jijJar.getParent().resolve(name + ".tmp");
                    try (JarOutputStream jos = new JarOutputStream(
                            new FileOutputStream(tempJar.toFile()), manifest)) {
                        // ZIP directory entries: package resources (ClassLoader.getResources) and classpath
                        // scanners (Reflections - YungsApi @AutoRegister) silently find nothing without them.
                        com.retromod.util.JarDirectoryEntries.writeAll(jos, tempDir);
                        try (var stream = Files.walk(tempDir)) {
                            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                                String entryName = tempDir.relativize(file).toString()
                                    .replace(File.separator, "/");
                                if (entryName.equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
                                JarEntry entry = new JarEntry(entryName);
                                jos.putNextEntry(entry);
                                Files.copy(file, jos);
                                jos.closeEntry();
                            }
                        }
                    }
                    Files.move(tempJar, jijJar, StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("  Repacked JiJ mod: {}", name);
                }
            } finally {
                deleteRecursively(tempDir);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to process JiJ mod {}: {}", name, e.getMessage());
        }
    }

    /** Map of class name to class bytes, for mixin analysis. */
    private Map<String, byte[]> buildClassLookup(Path sourceDir) {
        Map<String, byte[]> lookup = new HashMap<>();
        try (var stream = Files.walk(sourceDir)) {
            for (Path file : stream.filter(f -> f.toString().endsWith(".class")).toList()) {
                String name = sourceDir.relativize(file).toString().replace(File.separator, "/");
                lookup.put(name, Files.readAllBytes(file));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to build class lookup: {}", e.getMessage());
        }
        return lookup;
    }

    /** Delete a directory and its contents. */
    private void deleteRecursively(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException ignored) {}
                        });
                }
            }
        } catch (IOException ignored) {}
    }

    /** Whether a mod JAR has already been transformed by Retromod. */
    public static boolean isAlreadyTransformed(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String transformed = manifest.getMainAttributes().getValue("Retromod-Transformed");
                if ("true".equals(transformed)) {
                    return true;
                }
            }

            ZipEntry entry = jar.getEntry("fabric.mod.json");
            if (entry != null) {
                String content = new String(jar.getInputStream(entry).readAllBytes());
                return content.contains("\"retromod_transformed\"");
            }

        } catch (Exception e) {
            // treat as not transformed
        }

        return false;
    }

    /**
     * Whether debug mode is on in config/retromod/config.json. Plain string check
     * (no JSON parser at this layer), matching RetromodPreLaunch's config reads.
     */
    private static boolean isDebugEnabled() {
        try {
            Path configPath = Path.of("config/retromod/config.json");
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                return json.contains("\"debug\": true") ||
                       json.contains("\"debug\":true");
            }
        } catch (Exception e) {
            // Default to false; never crash for config reading
        }
        return false;
    }

    /** Checks a transformed mod for references that are missing from the current game. */
    void debugScanTransformedMod(Path transformedJar, String modName) {
        try {
            LOGGER.info("Checking transformed references in {}", modName);

            Set<String> modClasses = new HashSet<>();
            Set<String> referencedClasses = new LinkedHashSet<>();
            Map<String, Set<String>> referencedMethods = new LinkedHashMap<>();
            Map<String, Set<String>> referencedFields = new LinkedHashMap<>();
            Map<String, Set<String>> referencedCtors = new LinkedHashMap<>();

            try (JarFile jar = new JarFile(transformedJar.toFile())) {
                // First pass: mod-internal class names.
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                        modClasses.add(entry.getName().replace(".class", ""));
                    }
                }

                // Second pass: external references from bytecode.
                entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.isDirectory()) continue;

                    try (InputStream is = jar.getInputStream(entry)) {
                        ClassReader cr = new ClassReader(is);
                        cr.accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(int version, int access, String name,
                                              String signature, String superName, String[] interfaces) {
                                if (superName != null) referencedClasses.add(superName);
                                if (interfaces != null) {
                                    for (String iface : interfaces) referencedClasses.add(iface);
                                }
                            }

                            @Override
                            public MethodVisitor visitMethod(int access, String name,
                                                             String desc, String signature,
                                                             String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(int opcode, String owner,
                                                                String mName, String mDesc,
                                                                boolean isInterface) {
                                        referencedClasses.add(owner);
                                        referencedMethods
                                            .computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                            .add(mName + mDesc);
                                        if ("<init>".equals(mName)) {
                                            referencedCtors
                                                .computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                                .add(mDesc);
                                        }
                                    }

                                    @Override
                                    public void visitFieldInsn(int opcode, String owner,
                                                               String fName, String fDesc) {
                                        referencedClasses.add(owner);
                                        referencedFields
                                            .computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                            .add(fName);
                                    }

                                    @Override
                                    public void visitTypeInsn(int opcode, String type) {
                                        if (type != null && !type.startsWith("[")) {
                                            referencedClasses.add(type);
                                        }
                                    }
                                };
                            }
                        }, ClassReader.SKIP_DEBUG);
                    } catch (Exception e) {
                        // One unreadable class should not hide problems in the rest of the mod.
                    }
                }

                debugScanMixinTargets(jar, modName);
            }

            int issueCount = 0;

            // These classes come from Java or libraries bundled with Minecraft.
            String[] safeLibraryPrefixes = {
                "java/", "javax/", "jdk/", "sun/",
                "com/google/gson/", "com/google/common/",
                "org/slf4j/", "org/apache/logging/",
                "org/apache/commons/", "org/objectweb/asm/", "org/lwjgl/",
                "io/netty/", "com/mojang/",
                "it/unimi/dsi/fastutil/", "org/joml/",
                "net/fabricmc/loader/", "net/fabricmc/api/",
                "net/fabricmc/fabric/",
                "com/retromod/",
            };

            for (String cls : referencedClasses) {
                if (modClasses.contains(cls)) continue;
                if (isSafeLibrary(cls, safeLibraryPrefixes)) continue;
                if (!canResolveClass(cls)) {
                    LOGGER.info("Reference check for {}: class {} was not found in Minecraft {}",
                            modName, cls.replace('/', '.'), targetMcVersion);
                    issueCount++;
                }
            }

            for (var entry : referencedMethods.entrySet()) {
                String owner = entry.getKey();
                if (modClasses.contains(owner)) continue;
                if (isSafeLibrary(owner, safeLibraryPrefixes)) continue;
                // A missing owner class was already logged above.
                if (!canResolveClass(owner)) continue;

                for (String nameDesc : entry.getValue()) {
                    int descStart = nameDesc.indexOf('(');
                    if (descStart < 0) continue;
                    String mName = nameDesc.substring(0, descStart);
                    String mDesc = nameDesc.substring(descStart);
                    if (!canResolveMethod(owner, mName, mDesc)) {
                        LOGGER.info("Reference check for {}: method {}.{} was not found in "
                                        + "Minecraft {}",
                                modName, owner.replace('/', '.'), mName, targetMcVersion);
                        issueCount++;
                    }
                }
            }

            for (var entry : referencedFields.entrySet()) {
                String owner = entry.getKey();
                if (modClasses.contains(owner)) continue;
                if (isSafeLibrary(owner, safeLibraryPrefixes)) continue;
                if (!canResolveClass(owner)) continue;

                for (String fieldName : entry.getValue()) {
                    if (!canResolveField(owner, fieldName)) {
                        LOGGER.info("Reference check for {}: field {}.{} was not found in "
                                        + "Minecraft {}",
                                modName, owner.replace('/', '.'), fieldName, targetMcVersion);
                        issueCount++;
                    }
                }
            }

            // A class that exists but has no constructor of the invoked arity is a mismatch.
            for (var entry : referencedCtors.entrySet()) {
                String owner = entry.getKey();
                if (modClasses.contains(owner)) continue;
                if (isSafeLibrary(owner, safeLibraryPrefixes)) continue;
                if (!canResolveClass(owner)) continue;

                for (String desc : entry.getValue()) {
                    if (!canResolveMethod(owner, "<init>", desc)) {
                        int paramCount = countParameters(desc);
                        LOGGER.info("Reference check for {}: constructor {} with {} {} was not "
                                        + "found in Minecraft {}",
                                modName, owner.replace('/', '.'), paramCount,
                                paramCount == 1 ? "parameter" : "parameters", targetMcVersion);
                        issueCount++;
                    }
                }
            }

            if (issueCount == 0) {
                LOGGER.info("Reference check finished for {}. No problems were found.", modName);
            } else {
                LOGGER.info("Reference check finished for {}. Found {} possible {}.",
                        modName, issueCount, issueCount == 1 ? "problem" : "problems");
            }

        } catch (Exception e) {
            LOGGER.info("Could not finish the reference check for {}: {}",
                    modName, e.getMessage());
        }
    }

    /**
     * Log mixin target classes (from the mod's mixin configs) that don't resolve on
     * the runtime classpath; such targets crash when the mixin framework applies them.
     */
    private void debugScanMixinTargets(JarFile jar, String modName) {
        try {
            ZipEntry fabricJson = jar.getEntry("fabric.mod.json");
            if (fabricJson == null) return;

            String content = new String(jar.getInputStream(fabricJson).readAllBytes());
            var matcher = Pattern.compile("\"mixins\"\\s*:\\s*\\[([^]]*)]").matcher(content);
            if (!matcher.find()) return;

            String mixinsArray = matcher.group(1);
            var configMatcher = Pattern.compile("\"([^\"]+\\.json)\"").matcher(mixinsArray);

            while (configMatcher.find()) {
                String configName = configMatcher.group(1);
                ZipEntry configEntry = jar.getEntry(configName);
                if (configEntry == null) continue;

                String configContent = new String(jar.getInputStream(configEntry).readAllBytes());

                // Bytecode contains the full @Mixin targets. The config can still reveal explicit
                // Minecraft class names without another class pass.
                var targetMatcher = Pattern.compile("\"(net\\.minecraft\\.[^\"]+)\"").matcher(configContent);
                while (targetMatcher.find()) {
                    String target = targetMatcher.group(1);
                    String internal = target.replace('.', '/');
                    if (!canResolveClass(internal)) {
                        LOGGER.info("Reference check for {}: Mixin target {} was not found in "
                                        + "Minecraft {}",
                                modName, target, targetMcVersion);
                    }
                }
            }
        } catch (Exception e) {
            // This optional check must not interrupt the actual transformation.
        }
    }

    /** Whether a class name starts with any always-present library prefix. */
    private static boolean isSafeLibrary(String className, String[] prefixes) {
        for (String prefix : prefixes) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Whether the internal-named class loads against the runtime classpath. */
    private static boolean canResolveClass(String internalName) {
        try {
            String dotName = internalName.replace('/', '.');
            Class.forName(dotName, false, FabricModTransformer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        } catch (Exception e) {
            // SecurityException/LinkageError etc.: assume resolvable to avoid false positives
            return true;
        }
    }

    /**
     * Best-effort method resolution by name and parameter count, walking supers and
     * interfaces. No descriptor type parsing (it would force recursive class loading).
     */
    private static boolean canResolveMethod(String ownerInternal, String name, String desc) {
        try {
            String dotName = ownerInternal.replace('/', '.');
            Class<?> cls = Class.forName(dotName, false, FabricModTransformer.class.getClassLoader());
            int paramCount = countParameters(desc);

            if ("<init>".equals(name)) {
                for (var ctor : cls.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == paramCount) return true;
                }
                Class<?> sup = cls.getSuperclass();
                while (sup != null) {
                    for (var ctor : sup.getDeclaredConstructors()) {
                        if (ctor.getParameterCount() == paramCount) return true;
                    }
                    sup = sup.getSuperclass();
                }
                return false;
            }

            Class<?> current = cls;
            while (current != null) {
                for (var m : current.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                        return true;
                    }
                }
                current = current.getSuperclass();
            }
            for (var iface : cls.getInterfaces()) {
                for (var m : iface.getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                        return true;
                    }
                }
            }
            return false;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return true; // missing class already reported in the class check
        } catch (Exception e) {
            return true; // avoid false positives
        }
    }

    /** Whether the class (or a superclass) declares a field of this name. */
    private static boolean canResolveField(String ownerInternal, String fieldName) {
        try {
            String dotName = ownerInternal.replace('/', '.');
            Class<?> cls = Class.forName(dotName, false, FabricModTransformer.class.getClassLoader());
            Class<?> current = cls;
            while (current != null) {
                try {
                    current.getDeclaredField(fieldName);
                    return true;
                } catch (NoSuchFieldException e) {
                    current = current.getSuperclass();
                }
            }
            return false;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return true; // missing class already reported
        } catch (Exception e) {
            return true; // avoid false positives
        }
    }

    /**
     * Count parameters in an ASM method descriptor; "(ILjava/lang/String;D)V" is 3.
     */
    private static int countParameters(String desc) {
        int count = 0;
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) break; // malformed (no ';'): indexOf+1 would loop forever
                i = end + 1;
                count++;
            } else if (c == '[') {
                i++; // array dimension marker
            } else {
                i++; // primitive
                count++;
            }
        }
        return count;
    }
}
