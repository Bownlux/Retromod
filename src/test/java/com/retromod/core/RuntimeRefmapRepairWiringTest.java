/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.retromod.mapping.IntermediaryToMojangMapper;
import com.retromod.mixin.MixinCompatibilityTransformer;
import com.retromod.mixin.MixinHandlerResignature;
import com.retromod.mixin.MixinRefmapRepairIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that loader runtime transforms carry refmap facts into the Mixin class pass. */
class RuntimeRefmapRepairWiringTest {

    private static final String TARGET_VERSION = "26.2";
    private static final String TARGET = "net/minecraft/test/RuntimeRefmapTarget";
    private static final String MIXIN = "com/example/mixin/RuntimeMixin";
    private static final String MIXIN_DOTTED = "com.example.mixin.RuntimeMixin";
    private static final String MIXIN_ENTRY = MIXIN + ".class";
    private static final String CONFIG_ENTRY = "runtime.mixins.json";
    private static final String REFMAP_ENTRY = "runtime-refmap.json";
    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String SOURCE_SELECTOR = "sourceTick(Ljava/lang/String;)V";
    private static final String OLD_TARGET_DESCRIPTOR = "(Ljava/lang/String;)V";
    private static final String NEW_TARGET_DESCRIPTOR = "(Ljava/lang/String;I)V";
    private static final String OLD_TARGET_SELECTOR =
            "L" + TARGET + ";target" + OLD_TARGET_DESCRIPTOR;
    private static final String NEW_TARGET_SELECTOR =
            "L" + TARGET + ";target" + NEW_TARGET_DESCRIPTOR;
    private static final String OLD_HANDLER_DESCRIPTOR =
            "(Ljava/lang/String;" + CALLBACK_INFO + ")V";
    private static final String NEW_HANDLER_DESCRIPTOR =
            "(Ljava/lang/String;I" + CALLBACK_INFO + ")V";
    private static final String MIXED_DIRECT_SELECTOR =
            "mixed(Ljava/lang/String;)Ljava/lang/String;";
    private static final String MIXED_SOURCE_SELECTOR =
            "sourceMixed(Ljava/lang/String;)Ljava/lang/String;";
    private static final String MIXED_OLD_HANDLER =
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String MIXED_NEW_HANDLER =
            "(Ljava/lang/String;ILjava/lang/String;)Ljava/lang/String;";

    @TempDir
    Path tempDir;

    private String savedTargetVersion;
    private Field fuzzyResolverField;
    private FuzzyMethodResolver savedFuzzyResolver;

    @BeforeEach
    void installSyntheticHostIndex() throws Exception {
        savedTargetVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = TARGET_VERSION;

        Path hostJar = tempDir.resolve("minecraft-host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(hostJar))) {
            writeEntry(out, TARGET + ".class", targetClass());
        }

        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(hostJar);
        assertTrue(resolver.isIndexed(), "the synthetic Minecraft target must be indexed");
        assertTrue(IntermediaryToMojangMapper.getInstance().isLoaded(),
                "Fabric runtime repair requires the bundled mapping table");

        RetromodTransformer transformer = RetromodTransformer.getInstance();
        fuzzyResolverField = RetromodTransformer.class.getDeclaredField("fuzzyResolver");
        fuzzyResolverField.setAccessible(true);
        savedFuzzyResolver = (FuzzyMethodResolver) fuzzyResolverField.get(transformer);
        fuzzyResolverField.set(transformer, resolver);
    }

    @AfterEach
    void restoreSharedTransformerState() throws Exception {
        RetromodVersion.TARGET_MC_VERSION = savedTargetVersion;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearJarClassBytesProvider();
        if (fuzzyResolverField != null) {
            fuzzyResolverField.set(transformer, savedFuzzyResolver);
        }
    }

    @Test
    @DisplayName("Fabric runtime output repairs a refmap-linked handler and its refmap")
    void fabricRuntimeCarriesRefmapPlanIntoClassPass() throws Exception {
        Path source = tempDir.resolve("runtime-fabric.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(source))) {
            writeEntry(out, "fabric.mod.json", fabricMetadata());
            writeFixtureEntries(out);
        }
        Path outputDir = Files.createDirectories(tempDir.resolve("fabric-game/output"));

        Path output = new FabricModTransformer(TARGET_VERSION).transformMod(source, outputDir);

        assertNotNull(output, "the Fabric runtime transform must produce an output jar");
        assertTrue(Files.exists(output), "the Fabric runtime output must exist");
        assertRepairedArchive(output);
    }

