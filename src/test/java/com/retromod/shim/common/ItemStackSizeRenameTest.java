/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** #384 (Better Than Wolves, Forge 1.20.1 on Forge 26.2): 1.20.5 renamed Item.getMaxStackSize() to getDefaultMaxStackSize(). */
class ItemStackSizeRenameTest {

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("an item's old getMaxStackSize() call reads the default stack size on a 26.2 host")
    void itemStackSizeCallIsRenamed() {
        // Offline the host classes are unreadable, so the target version decides.
        RetromodVersion.TARGET_MC_VERSION = "26.2";
        Legacy1205MemberBridge.register(transformer);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/btw/ProxyRegistry", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "size", "(Lnet/minecraft/world/item/Item;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/item/Item", "getMaxStackSize", "()I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/btw/ProxyRegistry")).accept(node, 0);
        MethodInsnNode call = (MethodInsnNode) Arrays.stream(node.methods.get(0).instructions.toArray())
                .filter(MethodInsnNode.class::isInstance).findFirst().orElseThrow();
        assertEquals("getDefaultMaxStackSize", call.name);
        assertEquals("()I", call.desc);
    }
}
