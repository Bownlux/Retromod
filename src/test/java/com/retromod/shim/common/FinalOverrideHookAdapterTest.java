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

    @Test
    void movesIsAlliedToOntoConsidersEntityAsAlly() {
        String entity = "net/minecraft/world/entity/Entity";
        String desc = "(Lnet/minecraft/world/entity/Entity;)Z";
        BiPredicate<String, String> hierarchy = (child, ancestor) -> entity.equals(ancestor) && child.equals(MONSTER);
        byte[] tamed = overriding(MONSTER, "isAlliedTo", desc, true);

        ClassNode result = read(FinalOverrideHookAdapter.apply(tamed, "26.2", hierarchy));
        MethodNode hook = named(result, "considersEntityAsAlly");
        assertNotNull(hook, "the ally rule of an old tamed mob runs from the hook the final isAlliedTo asks");
        assertEquals("considersEntityAsAlly", firstCall(hook).name, "its super call follows, or it recurses");
        assertSame(tamed, FinalOverrideHookAdapter.apply(tamed, "1.21.1", hierarchy),
                "1.21.1 still lets a mob override isAlliedTo");
        assertNotNull(named(read(FinalOverrideHookAdapter.apply(tamed, "1.21.2", hierarchy)), "considersEntityAsAlly"),
                "1.21.2 made isAlliedTo final over considersEntityAsAlly");
    }

    @Test
    void movesA12111ButtonsRenderWidgetOntoRenderContentsInBothNamings() {
        String[][] namings = {
            {"net/minecraft/client/gui/components/AbstractButton", "renderWidget",
                "(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", "renderContents"},
            {"net/minecraft/class_4264", "method_48579", "(Lnet/minecraft/class_332;IIF)V", "method_75752"}};
        for (String[] naming : namings) {
            BiPredicate<String, String> hierarchy = (child, ancestor) -> naming[0].equals(ancestor) && child.equals(naming[0]);
            byte[] button = overriding(naming[0], naming[1], naming[2], false);
            assertNotNull(named(read(FinalOverrideHookAdapter.apply(button, "1.21.11", hierarchy)), naming[3]), naming[3]);
            assertSame(button, FinalOverrideHookAdapter.apply(button, "1.21.10", hierarchy),
                    "1.21.10 still lets a button override renderWidget");
        }
    }

    @Test
    void movesAnIntermediaryListsUpdateNarrationOntoUpdateWidgetNarration() {
        String widget = "net/minecraft/class_339";
        BiPredicate<String, String> hierarchy = (child, ancestor) -> widget.equals(ancestor) && child.equals(MONSTER);
        byte[] list = overriding(MONSTER, "method_37020", "(Lnet/minecraft/class_6382;)V", false);

        assertNotNull(named(read(FinalOverrideHookAdapter.apply(list, "1.21.1", hierarchy)), "method_47399"));
    }

    @Test
    void movesAButtonsRenderOntoExtractContentsUnlessItCallsTheAbstractSuper() {
        String button = "net/minecraft/client/gui/components/AbstractButton";
        String desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";
        BiPredicate<String, String> hierarchy = (child, ancestor) -> button.equals(ancestor) && child.equals(button);

        byte[] ownDrawing = overriding(button, "extractWidgetRenderState", desc, false);
        assertNotNull(named(read(FinalOverrideHookAdapter.apply(ownDrawing, "26.2", hierarchy)), "extractContents"),
                "an old button's drawing runs where the final extractWidgetRenderState draws");

        byte[] superDrawing = overriding(button, "extractWidgetRenderState", desc, true);
        assertSame(superDrawing, FinalOverrideHookAdapter.apply(superDrawing, "26.2", hierarchy),
                "AbstractButton.extractContents is abstract, so a super call there would fail");
    }

    /** A class that overrides {@code name desc}, optionally calling the super method first. */
    private static byte[] overriding(String superName, String name, String desc, boolean callsSuper) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, MOB, null, superName, null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitCode();
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(desc);
        if (callsSuper) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            int slot = 1;
            for (org.objectweb.asm.Type arg : args) {
                mv.visitVarInsn(arg.getOpcode(Opcodes.ILOAD), slot);
                slot += arg.getSize();
            }
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, name, desc, false);
        } else if (org.objectweb.asm.Type.getReturnType(desc).getSort() != org.objectweb.asm.Type.VOID) {
            mv.visitInsn(Opcodes.ICONST_1);
        }
        mv.visitInsn(org.objectweb.asm.Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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

    private static MethodNode named(ClassNode node, String name) {
        return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }

    private static MethodInsnNode firstCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) return call;
        }
        throw new AssertionError("no call in " + method.name);
    }
}
