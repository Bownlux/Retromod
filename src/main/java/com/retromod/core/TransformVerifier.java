/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.core.verify.McSymbolIndex;
import com.retromod.util.ZipSecurity;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.jar.*;

/**
 * Post-transformation bytecode verifier. Scans a transformed mod JAR for class,
 * method, and field references missing from the runtime classpath, catching
 * unmapped intermediary names, missing shims, or double-renamed classes before
 * the game loads them.
 *
 * <p>Enable with "verify_transforms": true in config/retromod/config.json.
 * Reports go to config/retromod/verify-reports/.
 */
public final class TransformVerifier {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    /** Prefixes that are always on the classpath and should not be flagged. */
    private static final String[] SAFE_PREFIXES = {
        "java/", "javax/", "jdk/", "sun/",
        runtimePrefix("com", "google", "gson"), "com/google/common/",
        runtimePrefix("org", "slf4j"), "org/apache/logging/",
        "org/apache/commons/", "org/objectweb/asm/", "org/lwjgl/",
        "io/netty/",
        "com/mojang/authlib/", "com/mojang/blocklist/", "com/mojang/brigadier/",
        "com/mojang/datafixers/", "com/mojang/jtracy/", "com/mojang/logging/",
        "com/mojang/patchy/", "com/mojang/serialization/", "com/mojang/text2speech/",
        "it/unimi/dsi/fastutil/", "org/joml/",
        "net/fabricmc/loader/", "net/fabricmc/api/",
        "net/fabricmc/fabric/",
        "org/spongepowered/asm/", "com/llamalad7/mixinextras/",
        "net/minecraftforge/", "net/neoforged/",
        "com/retromod/",
    };

    private TransformVerifier() {}

    private static final int MAX_NESTED_VERIFY_DEPTH = 4;

    /**
     * Verify a transformed mod JAR by checking every bytecode reference against
     * the runtime classpath. Never null.
     *
     * @param transformedJar path to the transformed JAR
     * @param modName        human-readable mod name for reports
     * @param targetVersion  target MC version string
     */
    public static VerifyResult verify(Path transformedJar, String modName, String targetVersion) {
        return verify(transformedJar, modName, targetVersion, null);
    }

    /**
     * Verify against an exact target-Minecraft index when one is available. The standalone CLI
     * cannot load Minecraft classes onto its own classpath, so relying only on resource probes
     * makes valid classes from {@code --mc-jar} look missing.
     */
    public static VerifyResult verify(Path transformedJar, String modName, String targetVersion,
            McSymbolIndex targetIndex) {
        return verify(transformedJar, modName, targetVersion, targetIndex,
                RetromodTransformer.NestedArchiveBudget.defaults());
    }

