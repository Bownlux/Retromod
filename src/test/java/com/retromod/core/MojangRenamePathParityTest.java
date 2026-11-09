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
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
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

/** Every class-transform path gives a 1.21.1 NeoForge or Forge mod the host's names for renamed Minecraft members. */
class MojangRenamePathParityTest {

    private static final String HEIGHT = "net/minecraft/world/level/LevelHeightAccessor";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String TOAST_COMPONENT = "net/minecraft/client/gui/components/toasts/ToastComponent";
    private static final String RENAMED = "[getMinY, fovModifier, getMaxBuildHeight]";
    private static final String KEPT = "[getMinBuildHeight, fov, getMaxBuildHeight]";

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

    @ParameterizedTest(name = "{0} {1} on {2}")
    @CsvSource({"neoforge, runtime, 26.3", "neoforge, runtime, 1.21.4", "neoforge, transform, 26.3",
            "neoforge, batch, 26.3", "neoforge, aot, 26.3", "forge, runtime, 26.3", "forge, transform, 26.3",
            "forge, batch, 26.3", "forge, aot, 26.3"})
    @DisplayName("each path renames the mod's calls, fields and mixin selector, and its nested library's")
    void everyPathRenames(String loader, String path, String host) throws Exception {
        Path output = translate(loader, path, host, "1.21.1");

        assertEquals(Map.of("mod/Heights", RENAMED, "nested:lib/LibHeights", RENAMED,
                "mod/mixin/HeightMixin", "getMinY"), summary(output));
        assertEquals(TOAST_COMPONENT, toastMixinTarget(output),
                "a mixin on a renamed class stays inert, also after the embedder adds the height helper");
    }

    @ParameterizedTest(name = "{0} {1} on {2} for a {3} mod")
    @CsvSource({"neoforge, runtime, 1.21.1, 1.21.1", "neoforge, transform, 26.3, 1.21.2",
            "neoforge, runtime, 26.3, 1.21.11", "forge, batch, 26.3, 1.21.4"})
    @DisplayName("a host before the rename, or a mod built after it, keeps the old names")
    void olderHostsAndNewerModsKeepTheNames(String loader, String path, String host, String built) throws Exception {
        Path output = translate(loader, path, host, built);

        assertEquals(Map.of("mod/Heights", KEPT, "nested:lib/LibHeights", KEPT,
                "mod/mixin/HeightMixin", "getMinBuildHeight"), summary(output));
    }

    /** Translates a mod built for {@code built} for a {@code loader} host through one path. */
    private Path translate(String loader, String path, String host, String built) throws Exception {
        McReflect.setForceForge(loader.equals("forge"));
        McReflect.setForceNeoForge(loader.equals("neoforge"));
        ModVersionDetector.setDefaultPreferredLoader(loader);
        Path input = Files.createDirectories(dir.resolve("in")).resolve("heights.jar");
        Files.write(input, modJar(loader, built));
        Path out = Files.createDirectories(dir.resolve("out"));
        Path output = switch (path) {
            case "runtime" -> runtime(loader, host, input, out);
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

    /** Registers what the loader's entry point registers on {@code host}, then runs its mod transformer. */
    private Path runtime(String loader, String host, Path input, Path out) throws Exception {
        RetromodVersion.TARGET_MC_VERSION = host;
        transformer.clearRedirectsForTesting();
        if (loader.equals("forge")) {
            RetromodForge.registerTransformRedirects(transformer);
        } else {
            RetromodNeoForge.registerTransformRedirects(transformer);
        }
        return new ForgeModTransformer(host).transformMod(input, out);
    }

    /** The names each class's code reaches, for the mod, its nested library, and its mixin's selector. */
    private static Map<String, String> summary(Path output) throws Exception {
        Map<String, String> found = new TreeMap<>();
        try (JarFile jar = new JarFile(output.toFile())) {
            found.put("mod/Heights", names(read(jar, "mod/Heights")));
            ClassNode mixin = read(jar, "mod/mixin/HeightMixin");
            MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("onHeight")).findFirst().orElseThrow();
            AnnotationNode inject = handler.visibleAnnotations.get(0);
            found.put("mod/mixin/HeightMixin", String.valueOf(((List<?>) value(inject, "method")).get(0)));
            Path nested = Files.createTempFile("nested-", ".jar");
            try {
                JarEntry entry = jar.getJarEntry("META-INF/jarjar/lib.jar");
                assertNotNull(entry, "the nested library is missing from " + output.getFileName());
                Files.write(nested, jar.getInputStream(entry).readAllBytes());
                try (JarFile lib = new JarFile(nested.toFile())) {
                    found.put("nested:lib/LibHeights", names(read(lib, "lib/LibHeights")));
                }
            } finally {
                Files.deleteIfExists(nested);
            }
        }
        return found;
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        }
        throw new AssertionError("no " + key + " in " + annotation.desc);
    }

