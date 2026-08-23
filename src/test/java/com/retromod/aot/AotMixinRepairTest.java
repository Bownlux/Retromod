/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.aot;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.ShimRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A Mixin selector is annotation text, so remapping a class does not touch it. The AOT path used
 * to stop at the class remap, which left every selector on the name the mod was built against and
 * made all of a prepared mod's Mixins fail to apply.
 */
class AotMixinRepairTest {

    private static final Path FIXTURE = Path.of("test-jars-mixin/revampedphantoms-1.1.2-fabric.jar");
    private static final Path REFMAP_FIXTURE =
            Path.of("test-jars-mixin/darkness-fabric-mc119-2.0.103.jar");
    private static final String REFMAP_ENTRY = "darkness-refmap.json";
    private static final Pattern OBFUSCATED = Pattern.compile("(class|method|field)_\\d+");
    private static final String NESTED_LIB = "META-INF/jars/retromod-test-lib.jar";
    private static final String NESTED_MIXIN = "testlib/NestedMixin.class";
    private static final String LEGACY_SELECTOR = "method_55665(Lnet/minecraft/class_1297;"
            + "Lnet/minecraft/class_1297;Lnet/minecraft/class_9066;)Lnet/minecraft/class_243;";

    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("AOT leaves no intermediary Mixin selector behind, matching the transform path")
    void aotRepairsMixinSelectors() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(FIXTURE), "revamped phantoms fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";

