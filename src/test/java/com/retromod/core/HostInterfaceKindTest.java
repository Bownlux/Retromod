/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * #249 (Sophisticated Backpacks): {@code IncompatibleClassChangeError: Method
 * 'Lifecycle Lifecycle.stable()' must be Methodref constant}.
 *
 * <p>Retromod rewrites a call to a type that became an interface so it uses the interface opcode
 * and an {@code InterfaceMethodref} constant. Which types those are came from a hardcoded list, and
 * {@code com/mojang/serialization/Lifecycle} was on it. Lifecycle is a plain class in every
 * DataFixerUpper Minecraft has shipped, so every call to it was rewritten into a form the JVM
 * rejects. The same list still claims {@code DataResult} unconditionally, which is right from
 * DataFixerUpper 9 onward and wrong on the DataFixerUpper 6 that Minecraft 1.20.1 ships.
 *
 * <p>The fix reads the kind from the host and keeps the list only as an offline fallback.
 */
class HostInterfaceKindTest {

    private static final String LIFECYCLE = "com/mojang/serialization/Lifecycle";
    private static final String MOD_CLASS = "test/kind/RegistryUser";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        // Any redirect will do; the transform loop exits early when nothing is registered.
        transformer.registerClassRedirect("test/kind/Old", "test/kind/New");
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    private static byte[] callsLifecycleStable() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOD_CLASS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "register", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, LIFECYCLE, "stable", "()L" + LIFECYCLE + ";", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode lifecycleCall(byte[] transformed) {
        ClassNode cn = new ClassNode();
        new ClassReader(transformed).accept(cn, 0);
        MethodNode register = cn.methods.stream()
                .filter(m -> m.name.equals("register")).findFirst().orElseThrow();
        for (AbstractInsnNode insn : register.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(LIFECYCLE)) return call;
        }
        return fail("the call to Lifecycle.stable() disappeared");
    }

    @Test
    @DisplayName("#249: a static call to Lifecycle keeps its Methodref constant")
    void lifecycleCallIsNotRewrittenToInterfaceForm() {
        MethodInsnNode call = lifecycleCall(
                transformer.transformClass(callsLifecycleStable(), MOD_CLASS));

        assertFalse(call.itf,
                "Lifecycle is a class in every DataFixerUpper Minecraft ships, so the call must "
                + "stay a Methodref; marking it an interface is the #249 crash");
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode(),
                "a static call on a class keeps INVOKESTATIC");
    }

    @Test
    @DisplayName("the host decides the kind, not a hardcoded list")
    void hostAnswersTheKind() {
        assertEquals(Boolean.TRUE, SuperclassSafety.isInterfaceOnHost("java/util/List", null));
        assertEquals(Boolean.FALSE, SuperclassSafety.isInterfaceOnHost("java/util/AbstractList", null));
        assertEquals(Boolean.FALSE, SuperclassSafety.isInterfaceOnHost("java/lang/String", null),
                "final still means class, not interface");
    }

    @Test
    @DisplayName("an unreadable name answers unknown so the caller keeps its old behavior")
    void unreadableNameIsUnknown() {
        assertNull(SuperclassSafety.isInterfaceOnHost("net/minecraft/nothing/Here", null),
                "with no indexed jar and nothing on the classpath there is no evidence either way");
        assertEquals(SuperclassSafety.Verdict.UNKNOWN,
                SuperclassSafety.classify("net/minecraft/nothing/Here", null));
    }

    @Test
    @DisplayName("shape verdicts separate extendable from final, interface, and absent")
    void classifiesShapes() {
        assertEquals(SuperclassSafety.Verdict.CLASS,
                SuperclassSafety.classify("java/util/AbstractList", null));
        assertEquals(SuperclassSafety.Verdict.FINAL_CLASS,
                SuperclassSafety.classify("java/lang/String", null));
        assertEquals(SuperclassSafety.Verdict.INTERFACE,
                SuperclassSafety.classify("java/util/List", null));

        assertFalse(SuperclassSafety.cannotBeExtended(SuperclassSafety.Verdict.CLASS));
        assertFalse(SuperclassSafety.cannotBeExtended(SuperclassSafety.Verdict.UNKNOWN),
                "an unreadable host must never be treated as a failure");
        for (SuperclassSafety.Verdict bad : new SuperclassSafety.Verdict[]{
                SuperclassSafety.Verdict.FINAL_CLASS,
                SuperclassSafety.Verdict.INTERFACE,
                SuperclassSafety.Verdict.ABSENT}) {
            assertTrue(SuperclassSafety.cannotBeExtended(bad), bad + " cannot be a superclass");
        }
    }
}
