/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common.embedded;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.polyfill.forge.embedded.GameRegistryShim;
import com.retromod.shim.forge.embedded.LegacyBrewingStore;
import com.retromod.shim.forge.embedded.LegacyEventCalls;
import com.retromod.shim.forge.embedded.LegacyRarities;
import com.retromod.shim.forge.embedded.LegacyTradeStore;
import com.retromod.shim.forge.embedded.LegacyWorldgenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Helpers copied into old mods log through LegacyLog, which reaches latest.log on Forge and NeoForge,
 * where standard output and standard error never do.
 */
class LegacyLogTest {

    private static final String LEGACY_LOG = internalName(LegacyLog.class);

    /** The helpers that old mods carry, plus the GameRegistry stand-in that runs from Retromod's jar. */
    private static final List<Class<?>> LOGGING_HELPERS = List.of(
            LegacyTradeStore.class, LegacyBrewingStore.class, LegacyWorldgenStore.class,
            LegacyRarities.class, LegacyEventCalls.class, LegacyEquipmentSupport.class,
            LegacyArmorTrims.class, GameRegistryShim.class);

    @Test
    @DisplayName("LegacyLog names only JDK classes, so a copy inside a mod links on every loader")
    void referencesOnlyTheJdk() throws IOException {
        Set<String> outside = new HashSet<>();
        for (String name : referencedClasses(classBytes(LegacyLog.class))) {
            if (!name.startsWith("java/") && !name.equals(LEGACY_LOG)) outside.add(name);
        }
        assertTrue(outside.isEmpty(), "a copied helper cannot reach Retromod's jar or any library: " + outside);
    }

    @Test
    @DisplayName("LegacyLog carries no SLF4J name that the release jar's relocation would rewrite")
    void noShadeVulnerableConstants() throws IOException {
        List<String> hits = new ArrayList<>();
        for (String constant : stringConstants(classBytes(LegacyLog.class))) {
            for (String pattern : List.of("org/slf4j", "org.slf4j", "com/retromod/shaded", "com.retromod.shaded")) {
                if (constant.contains(pattern)) hits.add(constant);
            }
        }
        assertTrue(hits.isEmpty(), "maven-shade would point these at Retromod's own SLF4J, which writes to "
                + "standard error: " + hits);
    }

    @Test
    @DisplayName("warnOnce logs a key the first time only")
    void warnOnceLogsOnce() throws Exception {
        String output = captureErr(() -> {
            assertTrue(LegacyLog.warnOnce("LegacyLogTest:once", "first note"));
            assertFalse(LegacyLog.warnOnce("LegacyLogTest:once", "second note"));
        });

        assertTrue(output.contains("[Retromod] first note"), "the first call logs: " + output);
        assertFalse(output.contains("second note"), "the second call for the key stays quiet: " + output);
    }

    @Test
    @DisplayName("with SLF4J present, a warning goes through it rather than straight to standard error")
    void usesSlf4jWhenPresent() throws Exception {
        String output = captureErr(() -> LegacyLog.warn("routed note"));

        // The test classpath binds SLF4J to slf4j-simple, which marks the level and logger name.
        assertTrue(output.contains("WARN Retromod - [Retromod] routed note"),
                "the line must come from the Retromod SLF4J logger: " + output);
    }

    @Test
    @DisplayName("without SLF4J, a warning still prints to standard error and never throws")
    void fallsBackWithoutSlf4j() throws Exception {
        byte[] bytes = classBytes(LegacyLog.class);
        // A loader that sees only the JDK, as on a host where SLF4J cannot be found.
        Class<?> isolated = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            Class<?> define() {
                return defineClass(LegacyLog.class.getName(), bytes, 0, bytes.length);
            }
        }.define();
        Method warn = isolated.getMethod("warn", String.class);

        String output = captureErr(() -> warn.invoke(null, "fallback note"));

