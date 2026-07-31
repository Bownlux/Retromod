/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.aot;

import com.retromod.core.*;
import com.retromod.embedder.*;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.shim.ShimRegistry;
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

    // Bump this when transform behavior changes.
    static final String AOT_VERSION = "1.3.0-snapshot.3";

    // Development classpaths have no jar to hash, so this can be empty.
    private static String currentSelfHash() {
        return AotCacheStamp.currentSelfHash();
    }

    private final ShimRegistry shimRegistry;
    private final RetromodTransformer transformer;
    private final ModVersionDetector versionDetector;
    private final ApiEmbedder apiEmbedder;
    private final String targetMcVersion;

    private int classesTransformed = 0;
    private int classesSkipped = 0;
    private int classesObfuscated = 0;
    
    public AotCompiler(ShimRegistry shimRegistry, String targetMcVersion) {
        this.shimRegistry = shimRegistry;
        this.transformer = RetromodTransformer.getInstance();
        this.versionDetector = new ModVersionDetector();
        this.apiEmbedder = new ApiEmbedder();
        this.targetMcVersion = targetMcVersion;

        // Never reuse transforms produced by a different Retromod build.
        AotCacheStamp.ensureCurrent(AOT_CACHE_DIR);
    }
    
    /** Returns the AOT-compiled JAR for {@code modJar} (possibly a cached copy), or the original if no transform applies. */
    public Path compileModAot(Path modJar) throws IOException {
        LOGGER.info("AOT compiling: {}", modJar.getFileName());

        Path cachedJar = getCachedJar(modJar);
        if (cachedJar != null && isValidCache(cachedJar, modJar)) {
            LOGGER.info("Using cached AOT compilation for: {}", modJar.getFileName());
            return cachedJar;
        }

        ModVersionInfo modInfo = versionDetector.detectVersion(modJar);
        if (modInfo == null) {
            LOGGER.warn("Could not analyze mod: {}", modJar.getFileName());
            return modJar;
        }

        if (!modInfo.needsTransformation(targetMcVersion)) {
            LOGGER.info("Mod {} is already compatible, skipping AOT", modInfo.modId());
            return modJar;
        }

        backupOriginalMod(modJar);

        List<VersionShim> shimChain = shimRegistry.findShimChain(
            modInfo.modLoaderType(),
            modInfo.targetMcVersion(),
            targetMcVersion
        );

        // For 26.x targets the vanilla class-move table below is needed even when the chain is empty,
        // so only bail on an empty chain for non-26.x targets.
        if (shimChain.isEmpty() && !RetromodVersion.isUnobfuscatedTarget(targetMcVersion)) {
            LOGGER.warn("No shim chain available for {} ({} -> {})",
                modInfo.modId(), modInfo.targetMcVersion(), targetMcVersion);
            return modJar;
        }

        for (VersionShim shim : shimChain) {
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
            if ("fabric".equalsIgnoreCase(modInfo.modLoaderType())) {
                try {
                    com.retromod.mapping.IntermediaryToMojangMapper.applyTo(transformer);
                } catch (Exception e) {
                    LOGGER.warn("Could not register member mappings for AOT", e);
                }
            }
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
        
        Path outputJar = AOT_CACHE_DIR.resolve(
            modJar.getFileName().toString().replace(".jar", "-aot.jar")
        );

        compileJar(modJar, outputJar, modInfo);
        
        LOGGER.info("AOT compilation complete: {} classes transformed, {} skipped, {} obfuscated (JIT fallback)",
            classesTransformed, classesSkipped, classesObfuscated);
        
        return outputJar;
    }
    
    /** Backs up the original mod JAR to mods/retromod-backups/ before transformation. */
    private void backupOriginalMod(Path modJar) {
        try {
            Path backupFolder = modJar.getParent().resolve("retromod-backups");
            Files.createDirectories(backupFolder);

            String fileName = modJar.getFileName().toString();
            Path backupPath = backupFolder.resolve(fileName);

            Files.copy(modJar, backupPath, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Created backup: {}", backupPath);
        } catch (Exception e) {
            LOGGER.warn("Could not create backup for: {}", modJar.getFileName(), e);
        }
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
        Map<String, byte[]> transformedClasses = new LinkedHashMap<>();
        Map<String, byte[]> originalResources = new LinkedHashMap<>();
        Set<String> obfuscatedClasses = new HashSet<>();
        
        try (JarFile jar = new JarFile(inputJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                
                if (entry.isDirectory()) continue;
                
                try (InputStream is = jar.getInputStream(entry)) {
                    // bounded read: a crafted jar with a huge/decompression-bomb entry would OOM
                    // the game JVM via readAllBytes (review finding; extractJar already bounds)
                    byte[] data = ZipSecurity.safeReadAllBytes(is);

                    if (entry.getName().endsWith(".class")) {
                        String className = entry.getName().replace(".class", "");

                        if (shouldTransformClass(className, modInfo)) {
                            if (isObfuscated(data)) {
                                obfuscatedClasses.add(className);
                                transformedClasses.put(entry.getName(), data);
                                classesObfuscated++;
                            } else {
                                byte[] transformed = transformClassAot(data, className);
                                transformedClasses.put(entry.getName(), transformed);
                                classesTransformed++;
                            }
                        } else {
                            transformedClasses.put(entry.getName(), data);
                            classesSkipped++;
                        }
                    } else {
                        originalResources.put(entry.getName(), data);
                    }
                }
            }
        }
        
        Map<String, byte[]> embeddedShims = collectEmbeddedShims(modInfo);

        try (JarOutputStream jos = new JarOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputJar.toFile())))) {

            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue(AOT_MANIFEST_KEY, AOT_VERSION);
            manifest.getMainAttributes().putValue("Retromod-Source-Version", modInfo.targetMcVersion());
            manifest.getMainAttributes().putValue("Retromod-Target-Version", targetMcVersion);
            manifest.getMainAttributes().putValue("Retromod-Compiled-Time", String.valueOf(System.currentTimeMillis()));
            manifest.getMainAttributes().putValue("Retromod-Source-Hash", computeHash(inputJar));
            String selfHash = currentSelfHash();
            if (!selfHash.isEmpty()) {
                manifest.getMainAttributes().putValue("Retromod-Self-Hash", selfHash);
            }

            if (!obfuscatedClasses.isEmpty()) {
                manifest.getMainAttributes().putValue("Retromod-JIT-Classes", 
                    String.join(",", obfuscatedClasses));
            }
            
            jos.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            manifest.write(jos);
            jos.closeEntry();

            // ZIP directory entries: package resources (ClassLoader.getResources) and classpath
            // scanners (Reflections, YungsApi @AutoRegister) silently find nothing without them.
            java.util.List<String> allNames = new java.util.ArrayList<>(transformedClasses.keySet());
            allNames.addAll(originalResources.keySet());
            com.retromod.util.JarDirectoryEntries.writeAllForNames(jos, allNames);

            // safeEntryName guards against zip-slip: entry names come from the input JAR, so a
            // malicious mod could ship one that traverses out of the archive ("../../etc/foo.class").
            // Downstream tooling that extracts the output JAR would inherit it.
            for (Map.Entry<String, byte[]> entry : transformedClasses.entrySet()) {
                jos.putNextEntry(new JarEntry(ZipSecurity.safeEntryName(entry.getKey())));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
            
            MixinCompatibilityTransformer mixinTransformer = new MixinCompatibilityTransformer(transformer);
            for (Map.Entry<String, byte[]> entry : originalResources.entrySet()) {
                if (entry.getKey().equals("META-INF/MANIFEST.MF")) continue;

                byte[] data = entry.getValue();

                if (entry.getKey().endsWith(".mixins.json") || entry.getKey().endsWith("mixin.json")) {
                    try {
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

                // Relax version constraints in mod metadata for 26.1+
                if (targetMcVersion != null && targetMcVersion.startsWith("26.")) {
                    if (entry.getKey().equals("fabric.mod.json") || entry.getKey().equals("quilt.mod.json")) {
                        data = relaxFabricModDependencies(data);
                        LOGGER.info("Patched Fabric metadata: {}", entry.getKey());
                    } else if (entry.getKey().equals("META-INF/mods.toml") ||
                               entry.getKey().equals("META-INF/neoforge.mods.toml")) {
                        data = relaxNeoForgeDependencies(data);
                        LOGGER.info("Patched NeoForge/Forge metadata: {}", entry.getKey());
                    }
                }

                // Recurse the transform into bundled Jar-in-Jar libs so an AOT-prepped mod's JiJ'd libs get
                // the same treatment as the JIT path (#95): a lib referencing a relocated class otherwise
                // loads broken or is reported missing. Soft-fails per nested jar.
                if ((entry.getKey().startsWith("META-INF/jars/")
                        || entry.getKey().startsWith("META-INF/jarjar/"))
                        && entry.getKey().endsWith(".jar")) {
                    data = transformNestedJarAot(data, 1);
                }

                jos.putNextEntry(new JarEntry(ZipSecurity.safeEntryName(entry.getKey())));
                jos.write(data);
                jos.closeEntry();
            }

            // Keys come from Retromod's own shim collection, not user input, but safeEntryName guards
            // against a future refactor letting an attacker-controlled string land here.
            for (Map.Entry<String, byte[]> entry : embeddedShims.entrySet()) {
                jos.putNextEntry(new JarEntry(
                    ZipSecurity.safeEntryName("retromod_embedded/" + entry.getKey() + ".class")));
                jos.write(entry.getValue());
                jos.closeEntry();
            }

            writeAotMetadata(jos, modInfo, obfuscatedClasses);
        }
    }

    /** Max Jar-in-Jar nesting depth the AOT path recurses through. */
    private static final int MAX_JIJ_DEPTH_AOT = 4;

    /**
     * Rewrites a bundled Jar-in-Jar library's bytecode and metadata with the outer mod's transformer and
     * recurses into its own bundled jars. Mirrors {@code RetromodCli.transformNestedJar} (kept self-contained
     * so aot doesn't depend on cli). Soft-fails to the original bytes on any error.
     */
    private byte[] transformNestedJarAot(byte[] jarData, int depth) {
        try {
            var bais = new java.io.ByteArrayInputStream(jarData);
            var baos = new java.io.ByteArrayOutputStream(jarData.length);
            boolean modified = false;
            try (var jis = new java.util.jar.JarInputStream(bais);
                 var jos = new JarOutputStream(baos)) {
                java.util.jar.JarEntry e;
                while ((e = jis.getNextJarEntry()) != null) {
                    jos.putNextEntry(new JarEntry(ZipSecurity.safeEntryName(e.getName())));
                    if (!e.isDirectory()) {
                        byte[] d = ZipSecurity.safeReadAllBytes(jis);
                        String name = e.getName();
                        if (name.endsWith(".class")) {
                            String cn = name.substring(0, name.length() - ".class".length());
                            try {
                                byte[] t = transformer.transformClass(d, cn);
                                if (t != null && t != d) { d = t; modified = true; }
                            } catch (Exception ignored) {
                            }
                        } else if (name.equals("fabric.mod.json") || name.equals("quilt.mod.json")) {
                            d = relaxFabricModDependencies(d); modified = true;
                        } else if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                            d = relaxNeoForgeDependencies(d); modified = true;
                        } else if (depth < MAX_JIJ_DEPTH_AOT
                                && (name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))
                                && name.endsWith(".jar")) {
                            byte[] t = transformNestedJarAot(d, depth + 1);
                            if (t != d) { d = t; modified = true; }
                        }
                        jos.write(d);
                    }
                    jos.closeEntry();
                }
            }
            return modified ? baos.toByteArray() : jarData;
        } catch (Exception ex) {
            return jarData;
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

        List<VersionShim> shimChain = shimRegistry.findShimChain(
            modInfo.modLoaderType(),
            modInfo.targetMcVersion(),
            targetMcVersion
        );

        for (VersionShim shim : shimChain) {
            for (String shimClass : shim.getShimClasses()) {
                try {
                    String resourcePath = shimClass.replace('.', '/') + ".class";
                    try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
                        if (is != null) {
                            shims.put(shimClass.replace('.', '/'), is.readAllBytes());
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

    private void writeAotMetadata(JarOutputStream jos, ModVersionInfo modInfo,
            Set<String> obfuscatedClasses) throws IOException {

        StringBuilder sb = new StringBuilder();
        sb.append("# Retromod AOT Compilation Metadata\n");
        sb.append("aot_version=").append(AOT_VERSION).append("\n");
        sb.append("retromod_self_hash=").append(currentSelfHash()).append("\n");
        sb.append("source_mc_version=").append(modInfo.targetMcVersion()).append("\n");
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
        
        jos.putNextEntry(new JarEntry("retromod_aot.properties"));
        jos.write(sb.toString().getBytes());
        jos.closeEntry();
    }
    
    private Path getCachedJar(Path originalJar) {
        Path cached = AOT_CACHE_DIR.resolve(
            originalJar.getFileName().toString().replace(".jar", "-aot.jar")
        );
        return Files.exists(cached) ? cached : null;
    }

    private boolean isValidCache(Path cachedJar, Path originalJar) {
        try (JarFile jar = new JarFile(cachedJar.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest == null) return false;

            String aotVersion = manifest.getMainAttributes().getValue(AOT_MANIFEST_KEY);
            if (!AOT_VERSION.equals(aotVersion)) return false;

            // Any change to Retromod's own classes shifts the self-hash, making the cached transforms stale.
            // Caches written before this field existed (no header) also invalidate, which is correct.
            String selfHash = currentSelfHash();
            if (!selfHash.isEmpty()) {
                String cachedSelfHash = manifest.getMainAttributes().getValue("Retromod-Self-Hash");
                if (!selfHash.equals(cachedSelfHash)) return false;
            }

            String cachedHash = manifest.getMainAttributes().getValue("Retromod-Source-Hash");
            String currentHash = computeHash(originalJar);

            return cachedHash != null && cachedHash.equals(currentHash);

        } catch (Exception e) {
            return false;
        }
    }

    private String computeHash(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = Files.readAllBytes(file);
            byte[] hash = digest.digest(bytes);

            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();

        } catch (Exception e) {
            return "";
        }
    }

    public void clearCache() throws IOException {
        if (Files.exists(AOT_CACHE_DIR)) {
            try (var paths = Files.walk(AOT_CACHE_DIR)) {
                paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            LOGGER.warn("Could not delete: {}", path);
                        }
                    });
            }
        }
        Files.createDirectories(AOT_CACHE_DIR);
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