    @Test
    @DisplayName("Forge runtime output repairs a refmap-linked handler and its refmap")
    void forgeRuntimeCarriesRefmapPlanIntoClassPass() throws Exception {
        Path source = tempDir.resolve("runtime-forge.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(source))) {
            writeEntry(out, "META-INF/mods.toml", forgeMetadata());
            writeFixtureEntries(out);
        }
        Path outputDir = Files.createDirectories(tempDir.resolve("forge-game/output"));

        Path output = new ForgeModTransformer(TARGET_VERSION).transformMod(source, outputDir);

        assertNotNull(output, "the Forge runtime transform must produce an output jar");
        assertTrue(Files.exists(output), "the Forge runtime output must exist");
        assertRepairedArchive(output);
    }

    @Test
    @DisplayName("Post-remap repair plans direct and refmap selectors together")
    void postRemapRepairUsesOneMixedSelectorPlan() {
        byte[] input = mixedSelectorMixinClass();
        MixinRefmapRepairIndex index = MixinRefmapRepairIndex.builder()
                .put(MIXIN, MIXED_SOURCE_SELECTOR,
                        new MixinRefmapRepairIndex.Repair(
                                TARGET,
                                "(Ljava/lang/String;)Ljava/lang/String;",
                                "(ILjava/lang/String;)Ljava/lang/String;",
                                Opcodes.ACC_PUBLIC,
                                List.of(new MixinHandlerResignature.ParamInsert(0, "I"))))
                .build();

        byte[] output = new MixinCompatibilityTransformer(
                RetromodTransformer.getInstance()).applyPostRemapRepairs(input, index);
        ClassNode mixin = new ClassNode();
        new ClassReader(output).accept(mixin, 0);
        MethodNode handler = mixin.methods.stream()
                .filter(method -> method.name.equals("handler"))
                .findFirst()
                .orElseThrow();

        assertEquals(MIXED_NEW_HANDLER, handler.desc);
        assertEquals(List.of(
                "mixed(ILjava/lang/String;)Ljava/lang/String;",
                MIXED_SOURCE_SELECTOR), injectSelectors(handler));
    }

