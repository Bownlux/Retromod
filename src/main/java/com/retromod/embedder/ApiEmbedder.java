/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.embedder;

import com.retromod.core.RetromodTransformer;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * ApiEmbedder: the core innovation of Retromod.
 * 
 * When a mod depends on APIs that have been removed from the current mod loader version,
 * this class extracts the old API implementation from archived mod loader sources
 * and embeds them directly into the mod JAR.
 * 
 * This effectively creates a "mini mod loader" inside each legacy mod that provides
 * the removed functionality.
 * 
 * Process:
 * 1. Scan mod bytecode to find all external API calls
 * 2. Identify which calls target removed/changed APIs
 * 3. Extract the original API implementation from archived sources
 * 4. Rewrite the API to be self-contained (no external deps)
 * 5. Inject the API classes into the mod JAR
 * 6. Redirect the mod's calls to use the embedded version
 */
public class ApiEmbedder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-Embedder");
    private static final int MAX_ARCHIVE_ENTRIES = 100_000;

    // Location of archived mod loader sources (extracted from old versions)
    private static final Path API_ARCHIVE_DIR = Path.of("config/retromod/api-archive");
    
    // Maps removed API method signatures to their archived implementation location
    private final Map<String, ArchivedApiInfo> removedApiRegistry = new HashMap<>();
    
    // Cache of already-extracted API classes
    private final Map<String, byte[]> extractedClassCache = new HashMap<>();
    
    public ApiEmbedder() {
        // Register known removed APIs
        registerRemovedApis();
    }
    
    /**
     * Register all known removed APIs and where to find their implementations.
     * This is populated from analyzing Fabric/Forge changelogs and source diffs.
     */
    private void registerRemovedApis() {
        // Example: Fabric API module that was removed/deprecated
        registerRemovedApi(
            "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents",
            "START_WORLD_TICK",
            "fabric-api-0.92.0",  // Last version containing this API
            "fabric-lifecycle-events-v1"
        );
        
        // Example: A utility class that was removed
        registerRemovedApi(
            "net/fabricmc/fabric/api/util/NbtType",
            "*",  // All methods
            "fabric-api-0.90.0",
            "fabric-api-base"
        );
        
        // Forge example: Old event that was restructured
        registerRemovedApi(
            "net/minecraftforge/event/world/WorldEvent$Load",
            "*",
            "forge-1.20.4-49.0.0",
            "forge-events"
        );
    }
    
    private void registerRemovedApi(String className, String methodName, 
            String lastGoodVersion, String sourceModule) {
        String key = className + "." + methodName;
        removedApiRegistry.put(key, new ArchivedApiInfo(
            className, methodName, lastGoodVersion, sourceModule
        ));
    }
    
    /**
     * Analyze a mod and embed all required shims for removed APIs.
     */
    public void embedRequiredShims(Path modJarPath, ModVersionInfo modInfo) {
        try {
            // Step 1: Find all API dependencies in the mod
            Set<ApiDependency> dependencies = analyzeModDependencies(modJarPath);
            
            // Step 2: Filter to only removed/changed APIs
            Set<ApiDependency> removedDeps = new HashSet<>();
            for (ApiDependency dep : dependencies) {
                if (isRemovedApi(dep)) {
                    removedDeps.add(dep);
                    LOGGER.info("Mod uses removed API: {}.{}", 
                            dep.className(), dep.methodName());
                }
            }
            
            if (removedDeps.isEmpty()) {
                return;
            }
            
            // Step 3: Extract and embed the required APIs
            embedApisIntoJar(modJarPath, removedDeps);
            
        } catch (Exception e) {
            LOGGER.error("Failed to embed shims into: {}", modJarPath, e);
        }
    }
    
    /**
     * Scan a mod JAR to find all external API method calls.
     */
    private Set<ApiDependency> analyzeModDependencies(Path modJarPath) throws IOException {
        Set<ApiDependency> dependencies = new HashSet<>();
        Set<String> entryNames = new HashSet<>();
        ArchiveBudget budget = new ArchiveBudget("mod dependency scan");
        
        try (JarFile jar = new JarFile(modJarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = registerEntry(entry, entryNames, modJarPath);
                
                if (!entry.isDirectory() && entryName.endsWith(".class")) {
                    try (InputStream is = jar.getInputStream(entry)) {
                        byte[] classBytes = readEntry(is, budget, entryName);
                        dependencies.addAll(extractApiCalls(classBytes));
                    }
                }
            }
        }
        
        return dependencies;
    }
    
    /**
     * Use ASM to extract all method invocations from a class.
     */
    private Set<ApiDependency> extractApiCalls(byte[] classBytes) {
        Set<ApiDependency> calls = new HashSet<>();
        
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        // Only track calls to mod loader APIs
                        if (isModLoaderApi(owner)) {
                            calls.add(new ApiDependency(owner, name, descriptor));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        
        return calls;
    }
    
    /**
     * Check if a class is part of a mod loader API.
     */
    private boolean isModLoaderApi(String className) {
        return className.startsWith("net/fabricmc/") ||
               className.startsWith("net/minecraftforge/") ||
               className.startsWith("net/neoforged/");
    }
    
    /**
     * Check if an API has been removed in the current version.
     */
    private boolean isRemovedApi(ApiDependency dep) {
        String key = dep.className() + "." + dep.methodName();
        String wildcardKey = dep.className() + ".*";
        
        return removedApiRegistry.containsKey(key) || 
               removedApiRegistry.containsKey(wildcardKey);
    }
    
    /**
     * Embed extracted API classes into a mod JAR.
     */
    void embedApisIntoJar(Path modJarPath, Set<ApiDependency> removedDeps)
            throws IOException {
        
        // Create output path for modified JAR
        Path outputPath = embeddedOutputPath(modJarPath);
        
        // Collect all classes we need to embed
        Map<String, byte[]> classesToEmbed = new HashMap<>();
        ArchiveBudget archiveBudget = new ArchiveBudget("archived API extraction");
        
        for (ApiDependency dep : removedDeps) {
            ArchivedApiInfo apiInfo = getApiInfo(dep);
            if (apiInfo != null) {
                // Extract the class and all its dependencies
                Map<String, byte[]> extracted = extractApiWithDependencies(apiInfo, archiveBudget);
                classesToEmbed.putAll(extracted);
            }
        }

        Path sourcePath = modJarPath.toAbsolutePath().normalize();
        Path targetPath = outputPath.toAbsolutePath().normalize();
        if (sourcePath.equals(targetPath)) {
            throw new IOException("Embedded output path matches the source jar: " + sourcePath);
        }
        ZipSecurity.validateNotSymlink(targetPath);
        Path stagedJar = Files.createTempFile(targetPath.getParent(),
                ".retromod-api-embed-", ".jar.tmp");
        try {
            writeEmbeddedJar(sourcePath, stagedJar, classesToEmbed);
            try (JarFile ignored = new JarFile(stagedJar.toFile())) {
                // Opening the completed jar verifies its central directory before replacement.
            }
            moveReplacing(stagedJar, targetPath);
        } finally {
            Files.deleteIfExists(stagedJar);
        }

        LOGGER.info("Created Retromod-enhanced JAR: {} ({} embedded classes)",
                outputPath.getFileName(), classesToEmbed.size());
    }

    private void writeEmbeddedJar(Path sourcePath, Path stagedJar,
            Map<String, byte[]> classesToEmbed) throws IOException {
        Set<String> outputNames = new HashSet<>();
        ArchiveBudget inputBudget = new ArchiveBudget("mod embed input");
        ArchiveBudget outputBudget = new ArchiveBudget("mod embed output");

        try (JarFile originalJar = new JarFile(sourcePath.toFile());
             JarOutputStream newJar = new JarOutputStream(Files.newOutputStream(stagedJar))) {
            // Copy all original entries.
            // Validate every entry name against zip-slip: input is an arbitrary
            // user-supplied JAR, and writing entry.getName() verbatim into the
            // output would propagate any traversal payload to downstream
            // consumers (mod scanners, archive viewers).
            Enumeration<JarEntry> entries = originalJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = registerEntry(entry, outputNames, sourcePath);
                newJar.putNextEntry(new JarEntry(entryName));

                if (!entry.isDirectory()) {
                    try (InputStream is = originalJar.getInputStream(entry)) {
                        byte[] data = readEntry(is, inputBudget, entryName);

                        // If it's a class file, transform it to use embedded APIs
                        if (entryName.endsWith(".class")) {
                            data = transformToUseEmbedded(data, classesToEmbed.keySet());
                        }

                        outputBudget.reserveEntry(data, entryName);
                        newJar.write(data);
                    }
                }

                newJar.closeEntry();
            }

            // Add embedded API classes under a special package.
            // Keys are Retromod-supplied; safeEntryName is defense-in-depth
            // against a future refactor accidentally letting attacker-controlled
            // strings reach this loop.
            for (Map.Entry<String, byte[]> embedded : classesToEmbed.entrySet()) {
                String embeddedPath = registerGeneratedEntry(
                        "retromod_embedded/" + embedded.getKey() + ".class", outputNames);
                outputBudget.reserveEntry(embedded.getValue(), embeddedPath);
                newJar.putNextEntry(new JarEntry(embeddedPath));
                newJar.write(embedded.getValue());
                newJar.closeEntry();
            }

            // Add a marker file indicating this JAR has been processed
            String markerName = registerGeneratedEntry(
                    "retromod_embedded/RETROMOD_PROCESSED", outputNames);
            byte[] marker = ("Processed by Retromod v1.0\n" +
                    "Embedded APIs: " + classesToEmbed.size() + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            outputBudget.reserveEntry(marker, markerName);
            newJar.putNextEntry(new JarEntry(markerName));
            newJar.write(marker);
            newJar.closeEntry();
        }
    }
    
    /**
     * Extract an API class and all classes it depends on.
     */
    private Map<String, byte[]> extractApiWithDependencies(ArchivedApiInfo apiInfo,
            ArchiveBudget budget)
            throws IOException {
        
        Map<String, byte[]> result = new HashMap<>();
        Set<String> toProcess = new HashSet<>();
        Set<String> processed = new HashSet<>();
        
        toProcess.add(apiInfo.className());
        
        while (!toProcess.isEmpty()) {
            String className = toProcess.iterator().next();
            toProcess.remove(className);
            
            if (processed.contains(className)) continue;
            processed.add(className);
            
            // Try to load from archive
            byte[] classBytes = loadFromArchive(apiInfo.archiveVersion(), className, budget);
            if (classBytes == null) {
                LOGGER.warn("Could not find archived class: {}", className);
                continue;
            }
            
            // Rewrite the class to be self-contained
            byte[] rewritten = rewriteForEmbedding(classBytes, apiInfo.archiveVersion());
            result.put(className, rewritten);
            
            // Find dependencies
            Set<String> deps = findClassDependencies(rewritten);
            for (String dep : deps) {
                if (isModLoaderApi(dep) && !processed.contains(dep)) {
                    toProcess.add(dep);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Load a class from the archived API sources.
     */
    private byte[] loadFromArchive(String archiveVersion, String className, ArchiveBudget budget)
            throws IOException {
        // Check cache first
        String cacheKey = archiveVersion + "/" + className;
        if (extractedClassCache.containsKey(cacheKey)) {
            byte[] cached = extractedClassCache.get(cacheKey);
            budget.reserveEntry(cached, className + ".class");
            return cached;
        }
        
        // Look in archive directory
        Path archivePath = API_ARCHIVE_DIR.resolve(archiveVersion + ".jar");
        
        if (!Files.exists(archivePath)) {
            // Try to download the archive
            if (!downloadArchive(archiveVersion, archivePath)) {
                return null;
            }
        }
        
        // Extract from archive JAR
        try (JarFile archiveJar = new JarFile(archivePath.toFile())) {
            validateArchiveEntries(archiveJar, archivePath);
            JarEntry entry = archiveJar.getJarEntry(className + ".class");
            if (entry == null) return null;
            
            try (InputStream is = archiveJar.getInputStream(entry)) {
                byte[] bytes = readEntry(is, budget, entry.getName());
                extractedClassCache.put(cacheKey, bytes);
                return bytes;
            }
        }
    }
    
    /**
     * Download an archived mod loader version.
     */
    private boolean downloadArchive(String version, Path targetPath) {
        // In a real implementation, this would download from Maven Central
        // or a Retromod archive server
        LOGGER.info("No local archive was found for {}. Download it before retrying.", version);
        LOGGER.info("Expected location: {}", targetPath);
        return false;
    }
    
    /**
     * Rewrite a class to be self-contained for embedding.
     * - Relocate package to retromod_embedded/
     * - Update all internal references
     */
    private byte[] rewriteForEmbedding(byte[] classBytes, String archiveVersion) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        
        String prefix = "retromod_embedded/";
        
        ClassVisitor relocator = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                // Relocate to embedded package
                String newName = prefix + name;
                String newSuper = shouldRelocate(superName) ? prefix + superName : superName;
                String[] newInterfaces = interfaces == null ? null :
                        Arrays.stream(interfaces)
                              .map(i -> shouldRelocate(i) ? prefix + i : i)
                              .toArray(String[]::new);
                
                super.visit(version, access, newName, signature, newSuper, newInterfaces);
            }
            
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                // Rewrite descriptor to use relocated types
                String newDesc = relocateDescriptor(descriptor, prefix);
                return new MethodVisitor(Opcodes.ASM9, 
                        super.visitMethod(access, name, newDesc, signature, exceptions)) {
                    
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        String newOwner = shouldRelocate(owner) ? prefix + owner : owner;
                        String newDesc = relocateDescriptor(descriptor, prefix);
                        super.visitMethodInsn(opcode, newOwner, name, newDesc, isInterface);
                    }
                    
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name,
                            String descriptor) {
                        String newOwner = shouldRelocate(owner) ? prefix + owner : owner;
                        String newDesc = relocateDescriptor(descriptor, prefix);
                        super.visitFieldInsn(opcode, newOwner, name, newDesc);
                    }
                    
                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        String newType = shouldRelocate(type) ? prefix + type : type;
                        super.visitTypeInsn(opcode, newType);
                    }
                };
            }
            
            private boolean shouldRelocate(String className) {
                return className != null && (
                    className.startsWith("net/fabricmc/") ||
                    className.startsWith("net/minecraftforge/") ||
                    className.startsWith("net/neoforged/")
                );
            }
        };
        
        reader.accept(relocator, 0);
        return writer.toByteArray();
    }
    
    private String relocateDescriptor(String descriptor, String prefix) {
        // Simple descriptor relocation; in practice you'd use a proper parser
        return descriptor
            .replace("Lnet/fabricmc/", "L" + prefix + "net/fabricmc/")
            .replace("Lnet/minecraftforge/", "L" + prefix + "net/minecraftforge/")
            .replace("Lnet/neoforged/", "L" + prefix + "net/neoforged/");
    }
    
    /**
     * Find all class dependencies of a class.
     */
    private Set<String> findClassDependencies(byte[] classBytes) {
        Set<String> deps = new HashSet<>();
        
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                    String superName, String[] interfaces) {
                if (superName != null) deps.add(superName);
                if (interfaces != null) deps.addAll(Arrays.asList(interfaces));
            }
            
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                            String descriptor, boolean isInterface) {
                        deps.add(owner);
                    }
                    
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name,
                            String descriptor) {
                        deps.add(owner);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        
        return deps;
    }
    
    /**
     * Transform a mod class to use embedded APIs instead of original ones.
     */
    private byte[] transformToUseEmbedded(byte[] classBytes, Set<String> embeddedClasses) {
        // Register redirects for all embedded classes
        for (String embedded : embeddedClasses) {
            RetromodTransformer.getInstance().registerClassRedirect(
                embedded, 
                "retromod_embedded/" + embedded
            );
        }
        
        // Transform the class
        return RetromodTransformer.getInstance().transformClass(
            classBytes, 
            new ClassReader(classBytes).getClassName()
        );
    }
    
    private ArchivedApiInfo getApiInfo(ApiDependency dep) {
        String key = dep.className() + "." + dep.methodName();
        String wildcardKey = dep.className() + ".*";
        
        ArchivedApiInfo info = removedApiRegistry.get(key);
        if (info == null) {
            info = removedApiRegistry.get(wildcardKey);
        }
        return info;
    }

    private static Path embeddedOutputPath(Path modJarPath) throws IOException {
        Path fileName = modJarPath.getFileName();
        if (fileName == null) {
            throw new IOException("Mod jar has no file name: " + modJarPath);
        }
        String name = fileName.toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".jar")
                ? name.substring(0, name.length() - 4)
                : name;
        return modJarPath.resolveSibling(base + "-retromod.jar");
    }

    private static void validateArchiveEntries(JarFile jar, Path jarPath) throws IOException {
        Set<String> names = new HashSet<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            registerEntry(entries.nextElement(), names, jarPath);
        }
    }

    private static String registerEntry(JarEntry entry, Set<String> names, Path jarPath)
            throws IOException {
        if (names.size() >= MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Jar has too many entries: " + jarPath.getFileName());
        }
        String name = validateEntryName(entry.getName(), entry.isDirectory());
        if (!names.add(name)) {
            throw new IOException("Jar contains duplicate entry: " + name);
        }
        return name;
    }

    private static String registerGeneratedEntry(String entryName, Set<String> names)
            throws IOException {
        if (names.size() >= MAX_ARCHIVE_ENTRIES) {
            throw new IOException("Retromod output has too many entries");
        }
        String name = validateEntryName(entryName, false);
        if (!names.add(name)) {
            throw new IOException("Retromod output entry collides with an existing entry: "
                    + name);
        }
        return name;
    }

    private static String validateEntryName(String entryName, boolean directory)
            throws IOException {
        String name = ZipSecurity.safeEntryName(entryName);
        if (name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Jar contains an unsafe entry name: " + entryName);
        }
        String withoutDirectorySlash = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        for (String part : withoutDirectorySlash.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                throw new IOException("Jar contains an unsafe entry name: " + entryName);
            }
        }
        return name;
    }

    private static byte[] readEntry(InputStream input, ArchiveBudget budget, String entryName)
            throws IOException {
        byte[] bytes = ZipSecurity.safeReadAllBytes(input);
        budget.reserveEntry(bytes, entryName);
        return bytes;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class ArchiveBudget {
        private final String operation;
        private long total;

        private ArchiveBudget(String operation) {
            this.operation = operation;
        }

        private void reserveEntry(byte[] bytes, String entryName) throws IOException {
            if (bytes.length > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE) {
                throw new IOException("Jar entry exceeds size limit during " + operation
                        + ": " + entryName);
            }
            if (total > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE - bytes.length) {
                throw new IOException("Jar exceeds total size limit during " + operation);
            }
            total += bytes.length;
        }
    }
    
    // --- Record classes ---
    
    public record ApiDependency(String className, String methodName, String descriptor) {}
    
    public record ArchivedApiInfo(
        String className,
        String methodName, 
        String archiveVersion,
        String sourceModule
    ) {}
}
