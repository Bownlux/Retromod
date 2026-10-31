/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * #366: ProjectE PE1.1.0's {@code NovaExplosion} extends {@code Explosion}, which 1.21.2 turned into
 * an interface, so the class could not load on NeoForge 26.2.
 */
class LegacyExplosionBaseBridgeTest {

    private static final String LEVEL = "net/minecraft/world/level/Level";
    private static final String STATE = "net/minecraft/world/level/block/state/BlockState";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("An Explosion subclass extends the generated server explosion, super calls included")
    void subclassMovesOntoTheGeneratedBase() {
        RetromodTransformer transformer = registered("26.2");

        ClassNode nova = read(transformer.transformClass(novaExplosion(), "mod/NovaExplosion.class"));

        assertEquals(LegacyExplosionBaseBridge.BASE, nova.superName);
        assertTrue(calls(nova).contains(LegacyExplosionBaseBridge.BASE + ".<init>" + LegacyExplosionBaseBridge.PLAIN),
                "super(...) keeps its old descriptor on the generated base: " + calls(nova));
        assertTrue(calls(nova).contains(LegacyExplosionBaseBridge.BASE + ".explode()V"),
                "super.explode() names the generated base: " + calls(nova));
    }

    @Test
    @DisplayName("Profiler, block hit, and NeoForge start calls reach the generated helpers")
    void explosionCallsAreRedirected() {
        RetromodTransformer transformer = registered("26.2");
        LegacyExplosionBaseBridge.registerNeoForge(transformer);

        List<String> calls = calls(read(transformer.transformClass(explosionCaller(), "mod/Caller.class")));

        assertTrue(calls.contains(LegacyExplosionBaseBridge.CALLS + ".profiler(L" + LEVEL + ";)Lnet/minecraft/util/profiling/ProfilerFiller;"), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(LegacyExplosionBaseBridge.CALLS + ".onExplosionHit")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith(LegacyExplosionBaseBridge.EVENTS + ".onExplosionStart")), calls.toString());
    }

    @Test
    @DisplayName("A host whose Explosion is still a class is left alone")
    void olderHostsAreLeftAlone() {
        RetromodTransformer transformer = registered("1.21.1");
        assertFalse(transformer.getSyntheticClasses().containsKey(LegacyExplosionBaseBridge.BASE));
    }

    @Test
    @DisplayName("The generated classes verify")
    void generatedClassesVerify() {
        for (byte[] generated : List.of(LegacyExplosionBaseBridge.generateBase(),
                LegacyExplosionBaseBridge.generateRecorder(LegacyExplosionBaseBridge.hostCalculatorMethods()),
                LegacyExplosionBaseBridge.generateCalls(), LegacyExplosionBaseBridge.generateEvents())) {
            new ClassReader(generated).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
        }
    }

    @Test
    @DisplayName("The recorder spares and keeps a block only while collecting")
    void recorderKeepsBlocksWhileCollecting() {
        ClassNode recorder = read(LegacyExplosionBaseBridge.generateRecorder(LegacyExplosionBaseBridge.hostCalculatorMethods()));
        MethodNode shouldBlockExplode = recorder.methods.stream()
                .filter(m -> m.name.equals("shouldBlockExplode")).findFirst().orElseThrow();
        List<String> calls = new ArrayList<>();
        for (AbstractInsnNode insn : shouldBlockExplode.instructions) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name);
        }
        assertEquals(List.of("net/minecraft/world/level/ExplosionDamageCalculator.shouldBlockExplode",
                "net/minecraft/core/BlockPos.immutable", "java/util/Set.add"), calls);
    }

    private static RetromodTransformer registered(String host) {
        RetromodVersion.TARGET_MC_VERSION = host;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyExplosionBaseBridge.register(transformer);
        return transformer;
    }

    /** ProjectE's shape: the plain old constructor, an own finalizeExplosion, and a super call. */
    private static byte[] novaExplosion() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/NovaExplosion", null, LegacyExplosionBaseBridge.EXPLOSION, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", LegacyExplosionBaseBridge.PLAIN, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitVarInsn(DLOAD, 3);
        init.visitVarInsn(DLOAD, 5);
        init.visitVarInsn(DLOAD, 7);
        init.visitVarInsn(FLOAD, 9);
        init.visitVarInsn(ILOAD, 10);
        init.visitVarInsn(ALOAD, 11);
        init.visitMethodInsn(INVOKESPECIAL, LegacyExplosionBaseBridge.EXPLOSION, "<init>", LegacyExplosionBaseBridge.PLAIN, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        MethodVisitor explode = cw.visitMethod(ACC_PUBLIC, "explode", "()V", null, null);
        explode.visitCode();
        explode.visitVarInsn(ALOAD, 0);
        explode.visitMethodInsn(INVOKESPECIAL, LegacyExplosionBaseBridge.EXPLOSION, "explode", "()V", false);
        explode.visitInsn(RETURN);
        explode.visitMaxs(0, 0);
        explode.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] explosionCaller() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_SUPER, "mod/Caller", null, "java/lang/Object", null);
        String explosion = "L" + LegacyExplosionBaseBridge.EXPLOSION + ";";
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "run",
                "(L" + LEVEL + ";L" + STATE + ";Lnet/minecraft/core/BlockPos;" + explosion + ")V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, LEVEL, "getProfiler", "()Lnet/minecraft/util/profiling/ProfilerFiller;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitInsn(ACONST_NULL);
        mv.visitMethodInsn(INVOKEVIRTUAL, STATE, "onExplosionHit",
                "(L" + LEVEL + ";Lnet/minecraft/core/BlockPos;" + explosion + "Ljava/util/function/BiConsumer;)V", false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitMethodInsn(INVOKESTATIC, "net/neoforged/neoforge/event/EventHooks", "onExplosionStart",
                "(L" + LEVEL + ";" + explosion + ")Z", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
