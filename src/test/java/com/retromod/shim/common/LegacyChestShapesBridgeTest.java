/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Lootr's chest reads ChestBlock.AABB through its own class, and the constant went in 1.21.5. */
class LegacyChestShapesBridgeTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void reset() {
        transformer.clearJarClassBytesProvider();
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("a chest subclass's read of the old constant becomes the generated shape getter")
    void subclassReadReachesTheGetter() {
        transformer.clearRedirectsForTesting();
        LegacyChestShapesBridge.register(transformer, name -> chest(false), "26.2");

        // The inherited read is proven from the jar's own classes, so the chest is in the jar.
        byte[] chest = lootrChest();
        transformer.setJarClassBytesProvider(name -> name.equals("mod/LootrChestBlock") ? chest : null);
        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(chest, "mod/LootrChestBlock")).accept(node, 0);

        MethodNode shape = node.methods.stream().filter(m -> m.name.equals("getShape")).findFirst().orElseThrow();
        boolean called = false;
        for (AbstractInsnNode insn : shape.instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(LegacyChestShapesBridge.SHAPES)
                    && call.name.equals("AABB")) {
                called = true;
            }
        }
        assertTrue(called, "the read goes to the generated getter");
    }

    @Test
    @DisplayName("a host whose ChestBlock still has AABB keeps it, and offline the version decides")
    void hostShapeDecides() {
        assertFalse(LegacyChestShapesBridge.hostDropped(chest(true), "26.2"));
        assertTrue(LegacyChestShapesBridge.hostDropped(chest(false), "1.21.1"));
        assertFalse(LegacyChestShapesBridge.hostDropped(null, "1.21.4"));
        assertTrue(LegacyChestShapesBridge.hostDropped(null, "1.21.5"));
    }

    @Test
    @DisplayName("the generated shapes class verifies")
    void generatedClassVerifies() {
        new ClassReader(LegacyChestShapesBridge.generate()).accept(new CheckClassAdapter(new ClassWriter(0), true), 0);
    }

    private static ClassNode chest(boolean withAabb) {
        ClassNode node = new ClassNode();
        node.name = LegacyChestShapesBridge.CHEST;
        if (withAabb) node.fields.add(new FieldNode(ACC_PROTECTED | ACC_STATIC, "AABB", LegacyChestShapesBridge.SHAPE, null, null));
        return node;
    }

    /** {@code class LootrChestBlock extends ChestBlock} whose getShape returns {@code AABB}, read through itself. */
    private static byte[] lootrChest() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/LootrChestBlock", null, LegacyChestShapesBridge.CHEST, null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getShape", "()" + LegacyChestShapesBridge.SHAPE, null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, "mod/LootrChestBlock", "AABB", LegacyChestShapesBridge.SHAPE);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
