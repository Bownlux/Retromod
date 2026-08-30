/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mixin;

import com.retromod.core.FuzzyMethodResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repairing a handler's signature is what makes Mixin apply it, so a repair is only an improvement
 * when the handler body still works. Deeper and Darker's painting handler is the worked example: its
 * target only gained a leading {@code ServerLevel}, which the engine repairs, but the body reads
 * {@code Painting.VARIANT_CODEC}, which 26.2 deleted. Repairing it would replace a mixin that
 * quietly fails to apply with a game that crashes when a painting breaks.
 */
class MixinHandlerBodySafetyTest {

    private static final String TARGET = "net/minecraft/world/entity/decoration/painting/Painting";
    private static final String SERVER_LEVEL = "Lnet/minecraft/server/level/ServerLevel;";
    private static final String ENTITY = "Lnet/minecraft/world/entity/Entity;";
    private static final String CODEC = "Lcom/mojang/serialization/Codec;";
    private static final String CALLBACK_INFO =
            "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
    private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    /** The handler's old shape, before the target gained its leading ServerLevel. */
    private static final String OLD_HANDLER = "(" + ENTITY + CALLBACK_INFO + ")V";

    @TempDir
    Path tempDir;

    private AutomaticMixinTranslator translator;

    @BeforeEach
    void indexHost() throws IOException {
        Path jar = tempDir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(TARGET + ".class"));
            out.write(hostPainting());
            out.closeEntry();
        }
        FuzzyMethodResolver resolver = new FuzzyMethodResolver();
        resolver.indexJar(jar);
        assertTrue(resolver.isIndexed());
        translator = new AutomaticMixinTranslator(resolver);
    }

    /** The host as 26.2 ships it: dropItem gained a ServerLevel and VARIANT_CODEC is gone. */
    private static byte[] hostPainting() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                TARGET, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "DEPTH", "F", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "dropItem", "(" + SERVER_LEVEL + ENTITY + ")V", null, null).visitEnd();
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                "getVariant", "()Ljava/lang/Object;", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * A mixin whose handler still has the old signature and whose body reads {@code fieldName} from
     * the target.
     */
    private static byte[] mixinReading(String fieldName, String fieldDescriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/PaintingMixin", null,
                "java/lang/Object", null);
        var mixin = writer.visitAnnotation(MIXIN, false);
        var value = mixin.visitArray("value");
        value.visit(null, org.objectweb.asm.Type.getObjectType(TARGET));
        value.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "dropItem", OLD_HANDLER, null, null);
        var inject = handler.visitAnnotation(INJECT, false);
        var method = inject.visitArray("method");
        method.visit(null, "dropItem");
        method.visitEnd();
        var at = inject.visitAnnotation("at", AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        inject.visitEnd();

        handler.visitCode();
        handler.visitFieldInsn(Opcodes.GETSTATIC, TARGET, fieldName, fieldDescriptor);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The same mixin, but its body calls {@code methodName} on the target instead. */
    private static byte[] mixinCalling(String methodName) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/PaintingMixin", null,
                "java/lang/Object", null);
        var mixin = writer.visitAnnotation(MIXIN, false);
        var value = mixin.visitArray("value");
        value.visit(null, org.objectweb.asm.Type.getObjectType(TARGET));
        value.visitEnd();
        mixin.visitEnd();

        MethodVisitor handler = writer.visitMethod(
                Opcodes.ACC_PRIVATE, "dropItem", OLD_HANDLER, null, null);
        var inject = handler.visitAnnotation(INJECT, false);
        var method = inject.visitArray("method");
        method.visit(null, "dropItem");
        method.visitEnd();
        var at = inject.visitAnnotation("at", AT);
        at.visit("value", "HEAD");
        at.visitEnd();
        inject.visitEnd();

        handler.visitCode();
        handler.visitVarInsn(Opcodes.ALOAD, 0);
        handler.visitTypeInsn(Opcodes.CHECKCAST, TARGET);
        handler.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TARGET, methodName,
                "()Ljava/lang/Object;", false);
        handler.visitInsn(Opcodes.POP);
        handler.visitInsn(Opcodes.RETURN);
        handler.visitMaxs(0, 0);
        handler.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String handlerDescriptor(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        for (MethodNode m : node.methods) {
            if (m.name.equals("dropItem")) return m.desc;
        }
        throw new AssertionError("the handler is missing");
    }

    @Test
    @DisplayName("A handler whose body reads a deleted field is left unrepaired")
    void handlerReadingADeletedFieldIsDeclined() {
        byte[] out = translator.translate(mixinReading("VARIANT_CODEC", CODEC));

        assertEquals(OLD_HANDLER, handlerDescriptor(out),
                "repairing the signature would make Mixin apply a handler that then throws "
                        + "NoSuchFieldError, which is worse than leaving it unapplied");
    }

    @Test
    @DisplayName("The same handler is repaired when its body only reads fields the host still has")
    void handlerReadingALiveFieldIsRepaired() {
        byte[] out = translator.translate(mixinReading("DEPTH", "F"));

        assertEquals("(" + SERVER_LEVEL + ENTITY + CALLBACK_INFO + ")V", handlerDescriptor(out),
                "the body is sound, so the proven leading parameter is inserted as usual");
    }

    @Test
    @DisplayName("A handler whose body calls a removed method is left unrepaired")
    void handlerCallingARemovedMethodIsDeclined() {
        byte[] out = translator.translate(mixinCalling("getGameRules"));

        assertEquals(OLD_HANDLER, handlerDescriptor(out),
                "a call the host no longer declares is as fatal as a removed field");
    }

    @Test
    @DisplayName("The same handler is repaired when it calls a method the host still has")
    void handlerCallingALiveMethodIsRepaired() {
        byte[] out = translator.translate(mixinCalling("getVariant"));

        assertEquals("(" + SERVER_LEVEL + ENTITY + CALLBACK_INFO + ")V", handlerDescriptor(out),
                "matching is by name across the hierarchy, so a live call must not decline");
    }
}
