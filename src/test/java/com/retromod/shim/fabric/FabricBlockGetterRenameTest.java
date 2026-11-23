/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.objectweb.asm.Opcodes.*;

/** A Fabric block entity built against the block view API, as Lootr's are, on a 26.x host's block getter API. */
class FabricBlockGetterRenameTest {

    private static final String OLD = "net/fabricmc/fabric/api/blockview/v2/";
    private static final String NEW = "net/fabricmc/fabric/api/blockgetter/v2/";

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void blockViewTypesBecomeTheBlockGetterOnes() {
        RetromodVersion.TARGET_MC_VERSION = "26.3";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Fabric_1_21_11_to_26_1().registerRedirects(transformer);

        ClassNode node = new ClassNode();
        new ClassReader(transformer.transformClass(blockEntity(), "mod/ChestEntity.class")).accept(node, 0);

        assertEquals(List.of(NEW + "RenderDataBlockEntity"), node.interfaces, "the block entity still offers render data");
        MethodInsnNode call = null;
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode method && method.name.equals("getBlockEntityRenderData")) call = method;
        }
        assertEquals(NEW + "FabricBlockGetter", call.owner);
    }

    /** A block entity that offers render data and reads a neighbour's through the level. */
    private static byte[] blockEntity() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC, "mod/ChestEntity", null, "java/lang/Object", new String[]{OLD + "RenderDataBlockEntity"});
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "neighbour",
                "(L" + OLD + "FabricBlockView;Lnet/minecraft/core/BlockPos;)Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEINTERFACE, OLD + "FabricBlockView", "getBlockEntityRenderData",
                "(Lnet/minecraft/core/BlockPos;)Ljava/lang/Object;", true);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
