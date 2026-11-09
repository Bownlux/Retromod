/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.loaderapi.CliHarness;
import com.retromod.loaderapi.LoaderApiFixtures;
import com.retromod.util.McReflect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Fabric mod built before 1.21.2 keeps the intermediary ids of the max build height getters and the
 * map color, which now name the inclusive getters and the ARGB color. Every path sends them to helpers.
 */
class FabricMeaningChangePathParityTest {

    private static final String HEIGHT = "net/minecraft/class_5539";
    private static final String MAP_COLOR = "net/minecraft/class_3620";
    private static final String BRIGHTNESS_DESC = "(Lnet/minecraft/class_3620$class_6594;)I";

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;
    private final boolean savedForge = McReflect.isForceForge();
    private final boolean savedNeoForge = McReflect.isForceNeoForge();

    @TempDir
    Path dir;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        McReflect.setForceForge(savedForge);
        McReflect.setForceNeoForge(savedNeoForge);
        ModVersionDetector.setDefaultPreferredLoader(null);
        transformer.setModSourceVersion(null);
        transformer.clearRedirectsForTesting();
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"runtime, 26.3", "runtime, 1.21.11", "runtime, 1.21.4", "transform, 26.3", "batch, 26.3", "aot, 26.3",
            "transform, 1.21.11"})
    @DisplayName("each path sends a 1.21.1 mod's calls, and its nested library's, to the helpers")
    void everyPathUsesTheHelpers(String path, String host) throws Exception {
        Path output = translate(path, host, "1.21.1");

        String helpers = "[getMaxBuildHeight, getMaxSection, calculateRGBColor] on the embedded helpers";
        assertEquals(Map.of("mod/Heights", helpers, "nested:lib/LibHeights", helpers), summary(output));
        // The mod's own exclusive override keeps its old name, and the class answers the host's getter.
        String hostGetter = host.startsWith("26") ? "getMaxY" : "method_31600";
        assertEquals(List.of("getMaxBuildHeight", hostGetter), heightMethods(output));
    }

    @ParameterizedTest(name = "{0} on {1}")
    @CsvSource({"runtime, 26.3", "transform, 26.3", "runtime, 1.21.11"})
    @DisplayName("a mod built for 1.21.2 or newer means the inclusive getters and ARGB, so it keeps them")
    void newerModsKeepTheHostMembers(String path, String host) throws Exception {
        Path output = translate(path, host, "1.21.4");

        String kept = host.startsWith("26")
                ? "[getMaxY, getMaxSectionY, calculateARGBColor] with calls to the host"
                : "[method_31600, method_31597, method_15820] with calls to the host";
        assertEquals(Map.of("mod/Heights", kept, "nested:lib/LibHeights", kept), summary(output));
        assertEquals(List.of(host.startsWith("26") ? "getMaxY" : "method_31600"), heightMethods(output),
                "a newer mod's override already means the inclusive getter");
    }

    /** The height getters {@code mod/FlatWorld} declares in the output, in declaration order. */
    private static List<String> heightMethods(Path output) throws Exception {
        try (JarFile jar = new JarFile(output.toFile())) {
            JarEntry entry = jar.getJarEntry("mod/FlatWorld.class");
            assertNotNull(entry, "mod/FlatWorld is missing from " + output.getFileName());
            ClassNode node = new ClassNode();
            new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
            List<String> names = new ArrayList<>();
            for (MethodNode method : node.methods) {
                if (method.desc.equals("()I") && !method.name.startsWith("<")) names.add(method.name);
            }
            return names;
        }
    }

    /** Translates a Fabric mod built for {@code built} for {@code host} through one path. */
    private Path translate(String path, String host, String built) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("heights.jar");
        Files.write(input, modJar(built));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> {
                RetromodVersion.TARGET_MC_VERSION = host;
                transformer.clearRedirectsForTesting();
                RetromodPreLaunch.registerShimsForTransform(host);
                yield new FabricModTransformer(host).transformMod(input, out);
            }
            case "transform" -> {
                CliHarness.transform(input, out.resolve("heights.jar"), host);
                yield out.resolve("heights.jar");
            }
            case "aot" -> {
                CliHarness.aot(input, out.resolve("heights.jar"), host);
                yield out.resolve("heights.jar");
            }
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(jar -> jar.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    yield all.get(0);
                }
            }
        };
        assertNotNull(output, path + " must produce a jar");
        assertTrue(Files.isRegularFile(output), path + " must produce " + output);
        return output;
    }

    /** The names {@code run} calls in the mod and its nested library, and whether they reach helpers in the jar. */
    private static Map<String, String> summary(Path output) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/Heights", describe(jar, "mod/Heights"));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry("META-INF/jars/lib.jar");
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibHeights", describe(lib, "lib/LibHeights"));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static String describe(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        MethodNode run = node.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        List<String> names = new ArrayList<>();
        boolean allHelpers = true;
        boolean noHelpers = true;
        for (AbstractInsnNode insn : run.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            names.add(call.name);
            boolean helper = call.owner.startsWith(SyntheticEmbedder.PREFIX)
                    && (call.owner.contains("/LegacyBuildHeight") || call.owner.contains("/LegacyMapColors"))
                    && jar.getJarEntry(call.owner + ".class") != null;
            allHelpers &= helper;
            noHelpers &= !helper;
        }
        String where = allHelpers ? " on the embedded helpers" : noHelpers ? " with calls to the host" : " mixed";
        return names + where;
    }

    /** A Fabric mod built for {@code built} that reads both heights and a map color, nesting a library that does the same. */
    private static byte[] modJar(String built) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("fabric.mod.json", metadata("heightlib", built, false));
        lib.put("lib/LibHeights.class", heightsClass("lib/LibHeights"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("fabric.mod.json", metadata("heights", built, true));
        mod.put("mod/Heights.class", heightsClass("mod/Heights"));
        mod.put("mod/FlatWorld.class", flatWorldClass());
        mod.put("META-INF/jars/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] metadata(String modId, String built, boolean nestsLibrary) {
        return ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"" + built + "\",\"fabricloader\":\">=0.15.0\"}"
                + (nestsLibrary ? ",\"jars\":[{\"file\":\"META-INF/jars/lib.jar\"}]" : "") + "}")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** {@code abstract class FlatWorld extends Level} with its own exclusive {@code getMaxBuildHeight()} of 320. */
    private static byte[] flatWorldClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/FlatWorld", null,
                "net/minecraft/class_1937", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_31600", "()I", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 320);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code static void run(LevelHeightAccessor, MapColor, Brightness)} in intermediary names. */
    private static byte[] heightsClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(L" + HEIGHT + ";L" + MAP_COLOR + ";Lnet/minecraft/class_3620$class_6594;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HEIGHT, "method_31600", "()I", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HEIGHT, "method_31597", "()I", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MAP_COLOR, "method_15820", BRIGHTNESS_DESC, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
