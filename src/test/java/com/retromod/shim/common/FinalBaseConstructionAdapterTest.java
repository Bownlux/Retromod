/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KubeJS 2001's enchantment builder returns {@code new BasicEnchantment(this)} as an
 * {@code Enchantment}. On 1.21 and newer {@code Enchantment} is final, so verifying the builder
 * loads the subclass and the whole plugin setup stops. These tests pin the rewrite that keeps the
 * builder loadable and reports the problem only when an enchantment is really built.
 */
class FinalBaseConstructionAdapterTest {

    private static final String ENCHANTMENT = "net/minecraft/class_1887";
    private static final String SUBCLASS = "com/example/BasicEnchantment";
    private static final String BUILDER = "com/example/EnchantmentBuilder";
    private static final String OTHER = "com/example/PlainObject";

    /** Only the mod's enchantment subclass extends the now-final base. */
    private static final BiPredicate<String, String> HIERARCHY =
            (child, ancestor) -> ENCHANTMENT.equals(ancestor) && SUBCLASS.equals(child);

    @Test
    void returnedSubclassIsReplacedByAnExplanatoryFailure() throws Exception {
        byte[] result = FinalBaseConstructionAdapter.apply(builder(SUBCLASS, true), "1.21.1", HIERARCHY);

        ClassNode node = read(result);
        assertFalse(createsAny(method(node, "createObject"), SUBCLASS),
                "the builder must not name the subclass, or verifying it loads the final-base subclass");

        // The base is final here, as on a 1.21 host, and the subclass is absent, so any attempt
        // to load it fails. The builder still loads, and building reports why it cannot.
        ByteLoader loader = new ByteLoader();
        loader.define(ENCHANTMENT, finalBase());
        Class<?> builder = loader.define(BUILDER, result);
        Object instance = builder.getConstructor().newInstance();
        Method create = builder.getMethod("createObject");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> create.invoke(instance));
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("com.example.BasicEnchantment"),
                "the message must name the class the mod tried to build: " + failure.getCause().getMessage());
    }

    @Test
    void leavesHostsBeforeTheBaseTurnedFinalAlone() {
        byte[] original = builder(SUBCLASS, true);
        assertSame(original, FinalBaseConstructionAdapter.apply(original, "1.20.4", HIERARCHY),
                "before 1.21 Enchantment can be extended and the builder must stay as written");
    }

    @Test
    void leavesClassesThatDoNotExtendAFinalBaseAlone() {
        byte[] original = builder(OTHER, true);
        assertSame(original, FinalBaseConstructionAdapter.apply(original, "1.21.1", HIERARCHY));
    }

    @Test
    void leavesAConstructionWhoseResultIsNotReturnedAlone() {
        byte[] original = builder(SUBCLASS, false);
        assertSame(original, FinalBaseConstructionAdapter.apply(original, "1.21.1", HIERARCHY),
                "a stored or reused subclass needs its own type on the stack, which the rewrite cannot give");
    }

    @Test
    void namesTheReasonOnlyForAHandledBaseOnAHostWhereItIsFinal() {
        String reason = FinalBaseConstructionAdapter.finalBaseReason(ENCHANTMENT, "1.21.1");
        assertNotNull(reason, "the superclass report needs the reason to stop calling the mod unloadable");
        assertTrue(reason.contains("data packs"), reason);
        assertNull(FinalBaseConstructionAdapter.finalBaseReason(ENCHANTMENT, "1.20.4"),
                "Enchantment is still extendable before 1.21");
        assertNull(FinalBaseConstructionAdapter.finalBaseReason("net/minecraft/class_1792", "1.21.1"),
                "a final base the adapter does not handle keeps the full error");
    }

    /** {@code createObject()} builds {@code created} from {@code this} and returns or drops it. */
    private static byte[] builder(String created, boolean returnsIt) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, BUILDER, null, "java/lang/Object", null);
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "createObject", "()L" + ENCHANTMENT + ";", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, created);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, created, "<init>", "(L" + BUILDER + ";)V", false);
        if (!returnsIt) {
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.ACONST_NULL);
        }
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] finalBase() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, ENCHANTMENT, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean createsAny(MethodNode method, String type) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof TypeInsnNode typeInsn && typeInsn.desc.equals(type)) return true;
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.owner.equals(type)) return true;
        }
        return false;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static final class ByteLoader extends ClassLoader {
        ByteLoader() {
            super(FinalBaseConstructionAdapterTest.class.getClassLoader());
        }

        Class<?> define(String internalName, byte[] bytes) {
            return defineClass(internalName.replace('/', '.'), bytes, 0, bytes.length);
        }
    }
}
