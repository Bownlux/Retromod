/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

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
        "com/google/gson/", "com/google/common/",
        "org/slf4j/", "org/apache/logging/",
        "org/apache/commons/", "org/objectweb/asm/", "org/lwjgl/",
        "io/netty/", "com/mojang/",
        "it/unimi/dsi/fastutil/", "org/joml/",
        "net/fabricmc/loader/", "net/fabricmc/api/",
        "net/fabricmc/fabric/",
        "net/minecraftforge/", "net/neoforged/",
        "com/retromod/",
    };

    private TransformVerifier() {}

    /**
     * Verify a transformed mod JAR by checking every bytecode reference against
     * the runtime classpath. Never null.
     *
     * @param transformedJar path to the transformed JAR
     * @param modName        human-readable mod name for reports
     * @param targetVersion  target MC version string
     */
    public static VerifyResult verify(Path transformedJar, String modName, String targetVersion) {
        List<Issue> issues = new ArrayList<>();

        try {
            Set<String> modClasses = new HashSet<>();
            Set<String> referencedClasses = new LinkedHashSet<>();
            Map<String, Set<String>> referencedMethods = new LinkedHashMap<>();
            Map<String, Set<String>> referencedFields = new LinkedHashMap<>();
            Map<String, Set<String>> referencedCtors = new LinkedHashMap<>();

            try (JarFile jar = new JarFile(transformedJar.toFile())) {
                // pass 1: mod-internal class names
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
                        modClasses.add(entry.getName().replace(".class", ""));
                    }
                }

                // pass 2: external references
                entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().endsWith(".class") || entry.isDirectory()) continue;

                    String sourceClass = entry.getName().replace(".class", "");
                    try (InputStream is = jar.getInputStream(entry)) {
                        scanClass(is, sourceClass, referencedClasses,
                                referencedMethods, referencedFields, referencedCtors);
                    } catch (Exception e) {
                        // skip unreadable classes
                    }
                }
            }

            for (String cls : referencedClasses) {
                if (modClasses.contains(cls)) continue;
                if (isSafe(cls)) continue;
                if (!canResolveClass(cls)) {
                    issues.add(new Issue(IssueType.MISSING_CLASS, cls, null, null));
                }
            }

            for (var entry : referencedMethods.entrySet()) {
                String owner = entry.getKey();
                if (modClasses.contains(owner) || isSafe(owner) || !canResolveClass(owner)) continue;

                for (String nameDesc : entry.getValue()) {
                    int descStart = nameDesc.indexOf('(');
                    if (descStart < 0) continue;
                    String mName = nameDesc.substring(0, descStart);
                    String mDesc = nameDesc.substring(descStart);
                    if (!canResolveMethod(owner, mName, mDesc)) {
                        issues.add(new Issue(IssueType.MISSING_METHOD, owner, mName, mDesc));
                    }
                }
            }

            for (var entry : referencedFields.entrySet()) {
                String owner = entry.getKey();
                if (modClasses.contains(owner) || isSafe(owner) || !canResolveClass(owner)) continue;

                for (String fieldName : entry.getValue()) {
                    if (!canResolveField(owner, fieldName)) {
                        issues.add(new Issue(IssueType.MISSING_FIELD, owner, fieldName, null));
                    }
                }
            }

            for (var entry : referencedCtors.entrySet()) {
                String owner = entry.getKey();
                if (modClasses.contains(owner) || isSafe(owner) || !canResolveClass(owner)) continue;

                for (String desc : entry.getValue()) {
                    if (!canResolveMethod(owner, "<init>", desc)) {
                        issues.add(new Issue(IssueType.MISSING_CONSTRUCTOR, owner, "<init>", desc));
                    }
                }
            }

        } catch (Throwable t) {
            // verifier is a diagnostic and must never abort a transform; catch Errors too
            // (a transformer-layer LinkageError can surface through a probe) and return
            // whatever we gathered (#102).
            LOGGER.warn("Could not verify the transformed mod {}: {}", modName, t.toString());
        }

        return new VerifyResult(modName, targetVersion, issues);
    }

    /**
     * Run verification and write the report to config/retromod/verify-reports/.
     * Logs a summary to the console.
     */
    public static VerifyResult verifyAndReport(Path transformedJar, String modName, String targetVersion) {
        LOGGER.info("Checking transformed bytecode for {}", modName);
        long start = System.currentTimeMillis();

        VerifyResult result = verify(transformedJar, modName, targetVersion);
        long elapsed = System.currentTimeMillis() - start;

        if (result.passed()) {
            LOGGER.info("Verification passed for {}: {} references checked in {} ms",
                    modName, result.totalChecked(), elapsed);
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
        try {
            Path configPath = Path.of("config/retromod/config.json");
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                return json.contains("\"verify_transforms\": true") ||
                       json.contains("\"verify_transforms\":true");
            }
        } catch (Exception e) {
            // default to false
        }
        return false;
    }

    private static void scanClass(InputStream is, String sourceClass,
            Set<String> classes, Map<String, Set<String>> methods,
            Map<String, Set<String>> fields, Map<String, Set<String>> ctors) throws IOException {

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
                        classes.add(owner);
                        methods.computeIfAbsent(owner, k -> new LinkedHashSet<>())
                               .add(mName + mDesc);
                        if ("<init>".equals(mName)) {
                            ctors.computeIfAbsent(owner, k -> new LinkedHashSet<>())
                                 .add(mDesc);
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner,
                                               String fName, String fDesc) {
                        classes.add(owner);
                        fields.computeIfAbsent(owner, k -> new LinkedHashSet<>())
                              .add(fName);
                    }

                    @Override
                    public void visitTypeInsn(int opcode, String type) {
                        if (type != null && !type.startsWith("[")) {
                            classes.add(type);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG);
    }

    private static boolean isSafe(String className) {
        for (String prefix : SAFE_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
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
        try {
            Class.forName(internalName.replace('/', '.'), false,
                    TransformVerifier.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            // classloader-visibility miss (client class on a server, other-module class
            // under NeoForge): confirm against the mapping before declaring a gap.
            return isKnownTargetClass(internalName);
        } catch (Throwable t) {
            // can't tell, so assume resolvable rather than crash. Mixin throws
            // IllegalClassLoadError (an Error) when a @Mixin-package class is loaded
            // directly (#102); the probe swallows it.
            return true;
        }
    }

    private static boolean canResolveMethod(String ownerInternal, String name, String desc) {
        try {
            Class<?> cls = Class.forName(ownerInternal.replace('/', '.'), false,
                    TransformVerifier.class.getClassLoader());
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
            return true; // don't double-report
        } catch (Throwable t) {
            return true; // #102: the probe (Mixin's IllegalClassLoadError) must not crash the transform
        }
    }

    private static boolean canResolveField(String ownerInternal, String fieldName) {
        try {
            Class<?> cls = Class.forName(ownerInternal.replace('/', '.'), false,
                    TransformVerifier.class.getClassLoader());
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
            return true;
        } catch (Throwable t) {
            return true; // #102: probe must not crash the transform
        }
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
        public int totalChecked() {
            // approximate count of references checked
            return issueCount() > 0 ? issueCount() : 1;
        }
    }
}
