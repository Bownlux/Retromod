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
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/** Every Fabric class-transform path gives a 1.20.1 mod and its nested library the intermediary copies of the text stand-ins. */
class FabricTextJsonPathParityTest {

    private static final String NESTED = "META-INF/jars/lib.jar";
    private static final String SERIALIZER = "net/minecraft/class_2561$class_2562";
    private static final String STYLE_SERIALIZER = "net/minecraft/class_2583$class_2584";
    private static final String JSON = "com/retromod/legacyapi/intermediary/minecraft/network/chat/LegacyComponentJson";
    private static final String STYLE_JSON = "com/retromod/legacyapi/intermediary/minecraft/network/chat/LegacyStyleJson";
    private static final String NO_CONTEXT = "com/retromod/legacyapi/intermediary/minecraft/core/NoRegistryContext";

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
    @CsvSource({"runtime, 1.20.2", "runtime, 1.20.4", "runtime, 1.21.1", "runtime, 1.21.11", "game, 1.21.1",
            "transform, 1.20.4", "transform, 1.21.1", "transform, 1.21.11", "batch, 1.21.11", "aot, 1.20.2", "aot, 1.21.1",
            "aot, 1.21.11"})
    @DisplayName("the old Gson text adapters, a subclass and the old statics reach copies embedded in the jar that calls them")
    void everyPathReachesTheCopies(String path, String host) throws Exception {
        Path output = translate(path, host);
        boolean repaired = RetromodVersion.compareMcVersions(host, "1.20.3") >= 0;
        try (JarFile outer = new JarFile(output.toFile())) {
            check(outer, "mod", repaired, path + " on " + host);
            Path nested = Files.createTempFile(dir, "nested-", ".jar");
            JarEntry entry = outer.getJarEntry(NESTED);
            assertNotNull(entry, "the nested library is missing from " + output.getFileName());
            Files.write(nested, outer.getInputStream(entry).readAllBytes());
            try (JarFile lib = new JarFile(nested.toFile())) {
                check(lib, "lib", repaired, path + " on " + host + ", nested");
            }
        }
    }

