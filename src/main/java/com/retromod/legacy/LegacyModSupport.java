/*
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.legacy;

import com.retromod.core.*;
import com.retromod.aot.*;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Coordinates the larger transform steps needed by mods that cross a Minecraft API era.
 */
public class LegacyModSupport {
    
    /**
     * Minecraft version epochs for transformation grouping.
     */
    public enum Epoch {
        LEGACY_1_8_TO_1_12(1, "1.8", "1.12.2", 8, "Legacy Forge"),
        FLATTENING_1_13(2, "1.13", "1.13.2", 8, "The Flattening"),
        MODERN_FOUNDATION_1_14_TO_1_16(3, "1.14", "1.16.5", 8, "Modern Foundation"),
        CAVES_CLIFFS_1_17_TO_1_18(4, "1.17", "1.18.2", 17, "Caves & Cliffs"),
        DATA_DRIVEN_1_19_TO_1_20(5, "1.19", "1.20.4", 17, "Data-Driven"),
        MODERN_1_20_5_PLUS(6, "1.20.5", "26.1", 21, "Modern Era");
        
        public final int order;
        public final String startVersion;
        public final String endVersion;
        public final int javaVersion;
        public final String name;
        
        Epoch(int order, String start, String end, int java, String name) {
            this.order = order;
            this.startVersion = start;
            this.endVersion = end;
            this.javaVersion = java;
            this.name = name;
        }
        
        public static Epoch fromVersion(String mcVersion) {
            int[] parts = parseVersion(mcVersion);
            int major = parts[0];
            int minor = parts[1];
            
            if (major == 1) {
                if (minor >= 8 && minor <= 12) return LEGACY_1_8_TO_1_12;
                if (minor == 13) return FLATTENING_1_13;
                if (minor >= 14 && minor <= 16) return MODERN_FOUNDATION_1_14_TO_1_16;
                if (minor >= 17 && minor <= 18) return CAVES_CLIFFS_1_17_TO_1_18;
                if (minor >= 19 && minor <= 20) {
                    if (minor == 20 && parts.length > 2 && parts[2] >= 5) {
                        return MODERN_1_20_5_PLUS;
                    }
                    return DATA_DRIVEN_1_19_TO_1_20;
                }
                if (minor >= 21) return MODERN_1_20_5_PLUS;
            }
            
            return MODERN_1_20_5_PLUS; // Default to modern
        }
        
