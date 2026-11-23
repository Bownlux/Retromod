/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

import static org.objectweb.asm.Opcodes.*;

/**
 * Lets old target goals and targeting conditions keep their entity predicates where 1.21.2 replaced them with a
 * selector that also takes the server level: the predicate is wrapped in a selector that ignores the level.
 */
public final class LegacyTargetSelectorBridge {

    public static final String WRAPPER = "com/retromod/generated/LegacyTargetSelector";
    static final String GOAL = "net/minecraft/world/entity/ai/goal/target/NearestAttackableTargetGoal";
    static final String CONDITIONS = "net/minecraft/world/entity/ai/targeting/TargetingConditions";
    static final String SELECTOR = CONDITIONS + "$Selector";
    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String OF = "(L" + PREDICATE + ";)L" + SELECTOR + ";";

    private LegacyTargetSelectorBridge() {}

    /** Registers the wrapper where the host's goal takes a selector and no predicate; the adapter keys on it. */
    public static void register(RetromodTransformer t) {
        ClassNode goal = ClassResourceInspector.read(GOAL);
        if (goal == null || t.getSyntheticClasses().containsKey(WRAPPER)) return;
        boolean selectors = goal.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.contains("L" + SELECTOR + ";"));
        boolean predicates = goal.methods.stream().anyMatch(m -> m.name.equals("<init>") && m.desc.contains("L" + PREDICATE + ";"));
        if (selectors && !predicates) t.registerSyntheticClass(WRAPPER, generate());
    }

    /** Wraps the predicate passed to a goal constructor or to TargetingConditions.selector, in a mod class. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null) return null;
        String text = new String(classBytes, StandardCharsets.ISO_8859_1);
        if (!text.contains(GOAL) && !text.contains(CONDITIONS)) return classBytes;
        ClassNode node = new ClassNode();
        try {
            new ClassReader(classBytes).accept(node, 0);
        } catch (RuntimeException unreadable) {
            return classBytes;
        }
        if (SyntheticEmbedder.isRetromodClass(node.name) || node.name.startsWith("net/minecraft/")) return classBytes;
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !takesPredicateLast(call)) continue;
                // The predicate is the last argument, so it is on top of the stack: wrap it there.
                method.instructions.insertBefore(call, new MethodInsnNode(INVOKESTATIC, WRAPPER, "of", OF, false));
                call.desc = call.desc.replace("L" + PREDICATE + ";)", "L" + SELECTOR + ";)");
                changed++;
            }
        }
        if (changed == 0) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean takesPredicateLast(MethodInsnNode call) {
        boolean goal = call.owner.equals(GOAL) && call.name.equals("<init>") && call.getOpcode() == INVOKESPECIAL;
        boolean conditions = call.owner.equals(CONDITIONS) && call.name.equals("selector");
        return (goal || conditions) && call.desc.contains("L" + PREDICATE + ";)");
    }

    /** {@code LegacyTargetSelector implements Selector}: tests the entity with the old predicate. */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, WRAPPER, null, "java/lang/Object", new String[] {SELECTOR});
        cw.visitField(ACC_PRIVATE | ACC_FINAL, "predicate", "L" + PREDICATE + ";", null, null).visitEnd();
        MethodVisitor init = cw.visitMethod(ACC_PRIVATE, "<init>", "(L" + PREDICATE + ";)V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitFieldInsn(PUTFIELD, WRAPPER, "predicate", "L" + PREDICATE + ";");
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        // of(null) stays null, which the host reads as no condition, as the old constructors did.
        MethodVisitor of = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "of", OF, null, null);
        of.visitCode();
        Label wrap = new Label();
        of.visitVarInsn(ALOAD, 0);
        of.visitJumpInsn(IFNONNULL, wrap);
        of.visitInsn(ACONST_NULL);
        of.visitInsn(ARETURN);
        of.visitLabel(wrap);
        of.visitTypeInsn(NEW, WRAPPER);
        of.visitInsn(DUP);
        of.visitVarInsn(ALOAD, 0);
        of.visitMethodInsn(INVOKESPECIAL, WRAPPER, "<init>", "(L" + PREDICATE + ";)V", false);
        of.visitInsn(ARETURN);
        of.visitMaxs(0, 0);
        of.visitEnd();
        MethodVisitor test = cw.visitMethod(ACC_PUBLIC, "test",
                "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/server/level/ServerLevel;)Z", null, null);
        test.visitCode();
        test.visitVarInsn(ALOAD, 0);
        test.visitFieldInsn(GETFIELD, WRAPPER, "predicate", "L" + PREDICATE + ";");
        test.visitVarInsn(ALOAD, 1);
        test.visitMethodInsn(INVOKEINTERFACE, PREDICATE, "test", "(Ljava/lang/Object;)Z", true);
        test.visitInsn(IRETURN);
        test.visitMaxs(0, 0);
        test.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
