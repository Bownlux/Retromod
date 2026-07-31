/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.polyfill;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.polyfill.minecraft.registry.RegistryPolyfill;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for the {@code new ResourceLocation(ns, path)} VerifyError (#135 Wyrms of Nyrus, and the
 * same root cause behind #136 / #121). {@link RegistryPolyfill} registered the ctor->factory
 * conversion with a plain method redirect, which only swapped the {@code Methodref} and left the
 * uninitialized {@code NEW}/{@code DUP} on the stack plus an {@code INVOKESPECIAL} against the factory
 * -> "Type uninitialized 0 ... is not assignable" at class load. It must go through
 * {@code registerConstructorRedirect} (which strips {@code NEW}/{@code DUP} and emits
 * {@code INVOKESTATIC}), and only on hosts >= 1.20.5 where the constructor was actually removed.
 */
class ResourceLocationCtorRedirectTest {

    private static final String RL = "net/minecraft/resources/ResourceLocation";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    /** A static factory using `return new ResourceLocation("modid", arg)`, the usual 1.12.2 form. */
    private static byte[] callerWithNewResourceLocation() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Res", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getResource", "(Ljava/lang/String;)L" + RL + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, RL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("modid");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RL, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode transformAndRead(String hostVersion) {
        RetromodVersion.TARGET_MC_VERSION = hostVersion;
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new RegistryPolyfill().registerPolyfills(t);

        byte[] out = t.transformClass(callerWithNewResourceLocation(), "test/mod/Res.class");
        assertNotNull(out);
        ClassNode cn = new ClassNode();
        new ClassReader(out).accept(cn, 0);
        return cn.methods.stream().filter(m -> m.name.equals("getResource")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("host >= 1.20.5: NEW/DUP stripped, call is INVOKESTATIC fromNamespaceAndPath (verifiable)")
    void ctorRedirectStripsNewOn26() {
        MethodNode m = transformAndRead("26.1");

        boolean hasNew = false, invokespecialInit = false, invokestaticFactory = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) hasNew = true;
            if (insn instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")) invokespecialInit = true;
                if (mi.getOpcode() == Opcodes.INVOKESTATIC && mi.name.equals("fromNamespaceAndPath")) invokestaticFactory = true;
            }
        }
        assertFalse(hasNew, "the uninitialized NEW ResourceLocation must be stripped (else VerifyError)");
        assertFalse(invokespecialInit, "the <init> INVOKESPECIAL must be gone");
        assertTrue(invokestaticFactory, "the call must become INVOKESTATIC fromNamespaceAndPath");
    }

    @Test
    @DisplayName("host >= 1.20.5: new ResourceLocation(String) -> INVOKESTATIC parse, NEW stripped")
    void oneArgCtorRedirectToParse() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        new RegistryPolyfill().registerPolyfills(t);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/mod/Res1", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "make", "()L" + RL + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, RL);
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("mymod:thing");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, RL, "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(t.transformClass(cw.toByteArray(), "test/mod/Res1.class")).accept(cn, 0);
        MethodNode m = cn.methods.stream().filter(x -> x.name.equals("make")).findFirst().orElseThrow();

        boolean hasNew = false, invokestaticParse = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) hasNew = true;
            if (insn instanceof MethodInsnNode mi
                    && mi.getOpcode() == Opcodes.INVOKESTATIC && mi.name.equals("parse")) invokestaticParse = true;
        }
        assertFalse(hasNew, "the single-arg new ResourceLocation must be stripped too");
        assertTrue(invokestaticParse, "new ResourceLocation(String) must become INVOKESTATIC parse");
    }

    @Test
    @DisplayName("host 1.20.1: ctor still exists, so the redirect does NOT fire (new + <init> preserved)")
    void ctorRedirectGatedOffOn1_20_1() {
        MethodNode m = transformAndRead("1.20.1");

        boolean hasNew = false, invokespecialInit = false, factory = false;
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) hasNew = true;
            if (insn instanceof MethodInsnNode mi) {
                if (mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>")) invokespecialInit = true;
                if (mi.name.equals("fromNamespaceAndPath")) factory = true;
            }
        }
        assertTrue(hasNew, "on 1.20.1 the constructor still exists; NEW must be left intact");
        assertTrue(invokespecialInit, "on 1.20.1 the <init> call must be left intact");
        assertFalse(factory, "the ctor->factory redirect must NOT fire on 1.20.1");
    }
}
