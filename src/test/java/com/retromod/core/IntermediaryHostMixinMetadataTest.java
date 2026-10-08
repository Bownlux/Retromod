/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.core;

import com.retromod.mixin.MixinCompatibilityTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * A pre-26.1 Fabric host runs on intermediary names, so a mod's intermediary Mixin metadata is the
 * working one there. Palladium on Fabric 1.21.1 (#262) lost every accessor and injector because
 * its refmap entries were stripped as if they were stale.
 */
class IntermediaryHostMixinMetadataTest {

    private static final String REFMAP = "{\"mappings\":{\"pkg/RangedAttributeAccessor\":"
            + "{\"maxValue\":\"field_6351:D\"}},\"data\":{\"named:intermediary\":"
            + "{\"pkg/RangedAttributeAccessor\":{\"maxValue\":\"field_6351:D\"}}}}";

    private final String savedVersion = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restoreHost() {
        RetromodVersion.TARGET_MC_VERSION = savedVersion;
    }

    @Test
    @DisplayName("An intermediary refmap entry survives on a pre-26.1 Fabric host")
    void refmapEntryKeptOnIntermediaryHost(@TempDir Path dir) throws Exception {
        Path refmap = dir.resolve("palladium-common-refmap.json");
        Files.writeString(refmap, REFMAP);

        stripBrokenEntries(new FabricModTransformer("1.21.1"), dir, refmap);

        assertTrue(Files.readString(refmap).contains("field_6351:D"),
                "the refmap entry is the only thing that maps the accessor to its host field");
    }

    @Test
    @DisplayName("An intermediary refmap entry is still stripped on an unobfuscated host")
    void refmapEntryStrippedOnUnobfuscatedHost(@TempDir Path dir) throws Exception {
        Path refmap = dir.resolve("palladium-common-refmap.json");
        Files.writeString(refmap, REFMAP);

        stripBrokenEntries(new FabricModTransformer("26.1"), dir, refmap);

        assertFalse(Files.readString(refmap).contains("field_6351"),
                "after the remap, a surviving intermediary name on 26.1 names a removed member");
    }

    @Test
    @DisplayName("An explicit require=1 on a transformed injector becomes soft")
    void explicitRequireBecomesSoft() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/ServerPlayNetworkHandlerMixin", null,
                "java/lang/Object", null);
        AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, org.objectweb.asm.Type.getObjectType("net/minecraft/class_3244"));
        targets.visitEnd();
        mixin.visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE, "reachDistance", "(D)D", null, null);
        AnnotationVisitor modify = mv.visitAnnotation(
                "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;", true);
        AnnotationVisitor method = modify.visitArray("method");
        method.visit(null, "method_12046");
        method.visitEnd();
        modify.visit("require", 1);
        modify.visitEnd();
        mv.visitCode();
        mv.visitVarInsn(DLOAD, 1);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = new MixinCompatibilityTransformer(RetromodTransformer.getInstance())
                .transformMixinClass(cw.toByteArray());

        ClassNode node = new ClassNode();
        new ClassReader(out).accept(node, 0);
        MethodNode handler = node.methods.stream()
                .filter(m -> m.name.equals("reachDistance")).findFirst().orElseThrow();
        AnnotationNode annotation = handler.visibleAnnotations.get(0);
        int requireIndex = annotation.values.indexOf("require");
        assertEquals(0, annotation.values.get(requireIndex + 1),
                "a missed constant on a newer host must skip the injection, not abort Mixin");
    }

    private static void stripBrokenEntries(FabricModTransformer transformer, Path dir, Path refmap)
            throws Exception {
        Method method = FabricModTransformer.class.getDeclaredMethod(
                "stripBrokenRefmapEntries", Path.class, Path.class);
        method.setAccessible(true);
        method.invoke(transformer, dir, refmap);
    }
}