        assertEquals("[Retromod] fallback note", output.strip());
    }

    @Test
    @DisplayName("debug goes to the host SLF4J logger's debug level, and to standard error only without SLF4J")
    void debugReachesTheDebugLevel(@TempDir Path dir) throws Exception {
        // A host SLF4J that records each call with its level, in a loader with only the JDK besides.
        Path src = Files.createDirectories(dir.resolve("src/org/slf4j"));
        Files.writeString(src.resolve("Logger.java"), """
                package org.slf4j;
                public interface Logger { void warn(String line); void info(String line); void debug(String line); }""");
        Files.writeString(src.resolve("LoggerFactory.java"), """
                package org.slf4j;
                public final class LoggerFactory {
                    public static final java.util.List<String> LINES = new java.util.ArrayList<>();
                    public static Logger getLogger(String name) {
                        return new Logger() {
                            public void warn(String line) { LINES.add("WARN " + name + " " + line); }
                            public void info(String line) { LINES.add("INFO " + name + " " + line); }
                            public void debug(String line) { LINES.add("DEBUG " + name + " " + line); }
                        };
                    }
                }""");
        Path out = Files.createDirectories(dir.resolve("out"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(),
                "--release", "17", src.resolve("Logger.java").toString(), src.resolve("LoggerFactory.java").toString()));
        byte[] bytes = classBytes(LegacyLog.class);
        ClassLoader host = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                try {
                    if (name.equals(LegacyLog.class.getName())) return defineClass(name, bytes, 0, bytes.length);
                    byte[] stand = Files.readAllBytes(out.resolve(name.replace('.', '/') + ".class"));
                    return defineClass(name, stand, 0, stand.length);
                } catch (IOException missing) {
                    throw new ClassNotFoundException(name);
                }
            }
        };
        Class<?> isolated = Class.forName(LegacyLog.class.getName(), true, host);

        String output = captureErr(() -> isolated.getMethod("debug", String.class).invoke(null, "quiet note"));

        assertEquals("", output, "nothing reaches standard error while SLF4J is there");
        assertEquals(List.of("DEBUG Retromod [Retromod] quiet note"),
                Class.forName("org.slf4j.LoggerFactory", true, host).getField("LINES").get(null));
    }

    @Test
    @DisplayName("without SLF4J, a debug line still prints to standard error and never throws")
    void debugFallsBackWithoutSlf4j() throws Exception {
        byte[] bytes = classBytes(LegacyLog.class);
        Class<?> isolated = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            Class<?> define() {
                return defineClass(LegacyLog.class.getName(), bytes, 0, bytes.length);
            }
        }.define();

        String output = captureErr(() -> isolated.getMethod("debug", String.class).invoke(null, "fallback debug"));

        assertEquals("[Retromod] fallback debug", output.strip());
    }

    @Test
    @DisplayName("a registered helper that logs brings LegacyLog into the same embedded package")
    void helperBringsLegacyLogWhenEmbedded(@TempDir Path mod) throws Exception {
        String store = internalName(LegacyTradeStore.class);
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        try {
            assertTrue(SyntheticEmbedder.registerClassResource(transformer, store, LegacyTradeStore.class));
            assertTrue(transformer.getSyntheticClasses().containsKey(LEGACY_LOG),
                    "registering the store must register the logger it calls");
            writeClass(mod, "oldmod/Trades", classWithField("oldmod/Trades", store));

            SyntheticEmbedder.embed(mod, "oldmod.jar", transformer);

            String base = SyntheticEmbedder.embeddedBase("oldmod.jar");
            assertTrue(Files.isRegularFile(mod.resolve(base + LEGACY_LOG + ".class")),
                    "LegacyLog must be copied next to the store, or its first note fails to link");
            Set<String> storeRefs = referencedClasses(Files.readAllBytes(mod.resolve(base + store + ".class")));
            assertTrue(storeRefs.contains(base + LEGACY_LOG), "the store must call the mod's own copy");
            assertFalse(storeRefs.contains(LEGACY_LOG), "no call may be left on Retromod's runtime copy");
        } finally {
            transformer.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("helpers never print to standard output or standard error directly")
    void helpersDoNotPrintDirectly() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Class<?> helper : LOGGING_HELPERS) {
            for (Path file : classAndNestedFiles(helper)) {
                scanForDirectPrinting(file.getFileName().toString(), Files.readAllBytes(file), hits);
            }
        }
        assertTrue(hits.isEmpty(), "Forge and NeoForge never copy these streams into latest.log; "
                + "use LegacyLog instead:\n" + String.join("\n", hits));
    }

    // --- helpers ------------------------------------------------------------------------------

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static String captureErr(ThrowingRunnable action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        try (InputStream in = type.getClassLoader().getResourceAsStream(internalName(type) + ".class")) {
            assertNotNull(in, "missing class file for " + type.getName());
            return in.readAllBytes();
        }
    }

    /** The compiled class and every class nested in it. */
    private static List<Path> classAndNestedFiles(Class<?> type) throws Exception {
        Path classes = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path outer = classes.resolve(internalName(type) + ".class");
        String nestedPrefix = type.getSimpleName() + "$";
        try (Stream<Path> siblings = Files.list(outer.getParent())) {
            List<Path> files = new ArrayList<>(siblings
                    .filter(p -> p.getFileName().toString().startsWith(nestedPrefix))
                    .toList());
            files.add(outer);
            return files;
        }
    }

    private static Set<String> referencedClasses(byte[] bytes) {
        Set<String> names = new HashSet<>();
        new ClassReader(bytes).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
            @Override
            public String map(String internalName) {
                names.add(internalName);
                return internalName;
            }
        }), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return names;
    }

    private static List<String> stringConstants(byte[] bytes) {
        List<String> constants = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String text) constants.add(text);
                    }

                    // String concatenation keeps its fixed parts in the bootstrap recipe.
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String desc, Handle bsm, Object... args) {
                        for (Object arg : args) if (arg instanceof String text) constants.add(text);
                    }
                };
            }
        }, 0);
        return constants;
    }

    private static void scanForDirectPrinting(String file, byte[] bytes, List<String> hits) {
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name, String fieldDesc) {
                        if (opcode == Opcodes.GETSTATIC && owner.equals("java/lang/System")
                                && (name.equals("out") || name.equals("err"))) {
                            hits.add(file + " " + method + ": System." + name);
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String callDesc,
                            boolean isInterface) {
                        if (owner.equals("java/lang/System") && name.equals("getLogger")) {
                            hits.add(file + " " + method + ": System.getLogger");
                        }
                    }
                };
            }
        }, 0);
    }

    private static byte[] classWithField(String name, String fieldType) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "store", "L" + fieldType + ";", null, null).visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void writeClass(Path dir, String internalName, byte[] bytes) throws IOException {
        Path file = dir.resolve(internalName + ".class");
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }
}
