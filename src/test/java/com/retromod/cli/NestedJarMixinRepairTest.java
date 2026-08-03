/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.cli;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.IntermediaryToMojangMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bundled library ships its own Mixins. The nested-jar pass renamed its classes but left those
 * Mixins alone, so their selectors still named the version the library was built against and they
 * could not resolve a target on a current host.
 */
class NestedJarMixinRepairTest {

    private static final String MIXIN_ENTRY = "testlib/NestedMixin.class";
    private static final String LEGACY_SELECTOR = "method_55665(Lnet/minecraft/class_1297;"
            + "Lnet/minecraft/class_1297;Lnet/minecraft/class_9066;)Lnet/minecraft/class_243;";

    private String savedVersion;

    @AfterEach
    void restore() {
        if (savedVersion != null) RetromodVersion.TARGET_MC_VERSION = savedVersion;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("The nested-jar pass remaps a bundled library's Mixin selector")
    void repairsMixinInsideNestedJar() throws Exception {
        savedVersion = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        IntermediaryToMojangMapper.applyTo(RetromodTransformer.getInstance());

        byte[] repaired = RetromodCli.transformNestedJar(libraryJar(), 1);

        ClassNode mixin = new ClassNode();
        new ClassReader(classFrom(repaired)).accept(mixin, 0);
        List<String> found = selectors(mixin);

        assertFalse(found.contains(LEGACY_SELECTOR), "the old selector must not survive: " + found);
        assertTrue(found.stream().anyMatch(s -> s.startsWith("getDefaultPassengerAttachmentPoint")),
                "expected the current name, got: " + found);
    }

    /** A one-class library whose Mixin still names its target the way the library was built. */
    private static byte[] libraryJar() throws IOException {
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
            jar.putNextEntry(new ZipEntry(MIXIN_ENTRY));
            jar.write(cw.toByteArray());
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] classFrom(byte[] jarBytes) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jarBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (MIXIN_ENTRY.equals(entry.getName())) return zip.readAllBytes();
            }
        }
        throw new IOException("no " + MIXIN_ENTRY + " in the repaired library");
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
}