    /** Testable variant with an explicit shared budget for the complete archive tree. */
    static VerifyResult verify(Path transformedJar, String modName, String targetVersion,
            McSymbolIndex targetIndex,
            RetromodTransformer.NestedArchiveBudget archiveBudget) {
        List<Issue> issues = new ArrayList<>();

        try {
            Objects.requireNonNull(archiveBudget, "archiveBudget");
            Set<String> modClasses = new HashSet<>();
            Set<String> referencedClasses = new LinkedHashSet<>();
            Map<String, Set<String>> referencedMethods = new LinkedHashMap<>();
            Map<String, Set<FieldReference>> referencedFields = new LinkedHashMap<>();
            Map<String, Set<String>> referencedCtors = new LinkedHashMap<>();
            Set<String> nestedReferencedClasses = new LinkedHashSet<>();
            Map<String, Set<String>> nestedReferencedMethods = new LinkedHashMap<>();
            Map<String, Set<FieldReference>> nestedReferencedFields = new LinkedHashMap<>();
            Map<String, Set<String>> nestedReferencedCtors = new LinkedHashMap<>();
            boolean scanNestedTargets = targetIndex != null && targetIndex.isAvailable();

            try (JarFile jar = new JarFile(transformedJar.toFile())) {
                // Learn every packaged class and scan outer classes in the same bounded read.
                // Validation happens after the complete walk, so later classes still satisfy
                // references found earlier without a second archive expansion.
                walkArchive(jar, archiveBudget,
                        (entryName, classBytes, nested) -> {
                            String discoveredClass = className(entryName);
                            modClasses.add(discoveredClass);
                            if (nested && !scanNestedTargets) return;
                            try (InputStream input = new ByteArrayInputStream(classBytes)) {
                                if (nested) {
                                    scanClass(input, discoveredClass, nestedReferencedClasses,
                                            nestedReferencedMethods, nestedReferencedFields,
                                            nestedReferencedCtors);
                                } else {
                                    scanClass(input, discoveredClass, referencedClasses,
                                            referencedMethods, referencedFields, referencedCtors);
                                }
                            } catch (Exception ignored) {
                                // One unreadable class must not disable checks for the rest of a mod.
                            }
                        });
            }

            for (String cls : referencedClasses) {
                if (modClasses.contains(cls)) continue;
                if (isSafe(cls)) continue;
                if (!canResolveClass(cls, targetIndex)) {
                    issues.add(new Issue(IssueType.MISSING_CLASS, cls, null, null));
                }
            }

            for (var entry : referencedMethods.entrySet()) {
                String owner = entry.getKey();
                if (isArrayType(owner) || modClasses.contains(owner) || isSafe(owner)
                        || !canResolveClass(owner, targetIndex)) continue;

                for (String nameDesc : entry.getValue()) {
                    int descStart = nameDesc.indexOf('(');
                    if (descStart < 0) continue;
                    String mName = nameDesc.substring(0, descStart);
                    String mDesc = nameDesc.substring(descStart);
                    if (!canResolveMethod(owner, mName, mDesc, targetIndex)) {
                        issues.add(new Issue(IssueType.MISSING_METHOD, owner, mName, mDesc));
                    }
                }
            }

            for (var entry : referencedFields.entrySet()) {
                String owner = entry.getKey();
                if (isArrayType(owner) || modClasses.contains(owner) || isSafe(owner)
                        || !canResolveClass(owner, targetIndex)) continue;

                for (FieldReference field : entry.getValue()) {
                    if (!canResolveField(
                            owner, field.name(), field.descriptor(), targetIndex)) {
                        issues.add(new Issue(IssueType.MISSING_FIELD,
                                owner, field.name(), field.descriptor()));
                    }
                }
            }

            for (var entry : referencedCtors.entrySet()) {
                String owner = entry.getKey();
                if (isArrayType(owner) || modClasses.contains(owner) || isSafe(owner)
                        || !canResolveClass(owner, targetIndex)) continue;

                for (String desc : entry.getValue()) {
                    if (!canResolveMethod(owner, "<init>", desc, targetIndex)) {
                        issues.add(new Issue(IssueType.MISSING_CONSTRUCTOR, owner, "<init>", desc));
                    }
                }
            }

            if (scanNestedTargets) {
                validateNestedTargetReferences(modClasses,
                        nestedReferencedClasses, nestedReferencedMethods,
                        nestedReferencedFields, nestedReferencedCtors,
                        targetIndex, issues);
            }

        } catch (Throwable t) {
            // verifier is a diagnostic and must never abort a transform; catch Errors too
            // (a transformer-layer LinkageError can surface through a probe) and return
            // whatever we gathered (#102).
            LOGGER.warn("Could not verify the transformed mod {}: {}", modName, t.toString());
            addIssue(issues, new Issue(IssueType.VERIFICATION_INCOMPLETE, "", null, null));
        }

        return new VerifyResult(modName, targetVersion, issues);
    }

    @FunctionalInterface
    private interface ArchiveClassVisitor {
        void visit(String entryName, byte[] classBytes, boolean nested) throws IOException;
    }

