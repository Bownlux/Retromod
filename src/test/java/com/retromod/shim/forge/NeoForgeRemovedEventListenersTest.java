/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Flywheel 1.0.6 on NeoForge 26.3: its constructor adds a lambda listener for the removed DebugText event. */
class NeoForgeRemovedEventListenersTest {

    private static final String OWNER = "mod/FlywheelNeoForge";
    private static final String BUS = "net/neoforged/bus/api/IEventBus";
    private static final String REMOVED = "net/neoforged/neoforge/client/event/CustomizeGuiOverlayEvent$DebugText";
    private static final String KEPT = "net/neoforged/neoforge/event/level/LevelEvent$Unload";
    private static final String PRIORITY = "net/neoforged/bus/api/EventPriority";

    @Test
    @DisplayName("a listener for a removed event is dropped, and one for a kept event stays")
    void dropsOnlyTheRemovedEventsListener() throws Exception {
        byte[] out = NeoForgeRemovedEventListeners.dropRemoved(registrar(), REMOVED::equals);
        MethodNode register = method(out, "register");

        long listeners = Arrays.stream(register.instructions.toArray())
                .filter(i -> i instanceof MethodInsnNode call && call.name.equals("addListener")).count();
        assertEquals(1, listeners, "only the kept event's addListener call should remain");
        for (AbstractInsnNode insn : register.instructions) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                assertEquals("(L" + KEPT + ";)V", ((Type) indy.bsmArgs[2]).getDescriptor(),
                        "the removed event's lambda must not be built, since that links the missing class");
            }
        }
        // The popped arguments leave a method the verifier accepts.
        new Analyzer<>(new BasicVerifier()).analyze(OWNER, register);
    }

    @Test
    @DisplayName("an addListener call that names the removed event's class loses the class literal too")
    void dropsTheExplicitEventClass() throws Exception {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(L" + BUS + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(Type.getObjectType(REMOVED));
        consumer(mv, REMOVED, "onDebugText");
        mv.visitMethodInsn(INVOKEINTERFACE, BUS, "addListener", "(Ljava/lang/Class;Ljava/util/function/Consumer;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        MethodNode register = method(NeoForgeRemovedEventListeners.dropRemoved(cw.toByteArray(), REMOVED::equals), "register");
        for (AbstractInsnNode insn : register.instructions) {
            assertFalse(insn instanceof org.objectweb.asm.tree.LdcInsnNode,
                    "loading the removed class literal would throw before the listener is dropped");
            assertFalse(insn instanceof MethodInsnNode call && call.name.equals("addListener"));
        }
        new Analyzer<>(new BasicVerifier()).analyze(OWNER, register);
    }

    @Test
    @DisplayName("a class without a removed event's listener comes back unchanged")
    void leavesOtherClassesAlone() {
        byte[] in = registrar();
        assertSame(in, NeoForgeRemovedEventListeners.dropRemoved(in, name -> false));
    }

    /** register(bus): bus.addListener(PRIORITY.HIGH, debugText lambda); bus.addListener(unload lambda). */
    private static byte[] registrar() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "register", "(L" + BUS + ";)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETSTATIC, PRIORITY, "HIGH", "L" + PRIORITY + ";");
        consumer(mv, REMOVED, "onDebugText");
        mv.visitMethodInsn(INVOKEINTERFACE, BUS, "addListener", "(L" + PRIORITY + ";Ljava/util/function/Consumer;)V", true);
        mv.visitVarInsn(ALOAD, 0);
        consumer(mv, KEPT, "onUnload");
        mv.visitMethodInsn(INVOKEINTERFACE, BUS, "addListener", "(Ljava/util/function/Consumer;)V", true);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        for (String[] handler : new String[][]{{"onDebugText", REMOVED}, {"onUnload", KEPT}}) {
            MethodVisitor h = cw.visitMethod(ACC_PRIVATE | ACC_STATIC, handler[0], "(L" + handler[1] + ";)V", null, null);
            h.visitCode();
            h.visitInsn(RETURN);
            h.visitMaxs(0, 0);
            h.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void consumer(MethodVisitor mv, String event, String handler) {
        Handle metafactory = new Handle(H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                        + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                        + "Ljava/lang/invoke/CallSite;", false);
        mv.visitInvokeDynamicInsn("accept", "()Ljava/util/function/Consumer;", metafactory,
                Type.getType("(Ljava/lang/Object;)V"),
                new Handle(H_INVOKESTATIC, OWNER, handler, "(L" + event + ";)V", false),
                Type.getType("(L" + event + ";)V"));
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
}