        private static int[] parseVersion(String version) {
            String[] parts = version.split("\\.");
            int[] result = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    result[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    result[i] = 0;
                }
            }
            return result;
        }
    }
    
    /**
     * Mod loader types across history.
     */
    public enum ModLoaderType {
        FORGE_LEGACY,      // Forge 1.8-1.12
        FORGE_MODERN,      // Forge 1.13-1.20
        NEOFORGE,          // NeoForge 1.20.5+
        FABRIC,            // Fabric 1.14+
        LITELOADER,        // LiteLoader (legacy, 1.5-1.12)
        RIFT,              // Rift (1.13 only, rare)
        QUILT,             // Quilt (1.18+)
        UNKNOWN
    }
    
    private final Path modsDirectory;
    private final String targetMcVersion;
    private final Epoch targetEpoch;
    private final EpochTransitionManager transitionManager;
    private final VirtualModLoader virtualLoader;
    private final ObfuscationDatabase obfuscationDb;
    
    public LegacyModSupport(Path modsDirectory, String targetMcVersion) {
        this.modsDirectory = modsDirectory;
        this.targetMcVersion = targetMcVersion;
        this.targetEpoch = Epoch.fromVersion(targetMcVersion);
        this.transitionManager = new EpochTransitionManager();
        this.virtualLoader = new VirtualModLoader();
        this.obfuscationDb = new ObfuscationDatabase();
    }
    
    /**
     * Analyze a mod and determine what transformations are needed.
     */
    public LegacyModAnalysis analyzeMod(Path modJar) throws IOException {
        LegacyModAnalysis analysis = new LegacyModAnalysis(modJar);
        
        try (JarFile jar = new JarFile(modJar.toFile())) {
            // Detect mod loader type
            analysis.modLoader = detectModLoader(jar);
            
            // Detect target Minecraft version
            analysis.targetMcVersion = detectTargetVersion(jar, analysis.modLoader);
            analysis.sourceEpoch = Epoch.fromVersion(analysis.targetMcVersion);
            
            // Detect Java class file version
            analysis.classFileVersion = detectClassFileVersion(jar);
            analysis.sourceJavaVersion = classFileVersionToJava(analysis.classFileVersion);
            
            // Calculate transformation requirements
            analysis.epochTransitions = calculateEpochTransitions(
                analysis.sourceEpoch, targetEpoch
            );
            
            // Determine if virtual mod loader is needed
            analysis.needsVirtualLoader = 
                analysis.sourceEpoch.order <= Epoch.FLATTENING_1_13.order ||
                analysis.modLoader == ModLoaderType.LITELOADER ||
                analysis.modLoader == ModLoaderType.RIFT;
            
            // Estimate transformation complexity
            analysis.complexity = estimateComplexity(analysis);
            
            // Scan for specific API usage
            analysis.apiUsage = scanApiUsage(jar);
        }
        
        return analysis;
    }
    
    /** Updates a legacy mod for the configured target version. */
    public Path transformMod(Path modJar, LegacyModAnalysis analysis) throws IOException {
        System.out.println("Retromod legacy transform");
        System.out.println("Source: " + modJar.getFileName());
        System.out.println("From:   " + analysis.targetMcVersion + " (" + analysis.sourceEpoch.name + ")");
        System.out.println("To:     " + targetMcVersion + " (" + targetEpoch.name + ")");
        System.out.println("Loader: " + analysis.modLoader);
        System.out.println("Source Java: " + analysis.sourceJavaVersion);
        System.out.println();
        
        if (analysis.epochTransitions.isEmpty()) {
            System.out.println("No changes are needed.");
            return modJar;
        }
        
        System.out.println("Transform steps: " + analysis.epochTransitions.size());
        for (EpochTransition t : analysis.epochTransitions) {
            System.out.println("  - " + t.name());
        }
        System.out.println();
        
        // Create output path
        String outputName = modJar.getFileName().toString()
            .replace(".jar", "-retromod-" + targetMcVersion + ".jar");
        Path outputJar = modJar.resolveSibling(outputName);
        
        // Transform the JAR
        long startTime = System.currentTimeMillis();
        
        try (JarFile sourceJar = new JarFile(modJar.toFile());
             JarOutputStream outputStream = new JarOutputStream(
                 new FileOutputStream(outputJar.toFile()))) {
            
            int classesTransformed = 0;
            int classesSkipped = 0;
            
            Enumeration<JarEntry> entries = sourceJar.entries();
            long totalBytes = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Sanitize entry name. Even though we're writing to another JAR
                // (not to disk here), downstream tools may extract the output
                // and be vulnerable to zip-slip. Reject traversal / absolute paths.
                String safeName;
                try {
                    safeName = ZipSecurity.safeEntryName(entry.getName());
                } catch (IOException badName) {
                    System.out.println("[Retromod-Legacy] Skipping unsafe entry: " + badName.getMessage());
                    continue;
                }

                try (InputStream is = sourceJar.getInputStream(entry)) {
                    if (safeName.endsWith(".class")) {
                        // Transform class file
                        byte[] classBytes = ZipSecurity.safeReadAllBytes(is);
                        totalBytes += classBytes.length;
                        if (totalBytes > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                            throw new IOException("Legacy mod exceeds " +
                                    ZipSecurity.DEFAULT_MAX_TOTAL_SIZE + " bytes total (possible zip bomb)");
                        }
                        byte[] transformed = transformClass(
                            classBytes, analysis, safeName
                        );

                        if (transformed != classBytes) {
                            classesTransformed++;
                        } else {
                            classesSkipped++;
                        }

                        outputStream.putNextEntry(new JarEntry(safeName));
                        outputStream.write(transformed);
                        outputStream.closeEntry();

                    } else if (safeName.equals("mcmod.info") ||
                               safeName.equals("mods.toml") ||
                               safeName.equals("META-INF/mods.toml") ||
                               safeName.equals("fabric.mod.json") ||
                               safeName.equals("quilt.mod.json")) {
                        // Transform mod metadata
                        byte[] metadata = ZipSecurity.safeReadAllBytes(is);
                        totalBytes += metadata.length;
                        if (totalBytes > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                            throw new IOException("Legacy mod exceeds total size limit");
                        }
                        byte[] transformed = transformMetadata(
                            metadata, safeName, analysis
                        );

                        outputStream.putNextEntry(new JarEntry(safeName));
                        outputStream.write(transformed);
                        outputStream.closeEntry();

                    } else {
                        // Copy unchanged, streaming with a byte counter so a giant
                        // asset entry can't blow up the total budget silently.
                        outputStream.putNextEntry(new JarEntry(safeName));
                        byte[] buf = new byte[8192];
                        long entryBytes = 0;
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            entryBytes += n;
                            totalBytes += n;
                            if (entryBytes > ZipSecurity.DEFAULT_MAX_ENTRY_SIZE ||
                                totalBytes > ZipSecurity.DEFAULT_MAX_TOTAL_SIZE) {
                                throw new IOException("Legacy mod entry '" + safeName +
                                        "' exceeds size limit (possible zip bomb)");
                            }
                            outputStream.write(buf, 0, n);
                        }
                        outputStream.closeEntry();
                    }
                }
            }
            
            // Embed shim classes
            embedShimClasses(outputStream, analysis);
            
            // Embed virtual mod loader if needed
            if (analysis.needsVirtualLoader) {
                embedVirtualLoader(outputStream, analysis);
            }
            
            long duration = System.currentTimeMillis() - startTime;
            
            System.out.println("Legacy transform finished:");
            System.out.println("  Classes updated:     " + classesTransformed);
            System.out.println("  Classes unchanged:   " + classesSkipped);
            System.out.println("  Time:                " + duration + " ms");
            System.out.println("  Output:              " + outputJar.getFileName());
        }
        
        return outputJar;
    }
    
    /**
     * Transform a single class file.
     */
    private byte[] transformClass(byte[] classBytes, LegacyModAnalysis analysis, 
            String className) {
        
        try {
            ClassReader reader = new ClassReader(classBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            
            // Build transformation chain
            ClassVisitor visitor = writer;
            
            // Add epoch transition transformers in reverse order
            for (int i = analysis.epochTransitions.size() - 1; i >= 0; i--) {
                EpochTransition transition = analysis.epochTransitions.get(i);
                visitor = transition.createTransformer(visitor, obfuscationDb);
            }
            
            // Add class file version upgrader if needed
            if (analysis.classFileVersion < Opcodes.V21) {
                visitor = new ClassVersionUpgrader(visitor, Opcodes.V21);
            }
            
            // Add obfuscation remapper
            visitor = new ObfuscationRemapper(visitor, obfuscationDb, 
                analysis.targetMcVersion, targetMcVersion);
            
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
            
        } catch (Exception e) {
            // If transformation fails, return original
            System.err.println("Warning: Failed to transform " + className + ": " + e.getMessage());
            return classBytes;
        }
    }
    
    // DETECTION METHODS
    
    private ModLoaderType detectModLoader(JarFile jar) {
        // Check for NeoForge
        if (jar.getEntry("META-INF/neoforge.mods.toml") != null) {
            return ModLoaderType.NEOFORGE;
        }
        
        // Check for Fabric
        if (jar.getEntry("fabric.mod.json") != null) {
            return ModLoaderType.FABRIC;
        }
        
        // Check for Quilt
        if (jar.getEntry("quilt.mod.json") != null) {
            return ModLoaderType.QUILT;
        }
        
        // Check for modern Forge
        if (jar.getEntry("META-INF/mods.toml") != null) {
            return ModLoaderType.FORGE_MODERN;
        }
        
        // Check for legacy Forge
        if (jar.getEntry("mcmod.info") != null) {
            return ModLoaderType.FORGE_LEGACY;
        }
        
        // Check for LiteLoader
        if (jar.getEntry("litemod.json") != null) {
            return ModLoaderType.LITELOADER;
        }
        
        // Check for Rift
        if (jar.getEntry("riftmod.json") != null) {
            return ModLoaderType.RIFT;
        }
        
        // Check for @Mod annotation in classes
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".class")) {
                try (InputStream is = jar.getInputStream(entry)) {
                    ClassReader reader = new ClassReader(is);
                    ModAnnotationScanner scanner = new ModAnnotationScanner();
                    reader.accept(scanner, ClassReader.SKIP_CODE);
                    
                    if (scanner.hasForgeModAnnotation) {
                        return ModLoaderType.FORGE_LEGACY;
                    }
                    if (scanner.hasNeoForgeModAnnotation) {
                        return ModLoaderType.NEOFORGE;
                    }
                } catch (Exception e) {
                    // Continue scanning
                }
            }
        }
        
        return ModLoaderType.UNKNOWN;
    }
    
    private String detectTargetVersion(JarFile jar, ModLoaderType loader) {
        try {
            switch (loader) {
                case FABRIC -> {
                    JarEntry entry = jar.getJarEntry("fabric.mod.json");
                    if (entry != null) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            return extractFabricVersion(is);
                        }
                    }
                }
                case FORGE_LEGACY -> {
                    JarEntry entry = jar.getJarEntry("mcmod.info");
                    if (entry != null) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            return extractLegacyForgeVersion(is);
                        }
                    }
                }
                case FORGE_MODERN, NEOFORGE -> {
                    JarEntry entry = jar.getJarEntry("META-INF/mods.toml");
                    if (entry == null) {
                        entry = jar.getJarEntry("META-INF/neoforge.mods.toml");
                    }
                    if (entry != null) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            return extractModernForgeVersion(is);
                        }
                    }
                }
                case QUILT -> {
                    JarEntry entry = jar.getJarEntry("quilt.mod.json");
                    if (entry != null) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            return extractQuiltVersion(is);
                        }
                    }
                }
                case LITELOADER -> {
                    JarEntry entry = jar.getJarEntry("litemod.json");
                    if (entry != null) {
                        try (InputStream is = jar.getInputStream(entry)) {
                            return extractLiteLoaderVersion(is);
                        }
                    }
                }
                case RIFT -> {
                    // Rift was a short-lived 1.13-only loader with almost no
                    // mods published. We have no metadata-extraction helper
                    // for it because nothing uses it in practice. Fall through
                    // to heuristic detection.
                }
                case UNKNOWN -> {
                    // Loader-detect said "don't know", so skip the metadata path
                    // and let heuristic detection take over below.
                }
            }
        } catch (Exception e) {
            // Fall through to heuristic detection
        }

        // Heuristic: check class signatures for version hints
        return detectVersionHeuristic(jar);
    }
    
    private int detectClassFileVersion(JarFile jar) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".class") && 
                !entry.getName().startsWith("META-INF/")) {
                
                try (InputStream is = jar.getInputStream(entry)) {
                    DataInputStream dis = new DataInputStream(is);
                    int magic = dis.readInt();
                    if (magic == 0xCAFEBABE) {
                        int minor = dis.readUnsignedShort();
                        int major = dis.readUnsignedShort();
                        return major;
                    }
                } catch (Exception e) {
                    // Continue
                }
            }
        }
        return Opcodes.V1_8; // Default to Java 8
    }
    
    private int classFileVersionToJava(int classFileVersion) {
        return switch (classFileVersion) {
            case Opcodes.V1_1 -> 1;
            case Opcodes.V1_2 -> 2;
            case Opcodes.V1_3 -> 3;
            case Opcodes.V1_4 -> 4;
            case Opcodes.V1_5 -> 5;
            case Opcodes.V1_6 -> 6;
            case Opcodes.V1_7 -> 7;
            case Opcodes.V1_8 -> 8;
            case Opcodes.V9 -> 9;
            case Opcodes.V10 -> 10;
            case Opcodes.V11 -> 11;
            case Opcodes.V12 -> 12;
            case Opcodes.V13 -> 13;
            case Opcodes.V14 -> 14;
            case Opcodes.V15 -> 15;
            case Opcodes.V16 -> 16;
            case Opcodes.V17 -> 17;
            case Opcodes.V18 -> 18;
            case Opcodes.V19 -> 19;
            case Opcodes.V20 -> 20;
            case Opcodes.V21 -> 21;
            default -> classFileVersion - 44; // Approximate for newer versions
        };
    }
    
    private List<EpochTransition> calculateEpochTransitions(Epoch source, Epoch target) {
        List<EpochTransition> transitions = new ArrayList<>();
        
        if (source.order >= target.order) {
            return transitions; // No transformation needed (or downgrade not supported)
        }
        
        // Add transitions for each epoch gap
        for (int i = source.order; i < target.order; i++) {
            EpochTransition transition = transitionManager.getTransition(i, i + 1);
            if (transition != null) {
                transitions.add(transition);
            }
        }
        
        return transitions;
    }
    
    private String estimateComplexity(LegacyModAnalysis analysis) {
        int score = 0;
        
        // Epoch distance
        int epochGap = targetEpoch.order - analysis.sourceEpoch.order;
        score += epochGap * 20;
        
        // The Flattening is extremely complex
        if (analysis.sourceEpoch.order <= Epoch.FLATTENING_1_13.order &&
            targetEpoch.order > Epoch.FLATTENING_1_13.order) {
            score += 50;
        }
        
        // Java version upgrade
        if (analysis.sourceJavaVersion < 17) score += 10;
        if (analysis.sourceJavaVersion < 11) score += 15;
        
        // Virtual loader needed
        if (analysis.needsVirtualLoader) score += 30;
        
        if (score < 20) return "Low";
        if (score < 50) return "Medium";
        if (score < 80) return "High";
        return "Very High";
    }
    
    // HELPER METHODS
    
    private String extractFabricVersion(InputStream is) throws IOException {
        // Parse fabric.mod.json
        String json = new String(is.readAllBytes());
        // Simple extraction: look for "minecraft" dependency
        int idx = json.indexOf("\"minecraft\"");
        if (idx > 0) {
            int start = json.indexOf("\"", idx + 11) + 1;
            int end = json.indexOf("\"", start);
            if (start > 0 && end > start) {
                String version = json.substring(start, end);
                // Clean up version string (remove ~, ^, >=, etc.)
                return version.replaceAll("[^0-9.]", "").split("-")[0];
            }
        }
        return "1.21";
    }
    
    private String extractLegacyForgeVersion(InputStream is) throws IOException {
        String json = new String(is.readAllBytes());
        int idx = json.indexOf("\"mcversion\"");
        if (idx > 0) {
            int start = json.indexOf("\"", idx + 11) + 1;
            int end = json.indexOf("\"", start);
            if (start > 0 && end > start) {
                return json.substring(start, end).trim();
            }
        }
        return "1.12.2";
    }
    
    private String extractModernForgeVersion(InputStream is) throws IOException {
        String toml = new String(is.readAllBytes());
        // Look for minecraft dependency
        int idx = toml.indexOf("modId=\"minecraft\"");
        if (idx < 0) idx = toml.indexOf("modId = \"minecraft\"");
        if (idx > 0) {
            int versionIdx = toml.indexOf("versionRange", idx);
            if (versionIdx > 0) {
                int start = toml.indexOf("\"", versionIdx) + 1;
                int end = toml.indexOf("\"", start);
                if (start > 0 && end > start) {
                    String range = toml.substring(start, end);
                    // Extract first version from range like [1.20.1,1.21)
                    return range.replaceAll("[\\[\\]()>=<,].*", "")
                               .replaceAll("[^0-9.]", "");
                }
            }
        }
        return "1.20";
    }
    
    private String extractQuiltVersion(InputStream is) throws IOException {
        return extractFabricVersion(is); // Similar format
    }
    
    private String extractLiteLoaderVersion(InputStream is) throws IOException {
        String json = new String(is.readAllBytes());
        int idx = json.indexOf("\"mcversion\"");
        if (idx > 0) {
            int start = json.indexOf("\"", idx + 11) + 1;
            int end = json.indexOf("\"", start);
            if (start > 0 && end > start) {
                return json.substring(start, end).trim();
            }
        }
        return "1.12.2";
    }
    
    private String detectVersionHeuristic(JarFile jar) {
        // Check for known version-specific classes/patterns
        if (jar.getEntry("net/minecraft/util/registry/Registry.class") != null) {
            return "1.14"; // Registry system introduced
        }
        if (jar.getEntry("net/minecraft/block/BlockState.class") != null) {
            return "1.13"; // BlockState introduced in flattening
        }
        // Default to legacy
        return "1.12.2";
    }
    
    private Map<String, Set<String>> scanApiUsage(JarFile jar) {
        Map<String, Set<String>> usage = new HashMap<>();
        // This would scan classes for API references
        // Simplified for now
        return usage;
    }
    
    private byte[] transformMetadata(byte[] metadata, String filename, 
            LegacyModAnalysis analysis) {
        // Update version requirements in mod metadata
        String content = new String(metadata);
        
        // Update Minecraft version requirements
        content = content.replace(
            "\"" + analysis.targetMcVersion + "\"",
            "\"" + targetMcVersion + "\""
        );
        
        return content.getBytes();
    }
    
    private void embedShimClasses(JarOutputStream out, LegacyModAnalysis analysis) 
            throws IOException {
        // Embed required shim classes based on epoch transitions
        for (EpochTransition transition : analysis.epochTransitions) {
            for (String shimClass : transition.getRequiredShims()) {
                embedShimClass(out, shimClass);
            }
        }
    }
    
    private void embedShimClass(JarOutputStream out, String className) throws IOException {
        String resourcePath = "/" + className.replace('.', '/') + ".class";
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is != null) {
                String entryName = className.replace('.', '/') + ".class";
                out.putNextEntry(new JarEntry(entryName));
                is.transferTo(out);
                out.closeEntry();
            }
        }
    }
    
    private void embedVirtualLoader(JarOutputStream out, LegacyModAnalysis analysis) 
            throws IOException {
        // Embed virtual mod loader components for legacy mods
        virtualLoader.embedComponents(out, analysis.modLoader, analysis.sourceEpoch);
    }
    
    // INNER CLASSES
    
    /**
     * Scanner for @Mod annotations.
     */
    private static class ModAnnotationScanner extends ClassVisitor {
        boolean hasForgeModAnnotation = false;
        boolean hasNeoForgeModAnnotation = false;
        
        ModAnnotationScanner() {
            super(Opcodes.ASM9);
        }
        
        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (descriptor.contains("fml/common/Mod") ||
                descriptor.contains("minecraftforge/fml/common/Mod")) {
                hasForgeModAnnotation = true;
            }
            if (descriptor.contains("neoforged/fml/common/Mod")) {
                hasNeoForgeModAnnotation = true;
            }
            return null;
        }
    }
}
