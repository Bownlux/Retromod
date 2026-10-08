/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * On a Forge 1.20.1 host members run under SRG names, and the target table is keyed by the class
 * that declares each member. A mod class overriding {@code getLightBlock} or reading an inherited
 * field names itself as the owner, so its members kept Mojang names: the override was never
 * called and the field read failed. MCreator blocks lit and dropped wrongly as a result.
 */
class InheritedTargetSrgTest {

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BEHAVIOUR = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String MOD_BLOCK = "test/mod/GlowBlock";
    private static final String LIGHT_DESC = "(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)I";

    private RetromodTransformer transformer;
    private final Map<String, byte[]> jar = new HashMap<>();

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        TargetSrgMapper.forVersion("1.20.1").applyTo(transformer);
        // No Minecraft jar in tests: give the hierarchy Block extends BlockBehaviour. The mod's
        // own classes join it in transform(), as every transform path supplies the jar's classes.
        jar.put(BLOCK, stub(BLOCK, BEHAVIOUR));
        jar.put(BEHAVIOUR, stub(BEHAVIOUR, "java/lang/Object"));
        transformer.setJarClassBytesProvider(jar::get);
    }

    @AfterEach
    void tearDown() {
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("an override declared on a mod block takes the SRG name of the vanilla method")
    void overrideTakesTheInheritedSrgName() {
        String expected = transformer.remapQualifiedMethodName(BEHAVIOUR, "getLightBlock", LIGHT_DESC);
        assertNotEquals("getLightBlock", expected, "the 1.20.1 table must name getLightBlock");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD_BLOCK, null, BLOCK, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getLightBlock", LIGHT_DESC, null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());
        assertTrue(out.methods.stream().anyMatch(m -> m.name.equals(expected)),
                "the override must be renamed to " + expected);
    }

    @Test
    @DisplayName("a field read through the mod class takes the inherited SRG name")
    void inheritedFieldTakesItsSrgName() {
        String expected = transformer.remapQualifiedFieldName(BEHAVIOUR, "hasCollision", "Z");
        assertNotEquals("hasCollision", expected, "the 1.20.1 table must name hasCollision");

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD_BLOCK, null, BLOCK, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "solid", "()Z", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, MOD_BLOCK, "hasCollision", "Z");
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = transform(cw.toByteArray());
        MethodNode solid = out.methods.stream().filter(m -> m.name.equals("solid")).findFirst().orElseThrow();
        FieldInsnNode read = (FieldInsnNode) java.util.Arrays.stream(solid.instructions.toArray())
                .filter(FieldInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals(expected, read.name);
    }

    @Test
    @DisplayName("the mod's own methods keep their names")
    void unrelatedMethodsKeepTheirNames() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, MOD_BLOCK, null, BLOCK, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "glowLevel", "()I", null, null);
        mv.visitCode();
        mv.visitInsn(ICONST_0);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        assertTrue(transform(cw.toByteArray()).methods.stream().anyMatch(m -> m.name.equals("glowLevel")));
    }

    private ClassNode transform(byte[] bytes) {
        jar.put(MOD_BLOCK, bytes);
        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(bytes, MOD_BLOCK)).accept(out, 0);
        return out;
    }

    private static byte[] stub(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT, name, null, superName, null);
        cw.visitEnd();
        return cw.toByteArray();
    }
}