    private static void assertRepairedArchive(Path archive) throws Exception {
        byte[] mixinBytes = readEntry(archive, MIXIN_ENTRY);
        assertNotNull(mixinBytes, "the transformed Mixin class must remain in the jar");
        ClassNode mixin = new ClassNode();
        new ClassReader(mixinBytes).accept(mixin, 0);
        MethodNode handler = mixin.methods.stream()
                .filter(method -> method.name.equals("handler"))
                .findFirst()
                .orElseThrow();
        assertEquals(NEW_HANDLER_DESCRIPTOR, handler.desc,
                "the host's added int parameter must be inserted before CallbackInfo");
        assertEquals(SOURCE_SELECTOR, injectSelector(handler),
                "the annotation must retain the source selector used as the refmap key");

        byte[] refmapBytes = readEntry(archive, REFMAP_ENTRY);
        assertNotNull(refmapBytes, "the transformed refmap must remain in the jar");
        JsonObject entries = JsonParser.parseString(
                        new String(refmapBytes, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("mappings")
                .getAsJsonObject(MIXIN_DOTTED);
        assertNotNull(entries, "the Mixin's refmap section must remain present");
        assertEquals(NEW_TARGET_SELECTOR, entries.get(SOURCE_SELECTOR).getAsString(),
                "the same refmap entry must point at the repaired host descriptor");
    }

    private static String injectSelector(MethodNode handler) {
        String visible = injectSelector(handler.visibleAnnotations);
        return visible != null ? visible : injectSelector(handler.invisibleAnnotations);
    }

    private static String injectSelector(List<AnnotationNode> annotations) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/injection/Inject;".equals(annotation.desc)
                    || annotation.values == null) {
                continue;
            }
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if (!"method".equals(annotation.values.get(i))) continue;
                Object value = annotation.values.get(i + 1);
                if (value instanceof String selector) return selector;
                if (value instanceof List<?> selectors && selectors.size() == 1
                        && selectors.get(0) instanceof String selector) {
                    return selector;
                }
            }
        }
        return null;
    }

    private static List<String> injectSelectors(MethodNode handler) {
        for (List<AnnotationNode> annotations : List.of(
                handler.visibleAnnotations != null
                        ? handler.visibleAnnotations : List.<AnnotationNode>of(),
                handler.invisibleAnnotations != null
                        ? handler.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (!annotation.desc.endsWith("ModifyReturnValue;")
                        || annotation.values == null) {
                    continue;
                }
                for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                    if (!"method".equals(annotation.values.get(i))) continue;
                    Object value = annotation.values.get(i + 1);
                    if (value instanceof List<?> selectors) {
                        return selectors.stream().map(String::valueOf).toList();
                    }
                    if (value instanceof String selector) return List.of(selector);
                }
            }
        }
        return List.of();
    }

    private static void writeFixtureEntries(JarOutputStream out) throws Exception {
        writeEntry(out, MIXIN_ENTRY, mixinClass());
        writeEntry(out, CONFIG_ENTRY, mixinConfig());
        writeEntry(out, REFMAP_ENTRY, refmap());
    }

    private static byte[] targetClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);
        MethodVisitor target = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "target", NEW_TARGET_DESCRIPTOR, null, null);
        target.visitCode();
        target.visitInsn(Opcodes.RETURN);
        target.visitMaxs(0, 2);
        target.visitEnd();

        MethodVisitor mixed = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "mixed",
                "(ILjava/lang/String;)Ljava/lang/String;", null, null);
        mixed.visitCode();
        mixed.visitVarInsn(Opcodes.ALOAD, 2);
        mixed.visitInsn(Opcodes.ARETURN);
        mixed.visitMaxs(1, 3);
        mixed.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mixedSelectorMixinClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC,
                MIXIN, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor owners = mixin.visitArray("value");
        owners.visit(null, Type.getObjectType(TARGET));
        owners.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "handler", MIXED_OLD_HANDLER, null, null);
        AnnotationVisitor modify = handler.visitAnnotation(
                "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;", false);
        AnnotationVisitor methods = modify.visitArray("method");
        methods.visit(null, MIXED_DIRECT_SELECTOR);
        methods.visit(null, MIXED_SOURCE_SELECTOR);
        methods.visitEnd();
        AnnotationVisitor at = modify.visitAnnotation(
                "at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "RETURN");
        at.visitEnd();
        modify.visitEnd();
        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 2);
        handler.visitInsn(Opcodes.POP);
        handler.visitVarInsn(Opcodes.ALOAD, 1);
        handler.visitInsn(Opcodes.ARETURN);
        handler.visitMaxs(1, 3);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] mixinClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                MIXIN, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor owners = mixin.visitArray("value");
        owners.visit(null, Type.getObjectType(TARGET));
        owners.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "handler", OLD_HANDLER_DESCRIPTOR, null, null);
        AnnotationVisitor inject = handler.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, SOURCE_SELECTOR);
        methods.visitEnd();
        AnnotationVisitor at = inject.visitAnnotation(
                "at", "Lorg/spongepowered/asm/mixin/injection/At;");
        at.visit("value", "HEAD");
        at.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 2);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String fabricMetadata() {
        return "{\"schemaVersion\":1,\"id\":\"runtime_refmap_fabric\","
                + "\"version\":\"1.0.0\",\"name\":\"Runtime Refmap Fabric\","
                + "\"mixins\":[\"" + CONFIG_ENTRY + "\"],"
                + "\"depends\":{\"fabricloader\":\"*\",\"minecraft\":\"1.20.1\"}}";
    }

    private static String forgeMetadata() {
        return "modLoader=\"javafml\"\n"
                + "loaderVersion=\"[1,)\"\n"
                + "license=\"MIT\"\n"
                + "[[mods]]\n"
                + "modId=\"runtime_refmap_forge\"\n"
                + "version=\"1.0.0\"\n"
                + "displayName=\"Runtime Refmap Forge\"\n"
                + "[[dependencies.runtime_refmap_forge]]\n"
                + "modId=\"minecraft\"\n"
                + "mandatory=true\n"
                + "versionRange=\"[1.20.1]\"\n"
                + "ordering=\"NONE\"\n"
                + "side=\"BOTH\"\n";
    }

    private static String mixinConfig() {
        return "{\"required\":true,\"package\":\"com.example.mixin\","
                + "\"refmap\":\"" + REFMAP_ENTRY + "\","
                + "\"mixins\":[\"RuntimeMixin\"]}";
    }

    private static String refmap() {
        return "{\"mappings\":{\"" + MIXIN_DOTTED + "\":{\""
                + SOURCE_SELECTOR + "\":\"" + OLD_TARGET_SELECTOR + "\"}}}";
    }

    private static void writeEntry(JarOutputStream out, String name, String content)
            throws Exception {
        writeEntry(out, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeEntry(JarOutputStream out, String name, byte[] content)
            throws Exception {
        out.putNextEntry(new JarEntry(name));
        out.write(content);
        out.closeEntry();
    }

    private static byte[] readEntry(Path jar, String name) throws Exception {
        try (JarFile archive = new JarFile(jar.toFile())) {
            JarEntry entry = archive.getJarEntry(name);
            if (entry == null) return null;
            return archive.getInputStream(entry).readAllBytes();
        }
    }

}