        Path prepared = prepare(FIXTURE);
        try {
            List<String> stale = staleSelectors(prepared);
            assertTrue(stale.isEmpty(), "AOT left intermediary Mixin selectors: " + stale);
        } finally {
            Files.deleteIfExists(prepared);
        }
    }

    @Test
    @DisplayName("AOT remaps the refmap a mixin needs to resolve its targets on a 26.x host")
    void aotRemapsRefmap() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFMAP_FIXTURE), "darkness fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";

        String original = readEntry(REFMAP_FIXTURE, REFMAP_ENTRY);
        Path prepared = prepare(REFMAP_FIXTURE);
        try {
            String actual = readEntry(prepared, REFMAP_ENTRY);
            assertNotEquals(original, actual, "an intermediary refmap is rejected on a 26.x host");
            assertEquals(com.retromod.core.MixinRefmapRemapper.remap(original,
                            com.retromod.mapping.IntermediaryToMojangMapper.getInstance()),
                    actual, "AOT must produce the same refmap as the other paths");
        } finally {
            Files.deleteIfExists(prepared);
        }
    }

    @Test
    @DisplayName("A Mixin inside a bundled library is repaired like the mod's own")
    void repairsMixinInsideNestedJar(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REFMAP_FIXTURE), "darkness fixture present");
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";

        // A real mod carries the metadata the compiler needs, so the library is added to one.
        Path host = tmp.resolve("bundles-a-library.jar");
        addNestedLibrary(REFMAP_FIXTURE, host, NESTED_LIB, nestedLibraryJar());

        Path prepared = prepare(host);
        try {
            byte[] library = readBytes(prepared, NESTED_LIB);
            ClassNode mixin = new ClassNode();
            new ClassReader(readBytesFrom(library, NESTED_MIXIN)).accept(mixin, 0);

            List<String> stale = new ArrayList<>();
            for (MethodNode method : mixin.methods) {
                collect(method.visibleAnnotations, "nested", stale);
                collect(method.invisibleAnnotations, "nested", stale);
            }
            assertTrue(stale.isEmpty(), "the bundled library kept intermediary selectors: " + stale);
            assertTrue(selectors(mixin).stream().anyMatch(s -> s.startsWith("getDefaultPassengerAttachmentPoint")),
                    "the nested selector must be remapped: " + selectors(mixin));
        } finally {
            Files.deleteIfExists(prepared);
        }
    }

    @Test
    @DisplayName("AOT moves an accessor owner before converting its target namespace")
    void aotRunsAccessorOwnerProofBeforeTheClassRemap(@TempDir Path tmp) throws Exception {
        tmp = tmp.toRealPath();
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        Path input = tmp.resolve("accessor-owner.jar");
        writeAccessorFixture(input);

        ShimRegistry registry = new ShimRegistry();
        registry.register(new com.retromod.shim.fabric.Fabric_1_21_10_to_1_21_11());
        registry.register(new com.retromod.shim.fabric.Fabric_1_21_11_to_26_1());

        Path prepared = new AotCompiler(
            registry, "26.1", tmp.resolve("cache")).compileModAot(input);
        ClassNode accessor = new ClassNode();
        new ClassReader(readBytes(prepared, "example/PlayerAccessor.class"))
            .accept(accessor, 0);

        String expectedOwner = com.retromod.mapping.IntermediaryToMojangMapper
            .getInstance().mapClass("net/minecraft/class_11890");
        assertEquals(expectedOwner, mixinTarget(accessor));
        MethodNode method = accessor.methods.stream()
            .filter(candidate -> candidate.name.equals("getPlayerModelParts"))
            .findFirst().orElseThrow();
        AnnotationNode annotation = annotation(method, "Lorg/spongepowered/asm/mixin/gen/Accessor;");
        assertEquals("DATA_PLAYER_MODE_CUSTOMISATION", annotationValue(annotation, "value"));
        assertEquals(false, annotationValue(annotation, "remap"));
    }

    /**
     * Builds the AOT jar for {@code fixture}. An unpackaged build stamps the cache by version
     * alone, so a previous run of the same version is dropped first rather than reused.
     */
    private static Path prepare(Path fixture) throws Exception {
        String cachedName = fixture.getFileName().toString().replace(".jar", "-aot.jar");
        Files.deleteIfExists(Path.of("config/retromod/aot-cache").resolve(cachedName));

        Path prepared = new AotCompiler(new ShimRegistry(), "26.1").compileModAot(fixture);
        assertNotEquals(fixture, prepared, "this fixture needs an AOT build to be meaningful");
        return prepared;
    }

    /** A one-class library whose Mixin still names its target the way the library was built. */
    private static byte[] nestedLibraryJar() throws IOException {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "testlib/NestedMixin", null, "java/lang/Object", null);
        var mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/class_1297"));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = cw.visitMethod(Opcodes.ACC_PRIVATE, "handler", "()V", null, null);
        var inject = handler.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false);
        var selector = inject.visitArray("method");
        selector.visit(null, LEGACY_SELECTOR);
        selector.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(1, 1);
        handler.visitEnd();
        cw.visitEnd();

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry(NESTED_MIXIN));
            jar.write(cw.toByteArray());
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static void writeAccessorFixture(Path path) throws IOException {
        String metadata = "{\"schemaVersion\":1,\"id\":\"accessor_owner\","
            + "\"version\":\"1.0.0\",\"depends\":{\"minecraft\":\"1.21.10\"}}";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new ZipEntry("fabric.mod.json"));
            jar.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new ZipEntry("example/PlayerAccessor.class"));
            jar.write(accessorMixin());
            jar.closeEntry();
        }
    }

    private static byte[] accessorMixin() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17,
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
            "example/PlayerAccessor", null, "java/lang/Object", null);
        var mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        var targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/class_1657"));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor method = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getPlayerModelParts", "()Lnet/minecraft/class_2940;", null, null);
        var accessor = method.visitAnnotation(
            "Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        accessor.visit("value", "PLAYER_MODEL_PARTS");
        accessor.visitEnd();
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/lang/AssertionError", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** Copies {@code source} to {@code target} with one extra entry added. */
    private static void addNestedLibrary(Path source, Path target, String entryName, byte[] entry)
            throws IOException {
        try (ZipFile in = new ZipFile(source.toFile());
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(target))) {
            for (ZipEntry existing : Collections.list(in.entries())) {
                if (existing.isDirectory()) continue;
                out.putNextEntry(new ZipEntry(existing.getName()));
                try (InputStream in2 = in.getInputStream(existing)) {
                    in2.transferTo(out);
                }
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(entryName));
            out.write(entry);
            out.closeEntry();
        }
    }

    private static byte[] readBytes(Path jar, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile());
             InputStream in = zip.getInputStream(zip.getEntry(entryName))) {
            return in.readAllBytes();
        }
    }

    private static byte[] readBytesFrom(byte[] jarBytes, String entryName) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) return zip.readAllBytes();
            }
        }
        throw new IOException("no " + entryName + " in the bundled library");
    }

    private static String mixinTarget(ClassNode classNode) {
        for (List<AnnotationNode> annotations : List.of(
                classNode.visibleAnnotations != null
                    ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null
                    ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) continue;
                List<?> targets = (List<?>) annotationValue(annotation, "value");
                return ((Type) targets.get(0)).getInternalName();
            }
        }
        throw new AssertionError("Mixin annotation is missing");
    }

    private static AnnotationNode annotation(MethodNode method, String descriptor) {
        for (List<AnnotationNode> annotations : List.of(
                method.visibleAnnotations != null
                    ? method.visibleAnnotations : List.<AnnotationNode>of(),
                method.invisibleAnnotations != null
                    ? method.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (descriptor.equals(annotation.desc)) return annotation;
            }
        }
        throw new AssertionError("annotation is missing: " + descriptor);
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) {
                return annotation.values.get(index + 1);
            }
        }
        return null;
    }

    /** Every selector string in the class's injector annotations. */
    private static List<String> selectors(ClassNode classNode) {
        List<String> found = new ArrayList<>();
        for (MethodNode method : classNode.methods) {
            for (List<AnnotationNode> list :
                    java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
                if (list == null) continue;
                for (AnnotationNode annotation : list) {
                    if (annotation.values == null) continue;
                    for (Object value : annotation.values) {
                        if (value instanceof List<?> nested) {
                            for (Object item : nested) {
                                if (item instanceof String text) found.add(text);
                            }
                        }
                    }
                }
            }
        }
        return found;
    }

    private static String readEntry(Path jar, String entryName) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile());
             InputStream in = zip.getInputStream(zip.getEntry(entryName))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** Every annotation string in the jar that still carries an intermediary name. */
    private static List<String> staleSelectors(Path jar) throws IOException {
        List<String> stale = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                ClassNode classNode = new ClassNode();
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in.readAllBytes()).accept(classNode, 0);
                }
                for (MethodNode method : classNode.methods) {
                    String where = classNode.name + "#" + method.name;
                    collect(method.visibleAnnotations, where, stale);
                    collect(method.invisibleAnnotations, where, stale);
                }
            }
        }
        return stale;
    }

    private static void collect(List<AnnotationNode> annotations, String where, List<String> stale) {
        if (annotations == null) return;
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc == null || !annotation.desc.contains("spongepowered")) continue;
            collectValues(annotation.values, where, stale);
        }
    }

    private static void collectValues(List<Object> values, String where, List<String> stale) {
        if (values == null) return;
        for (Object value : values) {
            if (value instanceof String text && OBFUSCATED.matcher(text).find()) {
                stale.add(where + " -> " + text);
            } else if (value instanceof List<?> nested) {
                collectValues(new ArrayList<>(nested), where, stale);
            } else if (value instanceof AnnotationNode annotation) {
                collectValues(annotation.values, where, stale);
            }
        }
    }
}
