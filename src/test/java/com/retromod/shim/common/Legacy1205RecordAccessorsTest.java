/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #398: Epic Core API (Forge 1.20.1) reads entity data ids in its {@code LivingEntity} mixin while Minecraft bootstraps.
 * 1.20.5 made {@code EntityDataAccessor} a record, so on NeoForge 1.21.1 the game stopped with
 * {@code NoSuchMethodError: EntityDataAccessor.getId()} before the server started.
 */
class Legacy1205RecordAccessorsTest {

    private static final String ACCESSOR = "net/minecraft/network/syncher/EntityDataAccessor";
    private static final String SERIALIZER = "()Lnet/minecraft/network/syncher/EntityDataSerializer;";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("the old getters become the record accessors on NeoForge 1.21.1 and 26.3")
    void gettersBecomeRecordAccessors() {
        for (String host : List.of("1.21.1", "26.3")) {
            RetromodVersion.TARGET_MC_VERSION = host;
            transformer.clearRedirectsForTesting();
            Legacy1205MemberBridge.registerRecordAccessors(transformer);

            List<String> calls = calls(transformer.transformClass(mixinReadingIds(), "mod/mixin/LivingEntityMixin"));

            assertTrue(calls.contains(ACCESSOR + ".id()I"), host + ": " + calls);
            assertTrue(calls.contains(ACCESSOR + ".serializer" + SERIALIZER), host + ": " + calls);
            assertFalse(calls.contains(ACCESSOR + ".getId()I"), host + ": " + calls);
        }
    }

    /** A mixin's static handler that reads an accessor's id and serializer, as Epic Core API's does. */
    private static byte[] mixinReadingIds() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "mod/mixin/LivingEntityMixin", null, "java/lang/Object", null);
        cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, "onClinit", "(L" + ACCESSOR + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ACCESSOR, "getId", "()I", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, ACCESSOR, "getSerializer", SERIALIZER, false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
