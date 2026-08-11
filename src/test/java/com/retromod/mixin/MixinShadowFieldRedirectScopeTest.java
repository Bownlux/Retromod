/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.AnnotationNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixinShadowFieldRedirectScopeTest {

    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";
    private static final String WINDOW = "com/mojang/blaze3d/platform/Window";

    @AfterEach
    void resetTransformer() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void windowFieldRedirectDoesNotRenameMinecraftMixinShadow() {
        RetromodTransformer transformer = transformerWithWindowRedirect();
        byte[] output = new MixinCompatibilityTransformer(transformer).transformMixinClass(
                shadowMixin("test/MinecraftMixin", "net/minecraft/client/Minecraft",
                        "window", "L" + WINDOW + ";"));

        assertShadowAndReadUse(output, "window");
    }

    @Test
    void matchingWindowMixinRenamesShadowAndItsSelfReferenceTogether() {
        RetromodTransformer transformer = transformerWithWindowRedirect();
        byte[] output = new MixinCompatibilityTransformer(transformer).transformMixinClass(
                shadowMixin("test/WindowMixin", WINDOW, "window", "J"));

        assertShadowAndReadUse(output, "handle");
    }

    @Test
    void sameOwnerRedirectWithDifferentDescriptorDoesNotRenameShadow() {
        RetromodTransformer transformer = transformerWithWindowRedirect();
        byte[] output = new MixinCompatibilityTransformer(transformer).transformMixinClass(
                shadowMixin("test/OtherWindowMixin", WINDOW,
                        "window", "L" + WINDOW + ";"));

        assertShadowAndReadUse(output, "window");
    }

    @Test
    void fieldToMethodRedirectDoesNotCreateANonexistentShadow() {
        String environment = "net/neoforged/fml/loading/FMLEnvironment";
        String dist = "Lnet/neoforged/api/distmarker/Dist;";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerFieldRedirect(
                environment, "dist", dist, environment, "getDist", "()" + dist);

        byte[] output = new MixinCompatibilityTransformer(transformer).transformMixinClass(
                shadowMixin("test/EnvironmentMixin", environment, "dist", dist));

        assertShadowAndReadUse(output, "dist");
    }

    @Test
    void qualifiedFieldSelectorUsesItsExplicitOwner() {
        RetromodTransformer transformer = transformerWithWindowRedirect();
        byte[] output = new MixinCompatibilityTransformer(transformer)
                .transformMixinClass(fieldSelectorMixin());

        assertEquals("L" + WINDOW + ";handle:J", fieldSelectorTarget(output));
    }

    private static RetromodTransformer transformerWithWindowRedirect() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        transformer.registerFieldRedirect(WINDOW, "window", "J", WINDOW, "handle", "J");
        return transformer;
    }

    private static byte[] shadowMixin(
            String mixinName, String targetOwner, String fieldName, String fieldDescriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, mixinName, null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetOwner));
        targets.visitEnd();
        mixin.visitEnd();

        var shadow = writer.visitField(
                Opcodes.ACC_PRIVATE, fieldName, fieldDescriptor, null, null);
        shadow.visitAnnotation(SHADOW, true).visitEnd();
        shadow.visitEnd();

        MethodVisitor read = writer.visitMethod(
                Opcodes.ACC_PUBLIC, "read", "()" + fieldDescriptor, null, null);
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitFieldInsn(Opcodes.GETFIELD, mixinName, fieldName, fieldDescriptor);
        read.visitInsn("J".equals(fieldDescriptor) ? Opcodes.LRETURN : Opcodes.ARETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] fieldSelectorMixin() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/FieldSelectorMixin",
                null, "java/lang/Object", null);
        AnnotationVisitor mixin = writer.visitAnnotation(MIXIN, false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("net/minecraft/client/Minecraft"));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE, "handler",
                "(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
                null, null);
        AnnotationVisitor inject = handler.visitAnnotation(INJECT, true);
        AnnotationVisitor methods = inject.visitArray("method");
        methods.visit(null, "run()V");
        methods.visitEnd();
        AnnotationVisitor at = inject.visitAnnotation("at", AT);
        at.visit("value", "FIELD");
        at.visit("target", "L" + WINDOW + ";window:J");
        at.visitEnd();
        inject.visitEnd();
        handler.visitCode();
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String fieldSelectorTarget(byte[] output) {
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        AnnotationNode inject = node.methods.stream()
                .filter(method -> method.name.equals("handler"))
                .flatMap(method -> method.visibleAnnotations.stream())
                .filter(annotation -> INJECT.equals(annotation.desc))
                .findFirst().orElseThrow();
        AnnotationNode at = (AnnotationNode) annotationValue(inject, "at");
        return (String) annotationValue(at, "target");
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        throw new IllegalArgumentException("Missing annotation value: " + key);
    }

    private static void assertShadowAndReadUse(byte[] output, String expectedName) {
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        FieldNode shadow = node.fields.get(0);
        assertEquals(expectedName, shadow.name);

        FieldInsnNode read = node.methods.stream()
                .filter(method -> method.name.equals("read"))
                .flatMap(method -> java.util.stream.StreamSupport.stream(
                        method.instructions.spliterator(), false))
                .filter(FieldInsnNode.class::isInstance)
                .map(FieldInsnNode.class::cast)
                .findFirst().orElseThrow();
        assertEquals(expectedName, read.name);
    }
}
