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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Set;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An old mob that overrode {@code LivingEntity.getDimensions(Pose)} fails class definition on
 * 1.20.5 and newer, where that method is final. These tests pin the move onto
 * {@code getDefaultDimensions} and the super-call retarget that prevents recursion.
 */
class FinalOverrideHookAdapterTest {

    private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
    private static final String MONSTER = "net/minecraft/world/entity/monster/Monster";
    private static final String MOB = "com/example/StormEntity";
    private static final String DESC =
            "(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;";

    /** Monster extends LivingEntity; nothing else is known. */
    private static final BiPredicate<String, String> HIERARCHY =
            (child, ancestor) -> LIVING.equals(ancestor) && Set.of(MONSTER, LIVING).contains(child);

    @Test
    void movesTheOverrideOntoTheHookOnModernHosts() {
        ClassNode result = read(FinalOverrideHookAdapter.apply(mob(MONSTER, true, false), "1.21.1", HIERARCHY));

        assertNull(method(result, "getDimensions"),
                "the final method must no longer be overridden, or class definition fails");
        MethodNode hook = method(result, "getDefaultDimensions");
        assertNotNull(hook, "the override body must now live in getDefaultDimensions");
        MethodInsnNode superCall = firstCall(hook);
        assertEquals(Opcodes.INVOKESPECIAL, superCall.getOpcode());
        assertEquals("getDefaultDimensions", superCall.name,
                "super.getDimensions would reach the final method and recurse into the override");
    }

    @Test
    void leavesOldHostsAlone() {
        byte[] original = mob(MONSTER, true, false);
        assertSame(original, FinalOverrideHookAdapter.apply(original, "1.20.4", HIERARCHY),
                "before 1.20.5 getDimensions is overridable and must stay as written");
    }

    @Test
    void leavesClassesOutsideTheLivingEntityHierarchyAlone() {
        byte[] original = mob("net/minecraft/world/entity/Entity", true, false);
        assertSame(original, FinalOverrideHookAdapter.apply(original, "1.21.1", HIERARCHY),
                "Entity.getDimensions is not final, so a plain entity keeps its override");
    }

    @Test
    void leavesClassesThatAlreadyOverrideTheHookAlone() {
        byte[] original = mob(MONSTER, true, true);
        assertSame(original, FinalOverrideHookAdapter.apply(original, "1.21.1", HIERARCHY),
                "a class that already declares the hook cannot take a second one");
    }

    private static byte[] mob(String superName, boolean overridesDimensions, boolean declaresHook) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOB, null, superName, null);
        if (overridesDimensions) dimensionsMethod(cw, "getDimensions", superName);
        if (declaresHook) dimensionsMethod(cw, "getDefaultDimensions", superName);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** {@code return super.<name>(pose);} */
    private static void dimensionsMethod(ClassWriter cw, String name, String superName) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, name, DESC, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(DESC))
                .findFirst().orElse(null);
    }

    private static MethodInsnNode firstCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call in " + method.name);
    }
}
