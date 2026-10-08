/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge.capability;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** {@code MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, ...)}, as Wither Storm attaches capabilities. */
class LegacyGenericListenerBridgeTest {

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Without the capability bridge there is no attach event to listen for")
    void needsTheAttachEvent() {
        LegacyGenericListenerBridge.register(transformer);
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyGenericListenerBridge.HELPER));
    }

    @Test
    @DisplayName("The generic listener becomes a filtered listener for the attach event")
    void genericListenerIsFiltered() {
        LegacyCapabilitySynthetics.register(transformer);
        LegacyGenericListenerBridge.register(transformer);
        transformer.registerClassRedirect(LegacyGenericListenerBridge.FORGE_BUS, LegacyGenericListenerBridge.NEO_BUS);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/StormMod", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init",
                "(L" + LegacyGenericListenerBridge.FORGE_BUS + ";Ljava/util/function/Consumer;)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType("net/minecraft/world/entity/Entity"));
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, LegacyGenericListenerBridge.FORGE_BUS, "addGenericListener",
                "(Ljava/lang/Class;Ljava/util/function/Consumer;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/StormMod")).accept(out, 0);
        List<MethodInsnNode> calls = new ArrayList<>();
        out.methods.forEach(m -> m.instructions.forEach(insn -> {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }));
        assertEquals(1, calls.size());
        assertEquals(INVOKESTATIC, calls.get(0).getOpcode(), "NeoForge's bus has no addGenericListener");
        assertEquals(LegacyGenericListenerBridge.HELPER, calls.get(0).owner);
        assertDoesNotThrow(() -> new ClassReader(LegacyGenericListenerBridge.generate()).accept(
                new CheckClassAdapter(new ClassWriter(0), false), 0));
    }
}