    private static void walkArchive(JarFile jar,
            RetromodTransformer.NestedArchiveBudget budget,
            ArchiveClassVisitor visitor) throws IOException {
        Set<String> names = new HashSet<>();
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = ZipSecurity.safeEntryName(entry.getName());
            String canonicalName = ZipSecurity.canonicalEntryName(name);
            if (!names.add(canonicalName)) {
                throw new IOException("duplicate JAR entry: " + name);
            }
            if (entry.isDirectory()) {
                budget.reserve(0, canonicalName);
                continue;
            }
            if (canonicalName.endsWith(".class")) {
                try (InputStream in = jar.getInputStream(entry)) {
                    long allowance = budget.beginRead(
                            ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, canonicalName);
                    byte[] classBytes = ZipSecurity.safeReadAllBytes(in, allowance);
                    budget.completeRead(allowance, classBytes.length);
                    visitor.visit(canonicalName, classBytes, false);
                }
            } else if (isNestedJarEntry(canonicalName)) {
                byte[] nested;
                try (InputStream in = jar.getInputStream(entry)) {
                    long allowance = budget.beginRead(
                            ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, canonicalName);
                    nested = ZipSecurity.safeReadAllBytes(in, allowance);
                    budget.completeRead(allowance, nested.length);
                }
                walkNestedArchive(nested, 1, budget, visitor);
            } else {
                budget.reserve(0, canonicalName);
            }
        }
    }

    private static void walkNestedArchive(byte[] archive, int depth,
            RetromodTransformer.NestedArchiveBudget budget,
            ArchiveClassVisitor visitor) throws IOException {
        Set<String> names = new HashSet<>();
        try (var in = new java.util.zip.ZipInputStream(new ByteArrayInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = ZipSecurity.safeEntryName(entry.getName());
                String canonicalName = ZipSecurity.canonicalEntryName(name);
                if (!names.add(canonicalName)) {
                    throw new IOException("duplicate nested JAR entry: " + name);
                }
                if (entry.isDirectory()) {
                    budget.reserve(0, canonicalName);
                    continue;
                }
                long allowance = budget.beginRead(
                        ZipSecurity.DEFAULT_MAX_ENTRY_SIZE, canonicalName);
                byte[] bytes = ZipSecurity.safeReadAllBytes(in, allowance);
                budget.completeRead(allowance, bytes.length);
                if (canonicalName.endsWith(".class")) {
                    visitor.visit(canonicalName, bytes, true);
                } else if (depth < MAX_NESTED_VERIFY_DEPTH
                        && isNestedJarEntry(canonicalName)) {
                    walkNestedArchive(bytes, depth + 1, budget, visitor);
                }
            }
        }
    }

    private static boolean isNestedJarEntry(String name) {
        return name != null && name.endsWith(".jar")
                && (name.startsWith("META-INF/jars/")
                    || name.startsWith("META-INF/jarjar/"));
    }

    private static String className(String entryName) {
        return entryName.substring(0, entryName.length() - ".class".length());
    }

    /**
     * Nested libraries can contain optional integrations that are absent by design. Only links to
     * namespaces owned by the indexed Minecraft JAR are authoritative enough to report here.
     */
    private static void validateNestedTargetReferences(
            Set<String> modClasses,
            Set<String> classes,
            Map<String, Set<String>> methods,
            Map<String, Set<FieldReference>> fields,
            Map<String, Set<String>> ctors,
            McSymbolIndex targetIndex,
            List<Issue> issues) {
        for (String cls : classes) {
            if (!isTargetOwnedClass(cls) || modClasses.contains(cls)) continue;
            if (!targetIndex.hasClass(cls)) {
                addIssue(issues, new Issue(IssueType.MISSING_CLASS, cls, null, null));
            }
        }

        for (var entry : methods.entrySet()) {
            String owner = entry.getKey();
            if (!isTargetOwnedClass(owner) || isArrayType(owner)
                    || modClasses.contains(owner) || !targetIndex.hasClass(owner)) continue;
            for (String nameDesc : entry.getValue()) {
                int descStart = nameDesc.indexOf('(');
                if (descStart < 0) continue;
                String name = nameDesc.substring(0, descStart);
                String descriptor = nameDesc.substring(descStart);
                if (!targetIndex.hasMethod(owner, name, descriptor)) {
                    addIssue(issues,
                            new Issue(IssueType.MISSING_METHOD, owner, name, descriptor));
                }
            }
        }

        for (var entry : fields.entrySet()) {
            String owner = entry.getKey();
            if (!isTargetOwnedClass(owner) || isArrayType(owner)
                    || modClasses.contains(owner) || !targetIndex.hasClass(owner)) continue;
            for (FieldReference field : entry.getValue()) {
                if (!targetIndex.hasField(owner, field.name(), field.descriptor())) {
                    addIssue(issues, new Issue(IssueType.MISSING_FIELD,
                            owner, field.name(), field.descriptor()));
                }
            }
        }

        for (var entry : ctors.entrySet()) {
            String owner = entry.getKey();
            if (!isTargetOwnedClass(owner) || isArrayType(owner)
                    || modClasses.contains(owner) || !targetIndex.hasClass(owner)) continue;
            for (String descriptor : entry.getValue()) {
                if (!targetIndex.hasMethod(owner, "<init>", descriptor)) {
                    addIssue(issues, new Issue(IssueType.MISSING_CONSTRUCTOR,
                            owner, "<init>", descriptor));
                }
            }
        }
    }

    private static boolean isTargetOwnedClass(String internalName) {
        return internalName != null
                && (internalName.startsWith("net/minecraft/")
                    || internalName.startsWith("com/mojang/blaze3d/")
                    || internalName.startsWith("com/mojang/math/")
                    || internalName.startsWith("com/mojang/realmsclient/"));
    }

    private static void addIssue(List<Issue> issues, Issue issue) {
        if (!issues.contains(issue)) issues.add(issue);
    }

    /**
     * Run verification and write the report to config/retromod/verify-reports/.
     * Logs a summary to the console.
     */
    public static VerifyResult verifyAndReport(Path transformedJar, String modName, String targetVersion) {
        return verifyAndReport(transformedJar, modName, targetVersion, null);
    }

    /** Verify and report using the supplied target-Minecraft symbol index when available. */
    public static VerifyResult verifyAndReport(Path transformedJar, String modName,
            String targetVersion, McSymbolIndex targetIndex) {
        LOGGER.info("Checking transformed bytecode for {}", modName);
        long start = System.currentTimeMillis();

        VerifyResult result = verify(transformedJar, modName, targetVersion, targetIndex);
        long elapsed = System.currentTimeMillis() - start;

        if (result.passed()) {
            LOGGER.info("Verification passed for {}: no unresolved references found in {} ms",
                    modName, elapsed);
        } else {
            LOGGER.warn("Verification found {} issue(s) for {} in {} ms",
                    result.issueCount(), modName, elapsed);
            int logged = 0;
            for (Issue issue : result.issues()) {
                if (logged++ >= 10) {
                    LOGGER.warn("... and {} more. See the report file.",
                            result.issueCount() - 10);
                    break;
                }
                LOGGER.warn("{}", issue.toReadableString(targetVersion));
            }
        }

        writeReport(result);
        return result;
    }

    /** True when verify_transforms is enabled in config. */
    public static boolean isEnabled() {
        return isEnabled(RetromodConfig.CONFIG_PATH);
    }

    static boolean isEnabled(Path configPath) {
        return RetromodConfig.getBooleanIfPresent(
                configPath, "verify_transforms", false);
    }

    private static void scanClass(InputStream is, String sourceClass,
            Set<String> classes, Map<String, Set<String>> methods,
            Map<String, Set<FieldReference>> fields,
            Map<String, Set<String>> ctors) throws IOException {

        ClassReader cr = new ClassReader(is);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name,
                              String signature, String superName, String[] interfaces) {
                if (superName != null) classes.add(superName);
                if (interfaces != null) {
                    for (String iface : interfaces) classes.add(iface);
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
                        addClassReference(classes, owner);
                        if (isArrayType(owner)) return;
                        if ("<init>".equals(mName)) {
                            ctors.computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                 .add(mDesc);
                        } else {
                            methods.computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                   .add(mName + mDesc);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner,
                                               String fName, String fDesc) {
                        addClassReference(classes, owner);
                        if (isArrayType(owner)) return;
                        fields.computeIfAbsent(owner, k -> new LinkedHashSet<>())
                              .add(new FieldReference(fName, fDesc));
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        addClassReference(classes, type);
                    }

                    @Override
                    public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                        addClassReference(classes, descriptor);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
    }

    private static void addClassReference(Set<String> classes, String reference) {
        String normalized = normalizeClassReference(reference);
        if (normalized != null) classes.add(normalized);
    }

    /** Returns an object array's element class, ignores primitive arrays, and keeps plain names. */
    private static String normalizeClassReference(String reference) {
        if (reference == null || !isArrayType(reference)) return reference;
        try {
            Type type = Type.getType(reference);
            Type element = type.getElementType();
            return element.getSort() == Type.OBJECT ? element.getInternalName() : null;
        } catch (IllegalArgumentException malformed) {
            return reference;
        }
    }

    private static boolean isArrayType(String reference) {
        return reference != null && reference.startsWith("[");
    }

    private static boolean isSafe(String className) {
        for (String prefix : SAFE_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Build prefixes at runtime so Maven Shade cannot rewrite diagnostic allow-list strings along
     * with Retromod's own bundled Gson and SLF4J references. The game still supplies the original
     * packages to mods even though the standalone CLI relocates its private copies.
     */
    private static String runtimePrefix(String... components) {
        return String.join("/", components) + "/";
    }

    /** Internal names of classes known to exist in the target MC, from the mapping. */
    private static volatile java.util.Set<String> knownTargetClasses;

    /**
     * Fallback for when {@link Class#forName} can't load a class the mapping
     * says exists: client classes on a dedicated server, or other-module classes
     * under NeoForge, which would otherwise show up as bogus "Missing Classes" (#58).
     */
    private static boolean isKnownTargetClass(String internalName) {
        java.util.Set<String> s = knownTargetClasses;
        if (s == null) {
            java.util.Set<String> built = new java.util.HashSet<>();
            try {
                var mapper = com.retromod.mapping.IntermediaryToMojangMapper.getInstance();
                if (mapper.isLoaded()) {
                    built.addAll(mapper.getClassMap().values());     // intermediary → Mojang(26.1)
                    built.addAll(mapper.getClassMoves().keySet());   // pre-move Mojang names
                    built.addAll(mapper.getClassMoves().values());   // post-move 26.1 names
                }
            } catch (Exception ignored) {
                // mapping unavailable: empty set, no fallback
            }
            knownTargetClasses = s = built;
        }
        return s.contains(internalName);
    }

    private static boolean canResolveClass(String internalName) {
        String normalized = normalizeClassReference(internalName);
        if (normalized == null) return isArrayType(internalName);
        internalName = normalized;
        return ClassResourceInspector.exists(internalName) || isKnownTargetClass(internalName);
    }

    private static boolean canResolveClass(String internalName, McSymbolIndex targetIndex) {
        String normalized = normalizeClassReference(internalName);
        if (normalized == null) return isArrayType(internalName);
        internalName = normalized;
        if (usesTargetIndex(internalName, targetIndex)) {
            return targetIndex.hasClass(internalName);
        }
        return canResolveClass(internalName);
    }

    private static boolean canResolveMethod(String ownerInternal, String name, String desc) {
        if (isArrayType(ownerInternal)) return true;
        if (ClassResourceInspector.read(ownerInternal) == null) return true;
        return canResolveMethod(ownerInternal, name, countParameters(desc), new HashSet<>());
    }

    private static boolean canResolveMethod(String ownerInternal, String name, String desc,
            McSymbolIndex targetIndex) {
        if (isArrayType(ownerInternal)) return true;
        if (usesTargetIndex(ownerInternal, targetIndex)) {
            return targetIndex.hasMethod(ownerInternal, name, desc);
        }
        return canResolveMethod(ownerInternal, name, desc);
    }

    private static boolean canResolveField(String ownerInternal, String fieldName) {
        if (isArrayType(ownerInternal)) return true;
        if (ClassResourceInspector.read(ownerInternal) == null) return true;
        return canResolveField(ownerInternal, fieldName, new HashSet<>());
    }

    private static boolean canResolveField(String ownerInternal, String fieldName,
            String fieldDesc, McSymbolIndex targetIndex) {
        if (isArrayType(ownerInternal)) return true;
        if (usesTargetIndex(ownerInternal, targetIndex)) {
            return targetIndex.hasField(ownerInternal, fieldName, fieldDesc);
        }
        return canResolveField(ownerInternal, fieldName);
    }

    private record FieldReference(String name, String descriptor) {}

    private static boolean usesTargetIndex(String internalName, McSymbolIndex targetIndex) {
        return targetIndex != null && targetIndex.isAvailable()
                && isTargetOwnedClass(internalName);
    }

    private static boolean canResolveMethod(
            String owner, String name, int paramCount, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return false;
        var node = ClassResourceInspector.read(owner);
        if (node == null) return false;
        for (var method : node.methods) {
            if (method.name.equals(name) && countParameters(method.desc) == paramCount) return true;
        }
        if (canResolveMethod(node.superName, name, paramCount, visited)) return true;
        for (String iface : node.interfaces) {
            if (canResolveMethod(iface, name, paramCount, visited)) return true;
        }
        return false;
    }

    private static boolean canResolveField(String owner, String name, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return false;
        var node = ClassResourceInspector.read(owner);
        if (node == null) return false;
        if (node.fields.stream().anyMatch(field -> field.name.equals(name))) return true;
        if (canResolveField(node.superName, name, visited)) return true;
        for (String iface : node.interfaces) {
            if (canResolveField(iface, name, visited)) return true;
        }
        return false;
    }

    private static int countParameters(String desc) {
        int count = 0;
        int i = 1;
        while (i < desc.length() && desc.charAt(i) != ')') {
            char c = desc.charAt(i);
            if (c == 'L') {
                int end = desc.indexOf(';', i);
                if (end < 0) break; // malformed (no ';'): indexOf+1 would reset i and spin forever
                i = end + 1;
                count++;
            } else if (c == '[') {
                i++;
            } else {
                i++;
                count++;
            }
        }
        return count;
    }

    private static void writeReport(VerifyResult result) {
        try {
            Path reportDir = Path.of("config/retromod/verify-reports");
            Files.createDirectories(reportDir);

            String safeName = result.modName().replaceAll("[^a-zA-Z0-9._-]", "_");
            Path reportFile = reportDir.resolve(safeName + ".txt");

            StringBuilder sb = new StringBuilder();
            sb.append("Retromod transform check\n\n");
            sb.append("Mod:     ").append(result.modName()).append('\n');
            sb.append("Target:  MC ").append(result.targetVersion()).append('\n');
            sb.append("Time:    ").append(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            ).append('\n');
            sb.append("Result:  ").append(result.passed() ? "passed" : "needs review").append('\n');
            sb.append('\n');

            if (result.passed()) {
                sb.append("No unresolved bytecode references were found.\n");
            } else {
                sb.append("Found ").append(result.issueCount()).append(" issue(s):\n\n");

                Map<IssueType, List<Issue>> byType = new LinkedHashMap<>();
                for (Issue issue : result.issues()) {
                    byType.computeIfAbsent(issue.type(), k -> new ArrayList<>()).add(issue);
                }

                for (var entry : byType.entrySet()) {
                    sb.append(entry.getKey().label).append('\n');
                    for (Issue issue : entry.getValue()) {
                        sb.append("  - ").append(issue.toReadableString(result.targetVersion()))
                          .append('\n');
                    }
                    sb.append('\n');
                }
            }

            Files.writeString(reportFile, sb.toString());
            LOGGER.info("Wrote the transformation report to {}", reportFile);

        } catch (Exception e) {
            LOGGER.debug("Could not write the transformation report: {}", e.getMessage());
        }
    }

    public enum IssueType {
        VERIFICATION_INCOMPLETE("Verification Incomplete"),
        MISSING_CLASS("Missing Classes"),
        MISSING_METHOD("Missing Methods"),
        MISSING_FIELD("Missing Fields"),
        MISSING_CONSTRUCTOR("Missing Constructors");

        final String label;
        IssueType(String label) { this.label = label; }
    }

    public record Issue(IssueType type, String owner, String name, String descriptor) {
        public String toReadableString(String targetVersion) {
            String ownerDot = owner.replace('/', '.');
            return switch (type) {
                case VERIFICATION_INCOMPLETE ->
                        "Verification did not finish. Check that the mod jar is readable and within archive limits";
                case MISSING_CLASS -> ownerDot + " not found in MC " + targetVersion;
                case MISSING_METHOD -> ownerDot + "." + name + "() not found in MC " + targetVersion;
                case MISSING_FIELD -> ownerDot + "." + name + " not found in MC " + targetVersion;
                case MISSING_CONSTRUCTOR -> {
                    int params = descriptor != null ? countParameters(descriptor) : 0;
                    yield ownerDot + ".<init> with " + params + " params not found in MC " + targetVersion;
                }
            };
        }
    }

    public record VerifyResult(String modName, String targetVersion, List<Issue> issues) {
        public boolean passed() { return issues.isEmpty(); }
        public int issueCount() { return issues.size(); }
    }
}
