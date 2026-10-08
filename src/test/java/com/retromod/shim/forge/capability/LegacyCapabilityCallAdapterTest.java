/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.forge.capability;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** The call-site half of the Forge capability bridge (#306, #308). */
class LegacyCapabilityCallAdapterTest {

    private static final String CAP = LegacyCapabilitySynthetics.CAPABILITY;
    private static final String LAZY = LegacyCapabilitySynthetics.LAZY_OPTIONAL;
    private static final String GET2 = "(L" + CAP + ";Lnet/minecraft/core/Direction;)L" + LAZY + ";";
    private static final String GET1 = "(L" + CAP + ";)L" + LAZY + ";";
    private static final String PLAYER = "net/minecraft/world/entity/player/Player";
    private static final String BLOCK_ENTITY = "net/minecraft/world/level/block/entity/BlockEntity";
    private static final String EVENT = LegacyCapabilitySynthetics.ATTACH_EVENT;
    private static final Function<String, byte[]> NO_JAR = name -> null;

    @Test
    void vanillaHolderQueryGoesToTheRuntime() {
        byte[] out = LegacyCapabilityCallAdapter.apply(caller(INVOKEVIRTUAL, PLAYER, GET1), NO_JAR);
        MethodInsnNode call = onlyGetCapabilityLikeCall(out);
        assertEquals(INVOKESTATIC, call.getOpcode(), "Player has no getCapability on NeoForge");
        assertEquals(LegacyCapabilitySynthetics.RUNTIME, call.owner);
        assertEquals("get", call.name);
        assertEquals("(Ljava/lang/Object;L" + CAP + ";)L" + LAZY + ";", call.desc);
    }

    @Test
    void superCallFromAnOverrideSkipsTheOverride() {
        byte[] out = LegacyCapabilityCallAdapter.apply(caller(INVOKESPECIAL, BLOCK_ENTITY, GET2), NO_JAR);
        MethodInsnNode call = onlyGetCapabilityLikeCall(out);
        assertEquals("getAttached", call.name,
                "super.getCapability must not dispatch back into the override that called it");
        assertEquals(INVOKESTATIC, call.getOpcode());
    }

    @Test
    void superCallToAModBaseClassStaysVirtual() {
        String base = "test/mod/BaseMachine";
        Map<String, byte[]> jar = Map.of(base, declaringGetCapability(base));
        byte[] out = LegacyCapabilityCallAdapter.apply(caller(INVOKESPECIAL, base, GET2), jar::get);
        MethodInsnNode call = onlyGetCapabilityLikeCall(out);
        assertEquals(INVOKESPECIAL, call.getOpcode(), "the mod's own base implementation must still run");
        assertEquals(base, call.owner);
    }

    @Test
    void providerInterfaceCallsAreLeftAlone() {
        byte[] in = caller(INVOKEINTERFACE, LegacyCapabilitySynthetics.PROVIDER, GET2);
        assertSame(in, LegacyCapabilityCallAdapter.apply(in, NO_JAR));
    }

    @Test
    void entityAttachListenerIgnoresOtherHolders() {
        byte[] out = LegacyCapabilityCallAdapter.apply(attachListener(
                "(L" + EVENT + "<Lnet/minecraft/world/entity/Entity;>;)V"), NO_JAR);
        MethodNode listener = method(out, "onAttach");
        List<AbstractInsnNode> code = realInstructions(listener);
        assertEquals(ALOAD, code.get(0).getOpcode());
        assertEquals("getObject", ((MethodInsnNode) code.get(1)).name);
        assertEquals("net/minecraft/world/entity/Entity", ((TypeInsnNode) code.get(2)).desc,
                "the listener only sees the holder type it was declared for, as on Forge");
        assertEquals(IFNE, code.get(3).getOpcode());
        assertEquals(RETURN, code.get(4).getOpcode());
    }

    @Test
    void wildcardAttachListenerStaysUnfiltered() {
        byte[] in = attachListener("(L" + EVENT + "<*>;)V");
        assertSame(in, LegacyCapabilityCallAdapter.apply(in, NO_JAR));
    }

    @Test
    void readsTheFirstTypeArgument() {
        assertEquals("net/minecraft/world/item/ItemStack", LegacyCapabilityCallAdapter.firstTypeArgument(
                "(L" + EVENT + "<Lnet/minecraft/world/item/ItemStack;>;)V"));
        assertNull(LegacyCapabilityCallAdapter.firstTypeArgument("(L" + EVENT + "<*>;)V"));
    }

    private static byte[] caller(int opcode, String owner, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Caller", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "query", "(L" + owner + ";L" + CAP + ";)L" + LAZY + ";", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 1);
        m.visitVarInsn(ALOAD, 2);
        if (desc.equals(GET2)) m.visitInsn(ACONST_NULL);
        m.visitMethodInsn(opcode, owner, "getCapability", desc, opcode == INVOKEINTERFACE);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] declaringGetCapability(String name) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, name, null, BLOCK_ENTITY, null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "getCapability", GET2, null, null);
        m.visitCode();
        m.visitInsn(ACONST_NULL);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] attachListener(String signature) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Listener", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(ACC_PUBLIC, "onAttach", "(L" + EVENT + ";)V", signature, null);
        AnnotationVisitor subscribe = m.visitAnnotation("Lnet/neoforged/bus/api/SubscribeEvent;", true);
        subscribe.visitEnd();
        m.visitCode();
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode onlyGetCapabilityLikeCall(byte[] bytes) {
        for (AbstractInsnNode insn : method(bytes, "query").instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call in query()");
    }

    private static MethodNode method(byte[] bytes, String name) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<AbstractInsnNode> realInstructions(MethodNode method) {
        List<AbstractInsnNode> real = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) real.add(insn);
        }
        return real;
    }
}