    /** The class the toast mixin targets in the output jar. */
    private static String toastMixinTarget(Path output) throws Exception {
        try (JarFile jar = new JarFile(output.toFile())) {
            AnnotationNode mixin = read(jar, "mod/mixin/ToastMixin").invisibleAnnotations.get(0);
            return ((Type) ((List<?>) value(mixin, "value")).get(0)).getInternalName();
        }
    }

    private static ClassNode read(JarFile jar, String className) throws Exception {
        JarEntry entry = jar.getJarEntry(className + ".class");
        assertNotNull(entry, className + " is missing from " + jar.getName());
        ClassNode node = new ClassNode();
        new ClassReader(jar.getInputStream(entry).readAllBytes()).accept(node, 0);
        return node;
    }

    /** The method and field names {@code run} uses. */
    private static String names(ClassNode node) {
        MethodNode run = node.methods.stream().filter(m -> m.name.equals("run")).findFirst().orElseThrow();
        List<String> names = new ArrayList<>();
        for (AbstractInsnNode insn : run.instructions) {
            if (insn instanceof MethodInsnNode call) names.add(call.name);
            if (insn instanceof FieldInsnNode field) names.add(field.name);
        }
        return names.toString();
    }

    /** A mod built for {@code built} that reads both members, with a mixin and a nested library that reads them too. */
    private static byte[] modJar(String loader, String built) throws Exception {
        Map<String, byte[]> lib = new LinkedHashMap<>();
        lib.put(metadataPath(loader), metadata(loader, "heightlib", built));
        lib.put("lib/LibHeights.class", heightsClass("lib/LibHeights"));
        Map<String, byte[]> mod = new LinkedHashMap<>();
        mod.put(metadataPath(loader), metadata(loader, "heights", built));
        mod.put("mod/Heights.class", heightsClass("mod/Heights"));
        mod.put("mod/mixin/HeightMixin.class", mixinClass());
        mod.put("mod/mixin/ToastMixin.class", toastMixinClass());
        mod.put("heights.mixins.json", ("{\"required\":true,\"package\":\"mod.mixin\","
                + "\"mixins\":[\"HeightMixin\",\"ToastMixin\"]}").getBytes(StandardCharsets.UTF_8));
        mod.put("META-INF/jarjar/lib.jar", LoaderApiFixtures.jar(lib));
        return LoaderApiFixtures.jar(mod);
    }

    private static String metadataPath(String loader) {
        return loader.equals("forge") ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml";
    }

    private static byte[] metadata(String loader, String modId, String built) {
        String next = built.startsWith("1.21") ? "1.22" : "27";
        String loaderDependency = loader.equals("forge") ? "mandatory=true\n" : "type=\"required\"\n";
        String text = "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\nlicense=\"MIT\"\n[[mods]]\nmodId=\""
                + modId + "\"\nversion=\"1.0.0\"\n[[mixins]]\nconfig=\"heights.mixins.json\"\n[[dependencies." + modId
                + "]]\nmodId=\"minecraft\"\n" + loaderDependency + "versionRange=\"[" + built + "," + next + ")\"\n"
                + "ordering=\"NONE\"\nside=\"BOTH\"\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** {@code static void run(LevelHeightAccessor, GameRenderer)} reading the floor and the renderer's fov. */
    private static byte[] heightsClass(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run",
                "(L" + HEIGHT + ";L" + GAME_RENDERER + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HEIGHT, "getMinBuildHeight", "()I", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.GETFIELD, GAME_RENDERER, "fov", "F");
        mv.visitInsn(Opcodes.POP);
        // The old exclusive ceiling goes to a helper the embedder copies into the jar.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HEIGHT, "getMaxBuildHeight", "()I", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mixin on ToastComponent, which 1.21.2 renamed to ToastManager and reworked. */
    private static byte[] toastMixinClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/ToastMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(TOAST_COMPONENT));
        targets.visitEnd();
        mixin.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A mixin on LevelHeightAccessor whose handler injects into getMinBuildHeight by its bare name. */
    private static byte[] mixinClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "mod/mixin/HeightMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(HEIGHT));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "onHeight",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
        AnnotationVisitor inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "getMinBuildHeight");
        methods.visitEnd();
        AnnotationVisitor ats = inject.visitArray("at");
        AnnotationVisitor at = ats.visitAnnotation(null, "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "HEAD");
        at.visitEnd();
        ats.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 2);
        handler.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
