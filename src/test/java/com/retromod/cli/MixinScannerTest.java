/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link MixinScanner}: ASM annotation reading (class targets in both class and string form,
 * injector handlers, nested {@code @At}, MixinExtras {@code @Local} capture), the applied flag from
 * a discovered config, the injector-null bare-mixin record, the frozen JSON schema, and per-jar
 * robustness. Synthetic mixin classes are emitted with ASM so the test is host-independent.
 */
public class MixinScannerTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";

    /** A @Mixin using the string {@code targets} form, with an @Inject handler capturing a @Local. */
    private static byte[] fooMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "com/test/FooMixin", null, "java/lang/Object", null);

        AnnotationVisitor a = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = a.visitArray("targets");
        targets.visit(null, "net.example.Foo");
        targets.visitEnd();
        a.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onFoo", "(I)V", null, null);
        AnnotationVisitor inj = mv.visitAnnotation(INJECT, false);
        AnnotationVisitor method = inj.visitArray("method");
        method.visit(null, "bar()V");
        method.visitEnd();
        AnnotationVisitor atArr = inj.visitArray("at");
        AnnotationVisitor at = atArr.visitAnnotation(null, AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        atArr.visitEnd();
        inj.visitEnd();
        // MixinExtras @Local on parameter 0
        mv.visitParameterAnnotation(0, LOCAL, false).visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A @Mixin using the class-literal {@code value} form, with a @Redirect + nested @At target. */
    private static byte[] barMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "com/test/BarMixin", null, "java/lang/Object", null);

        AnnotationVisitor a = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = a.visitArray("value");
        value.visit(null, Type.getObjectType("net/example/Bar"));
        value.visitEnd();
        a.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onBar", "()V", null, null);
        AnnotationVisitor inj = mv.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/Redirect;", false);
        AnnotationVisitor method = inj.visitArray("method");
        method.visit(null, "baz()V");
        method.visitEnd();
        // single (non-array) @At with a target selector
        AnnotationVisitor at = inj.visitAnnotation("at", AT);
        at.visit("value", "INVOKE");
        at.visit("target", "Lnet/example/Bar;doThing()V");
        at.visitEnd();
        inj.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A bare @Mixin with only an @Accessor (no injector handler beyond it) -> gets its own record. */
    private static byte[] accessorMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                "com/test/AccessorMixin", null, "java/lang/Object", null);
        AnnotationVisitor a = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = a.visitArray("value");
        value.visit(null, Type.getObjectType("net/example/Baz"));
        value.visitEnd();
        a.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getField", "()I", null, null);
        AnnotationVisitor acc = mv.visitAnnotation(ACCESSOR, false);
        acc.visit("value", "someField");
        acc.visitEnd();
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A @Mixin with NO injector methods at all -> one record with injector null. */
    private static byte[] pureShadowMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "com/test/PureMixin", null, "java/lang/Object", null);
        AnnotationVisitor a = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = a.visitArray("value");
        value.visit(null, Type.getObjectType("net/example/Pure"));
        value.visitEnd();
        a.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A plain class (not a mixin) that must be ignored. */
    private static byte[] plainClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/test/Plain", null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path buildJar(Path dir) throws Exception {
        Path jar = dir.resolve("testmixins.jar");
        String config = "{\n"
                + "  \"package\": \"com.test\",\n"
                + "  \"mixins\": [\"FooMixin\", \"BarMixin\", \"PureMixin\"],\n"
                + "  \"client\": [\"AccessorMixin\"]\n"
                + "}\n";
        String fabricJson = "{\n"
                + "  \"schemaVersion\": 1,\n"
                + "  \"id\": \"testmod\",\n"
                + "  \"mixins\": [\"testmod.mixins.json\"]\n"
                + "}\n";
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zos, "com/test/FooMixin.class", fooMixin());
            put(zos, "com/test/BarMixin.class", barMixin());
            put(zos, "com/test/AccessorMixin.class", accessorMixin());
            put(zos, "com/test/PureMixin.class", pureShadowMixin());
            put(zos, "com/test/Plain.class", plainClass());
            put(zos, "testmod.mixins.json", config.getBytes(StandardCharsets.UTF_8));
            put(zos, "fabric.mod.json", fabricJson.getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void put(ZipOutputStream zos, String name, byte[] data) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    private MixinScanner.Record find(MixinScanner.ScanResult r, String cls, String handler) {
        return r.records.stream()
                .filter(rec -> cls.equals(rec.mixinClass)
                        && java.util.Objects.equals(handler, rec.handler))
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("Scans injector handlers, both target forms, @At, @Local, and applied flag")
    void scansMixinJar(@TempDir Path dir) throws Exception {
        Path jar = buildJar(dir);
        MixinScanner.ScanResult r = MixinScanner.scan(List.of(jar));

        assertEquals(1, r.scannedJars);
        assertEquals(0, r.skippedJars);
        // Plain (non-mixin) class contributes no record.
        assertTrue(r.records.stream().noneMatch(rec -> "com/test/Plain".equals(rec.mixinClass)));

        // FooMixin: string-target form, @Inject, nested @At HEAD, @Local capture.
        MixinScanner.Record foo = find(r, "com/test/FooMixin", "onFoo");
        assertNotNull(foo, "FooMixin/onFoo record missing");
        assertEquals("Inject", foo.injector);
        assertEquals("(I)V", foo.handlerDesc);
        assertEquals(List.of("net/example/Foo"), foo.targetClasses);
        assertEquals(List.of("bar()V"), foo.targetSelectors);
        assertEquals(List.of("HEAD"), foo.at);
        assertTrue(foo.capturesLocal, "expected @Local capture on FooMixin");
        assertEquals(Boolean.TRUE, foo.applied, "FooMixin is declared in the config");

        // BarMixin: class-literal target form, @Redirect, single @At with a target selector.
        MixinScanner.Record bar = find(r, "com/test/BarMixin", "onBar");
        assertNotNull(bar);
        assertEquals("Redirect", bar.injector);
        assertEquals(List.of("net/example/Bar"), bar.targetClasses);
        assertEquals(List.of("baz()V"), bar.targetSelectors);
        assertEquals(List.of("INVOKE:Lnet/example/Bar;doThing()V"), bar.at);
        assertFalse(bar.capturesLocal);

        // AccessorMixin: @Accessor value becomes a selector.
        MixinScanner.Record acc = find(r, "com/test/AccessorMixin", "getField");
        assertNotNull(acc);
        assertEquals("Accessor", acc.injector);
        assertEquals(List.of("someField"), acc.targetSelectors);

        // PureMixin: no injector methods -> one record with injector null but target captured.
        MixinScanner.Record pure = find(r, "com/test/PureMixin", null);
        assertNotNull(pure, "expected a bare record for a mixin with no injectors");
        assertNull(pure.injector);
        assertNull(pure.handler);
        assertEquals(List.of("net/example/Pure"), pure.targetClasses);
        assertEquals(Boolean.TRUE, pure.applied);
    }

    @Test
    @DisplayName("Emits the frozen JSON schema with all expected keys")
    void emitsFrozenJsonSchema(@TempDir Path dir) throws Exception {
        Path jar = buildJar(dir);
        MixinScanner.ScanResult r = MixinScanner.scan(List.of(jar));
        String json = MixinScanner.toJson(r);

        assertTrue(json.contains("\"scannedJars\""));
        assertTrue(json.contains("\"records\""));
        for (String key : new String[]{"jar", "mixinClass", "targetClasses", "applied",
                "handler", "handlerDesc", "injector", "targetSelectors", "at", "capturesLocal"}) {
            assertTrue(json.contains("\"" + key + "\""), "JSON missing key: " + key);
        }
        // parse round-trips as valid JSON with the right jar count
        com.google.gson.JsonObject root =
                com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertEquals(1, root.get("scannedJars").getAsInt());
        assertTrue(root.getAsJsonArray("records").size() >= 4);
    }

    @Test
    @DisplayName("A corrupt jar is skipped, not fatal, and good jars still scan")
    void corruptJarIsSkipped(@TempDir Path dir) throws Exception {
        Path good = buildJar(dir);
        Path bad = dir.resolve("broken.jar");
        Files.write(bad, "this is not a zip".getBytes(StandardCharsets.UTF_8));

        MixinScanner.ScanResult r = MixinScanner.scan(List.of(good, bad));
        assertEquals(1, r.scannedJars);
        assertEquals(1, r.skippedJars);
        assertTrue(r.records.stream().anyMatch(rec -> "com/test/FooMixin".equals(rec.mixinClass)));
    }

    @Test
    @DisplayName("Deep mixin configs are refused before tree parsing")
    void refusesDeepMixinConfig(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("deep-config.jar");
        String nested = "[".repeat(257) + "0" + "]".repeat(257);
        String config = "{\"package\":\"com.test\",\"mixins\":[\"FooMixin\"],"
                + "\"padding\":" + nested + "}";
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(output, "com/test/FooMixin.class", fooMixin());
            put(output, "deep.mixins.json", config.getBytes(StandardCharsets.UTF_8));
        }

        MixinScanner.ScanResult result = MixinScanner.scan(List.of(jar));
        MixinScanner.Record record = find(result, "com/test/FooMixin", "onFoo");

        assertNotNull(record);
        assertNull(record.applied, "a refused config must not mark its mixin as declared");
    }

    @Test
    @DisplayName("Recognizes SpongePowered and MixinExtras injector descriptors")
    void recognizesInjectorDescriptors() {
        assertEquals("Inject", MixinScanner.recognizeInjector(INJECT));
        assertEquals("Accessor", MixinScanner.recognizeInjector(ACCESSOR));
        assertEquals("WrapOperation", MixinScanner.recognizeInjector(
                "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;"));
        assertEquals("ModifyExpressionValue", MixinScanner.recognizeInjector(
                "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;"));
        // version-tolerant fallback for a moved subpackage
        assertEquals("ModifyReturnValue", MixinScanner.recognizeInjector(
                "Lcom/llamalad7/mixinextras/injector/v3/ModifyReturnValue;"));
        assertNull(MixinScanner.recognizeInjector("Ljava/lang/Override;"));
    }

    /** A @Mixin whose @Inject selects its target via @Desc(value=...) instead of a method= string. */
    private static byte[] descMixin() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "com/test/DescMixin", null, "java/lang/Object", null);
        AnnotationVisitor a = cw.visitAnnotation(MIXIN, false);
        AnnotationVisitor value = a.visitArray("value");
        value.visit(null, Type.getObjectType("net/example/Desc"));
        value.visitEnd();
        a.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "onDesc", "(I)V", null, null);
        AnnotationVisitor inj = mv.visitAnnotation(INJECT, false);
        // target = { @Desc(value = "someMethod") } -- the @Desc[] annotation-array form, no method= string.
        AnnotationVisitor targetArr = inj.visitArray("target");
        AnnotationVisitor desc = targetArr.visitAnnotation(null,
                "Lorg/spongepowered/asm/mixin/injection/Desc;");
        desc.visit("value", "someMethod");
        desc.visitEnd();
        targetArr.visitEnd();
        // MixinExtras @Local from a (hypothetical) moved sugar subpackage must still be detected.
        mv.visitParameterAnnotation(0, "Lcom/llamalad7/mixinextras/sugar/v2/Local;", false).visitEnd();
        inj.visitEnd();
        mv.visitCode();
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    @DisplayName("Captures @Desc-based target selectors and a moved-subpackage @Local")
    void capturesDescTargetAndMovedLocal(@TempDir Path dir) throws Exception {
        Path jar = dir.resolve("descmixin.jar");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zos, "com/test/DescMixin.class", descMixin());
        }
        MixinScanner.ScanResult r = MixinScanner.scan(List.of(jar));
        MixinScanner.Record rec = find(r, "com/test/DescMixin", "onDesc");
        assertNotNull(rec, "DescMixin/onDesc record missing");
        assertEquals("Inject", rec.injector);
        assertEquals(List.of("net/example/Desc"), rec.targetClasses);
        assertEquals(List.of("someMethod"), rec.targetSelectors,
                "the @Desc value should be captured as a target selector");
        assertTrue(rec.capturesLocal, "a moved-subpackage @Local must still be detected");
    }

    @Test
    @DisplayName("isLocalAnnotation tolerates a moved sugar subpackage")
    void localAnnotationFallback() {
        assertTrue(MixinScanner.isLocalAnnotation("Lcom/llamalad7/mixinextras/sugar/Local;"));
        assertTrue(MixinScanner.isLocalAnnotation("Lcom/llamalad7/mixinextras/sugar/v2/Local;"));
        assertFalse(MixinScanner.isLocalAnnotation("Lsomewhere/else/Local;"));
        assertFalse(MixinScanner.isLocalAnnotation("Lcom/llamalad7/mixinextras/sugar/Share;"));
        assertFalse(MixinScanner.isLocalAnnotation(null));
    }

    @Test
    @DisplayName("Directory inputs are recursed for jars")
    void recursesDirectories(@TempDir Path dir) throws Exception {
        Path sub = dir.resolve("nested");
        Files.createDirectories(sub);
        buildJar(sub);
        MixinScanner.ScanResult r = MixinScanner.scan(List.of(dir));
        assertEquals(1, r.scannedJars);
        assertTrue(r.records.stream().anyMatch(rec -> "com/test/FooMixin".equals(rec.mixinClass)));
    }
}