    /** The adapters, the subclass and the statics of one jar, against the copies that jar carries. */
    private static void check(JarFile jar, String pkg, boolean repaired, String where) throws Exception {
        MethodNode make = method(read(jar, pkg + "/Texts"), "make");
        List<String> built = new ArrayList<>();
        for (AbstractInsnNode insn : make.instructions) {
            if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) built.add(type.desc);
        }
        String parent = read(jar, pkg + "/Custom").superName;
        List<MethodInsnNode> calls = calls(make);
        if (!repaired) {
            assertEquals(List.of(SERIALIZER, STYLE_SERIALIZER), built, where + ": the host's adapters stay");
            assertEquals(SERIALIZER, parent, where);
            return;
        }
        assertEmbedded(jar, built.get(0), JSON, where + ": the text adapter");
        assertEmbedded(jar, built.get(1), STYLE_JSON, where + ": the style adapter");
        assertEmbedded(jar, parent, JSON, where + ": the subclass's parent");
        assertEmbedded(jar, calls.get(0).owner, JSON, where + ": toJsonTree, which 1.20.5 made private");
        assertEquals("method_10868", calls.get(0).name, where);
        // On 1.20.5 to 1.21.5 too, so a number reads as its text and plain text writes as before.
        assertEmbedded(jar, calls.get(1).owner, JSON, where + ": fromJson");
        assertEquals("method_10877", calls.get(1).name, where);
        String root = built.get(0).substring(0, built.get(0).length() - JSON.length());
        assertNotNull(jar.getJarEntry(root + NO_CONTEXT + ".class"), where + ": the copy's empty registry context");
        for (JarEntry entry : Collections.list(jar.entries())) {
            assertFalse(entry.getName().contains("LegacyRegistryContext"), where + ": a Fabric jar carries " + entry.getName());
        }
    }

    private static void assertEmbedded(JarFile jar, String owner, String copy, String where) {
        assertTrue(owner.startsWith(SyntheticEmbedder.PREFIX) && owner.endsWith(copy), where + ": " + owner);
        assertNotNull(jar.getJarEntry(owner + ".class"), where + ": " + owner + " is not in " + jar.getName());
    }

    /** Translates the mod for a Fabric host through one path and returns the output jar. */
    private Path translate(String path, String host) throws Exception {
        McReflect.setForceForge(false);
        McReflect.setForceNeoForge(false);
        ModVersionDetector.setDefaultPreferredLoader(null);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("texts.jar");
        Files.write(input, modJar());
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = out.resolve("texts.jar");
        switch (path) {
            case "runtime" -> {
                RetromodVersion.TARGET_MC_VERSION = host;
                transformer.clearRedirectsForTesting();
                RetromodPreLaunch.registerShimsForTransform(host);
                output = new FabricModTransformer(host).transformMod(input, out);
            }
            case "game" -> output = inGame(host, input, out);
            case "transform" -> CliHarness.transform(input, output, host);
            case "aot" -> CliHarness.aot(input, output, host);
            default -> {
                CliHarness.batch(input.getParent(), out, host);
                try (var jars = Files.list(out)) {
                    List<Path> all = jars.filter(jar -> jar.toString().endsWith(".jar")).toList();
                    assertEquals(1, all.size(), "the batch wrote one jar: " + all);
                    output = all.get(0);
                }
            }
        }
        assertTrue(output != null && Files.isRegularFile(output), path + " must produce a jar");
        return output;
    }

    /** The runtime path with the host's serializers readable, as in the game, where the registry-passing helper probes them too. */
    private Path inGame(String host, Path input, Path out) throws Exception {
        Path game = dir.resolve("host.jar");
        Files.write(game, LoaderApiFixtures.jar(Map.of(
                "net/minecraft/class_8824.class", hostClass("net/minecraft/class_8824", false),
                SERIALIZER + ".class", hostClass(SERIALIZER, true),
                STYLE_SERIALIZER + ".class", hostClass(STYLE_SERIALIZER, false))));
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        try (java.net.URLClassLoader hostClasses = new java.net.URLClassLoader(new java.net.URL[] {game.toUri().toURL()}, null)) {
            thread.setContextClassLoader(hostClasses);
            RetromodVersion.TARGET_MC_VERSION = host;
            transformer.clearRedirectsForTesting();
            // Alone, the helper answers this host, so the row shows the copy is what moves it aside.
            com.retromod.shim.fabric.Pre1_20_5ComponentJsonBridge.register(transformer);
            assertFalse(transformer.getMethodRedirects().isEmpty(), "the helper reads this host's serializer");
            transformer.clearRedirectsForTesting();
            RetromodPreLaunch.registerShimsForTransform(host);
            assertTrue(com.retromod.shim.common.LegacyComponentJsonBridge.answersIntermediaryCalls(transformer));
            return new FabricModTransformer(host).transformMod(input, out);
        } finally {
            thread.setContextClassLoader(saved);
        }
    }

    /** A 1.21.1 host class for reading only, with the registry-taking statics when it is the text serializer. */
    private static byte[] hostClass(String name, boolean serializer) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        String provider = "Lnet/minecraft/class_7225$class_7874;";
        String[][] statics = {{"method_10877", "(Ljava/lang/String;" + provider + ")Lnet/minecraft/class_5250;"},
                {"method_10872", "(Lcom/google/gson/JsonElement;" + provider + ")Lnet/minecraft/class_5250;"},
                {"method_10873", "(Ljava/lang/String;" + provider + ")Lnet/minecraft/class_5250;"},
                {"method_10867", "(Lnet/minecraft/class_2561;" + provider + ")Ljava/lang/String;"}};
        if (serializer) {
            for (String[] method : statics) {
                cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method[0], method[1], null, null).visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) calls.add(call);
        }
        return calls;
    }

    private static ClassNode read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(node.name + " has no " + name));
    }

    /** A Fabric 1.20.1 mod with the text code, nesting a library with the same. */
    private static byte[] modJar() throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put("fabric.mod.json", metadata("textlib", false));
        lib.put("lib/Texts.class", texts("lib/Texts"));
        lib.put("lib/Custom.class", custom("lib/Custom"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put("fabric.mod.json", metadata("texts", true));
        mod.put("mod/Texts.class", texts("mod/Texts"));
        mod.put("mod/Custom.class", custom("mod/Custom"));
        mod.put(NESTED, LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static byte[] metadata(String modId, boolean nestsLibrary) {
        return ("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1.0.0\","
                + "\"depends\":{\"minecraft\":\"1.20.1\",\"fabricloader\":\">=0.14.0\"}"
                + (nestsLibrary ? ",\"jars\":[{\"file\":\"" + NESTED + "\"}]" : "") + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** {@code make()} builds both Gson adapters, then calls toJsonTree and fromJson, in 1.20.1's intermediary names. */
    private static byte[] texts(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "make", "()V", null, null);
        mv.visitCode();
        for (String type : List.of(SERIALIZER, STYLE_SERIALIZER)) {
            mv.visitTypeInsn(Opcodes.NEW, type);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERIALIZER, "method_10868",
                "(Lnet/minecraft/class_2561;)Lcom/google/gson/JsonElement;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitLdcInsn("{}");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, SERIALIZER, "method_10877", "(Ljava/lang/String;)Lnet/minecraft/class_5250;", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code class Custom extends Component.Serializer}, as CreativeCore's text adapter is. */
    private static byte[] custom(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, SERIALIZER, null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, SERIALIZER, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
