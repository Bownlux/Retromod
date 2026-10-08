/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Forge's supplier-built fluid blocks and buckets against NeoForge 1.21's constructors. */
class LegacyFluidSupplierBridgeTest {

    private static final String LIQUID = "net/minecraft/world/level/block/LiquidBlock";
    private static final String BUCKET = "net/minecraft/world/item/BucketItem";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final String BLOCK_PROPS = "Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;";
    private static final String ITEM_PROPS = "Lnet/minecraft/world/item/Item$Properties;";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void aModLiquidBlockReadsItsFluidBeforeTheSuperCall() {
        RetromodTransformer transformer = register();

        List<String> calls = calls(transformer.transformClass(liquidSubclass(), "test/AcidBlock.class"), "<init>");

        assertTrue(calls.contains(LegacyFluidSupplierBridge.HELPER + ".stashLiquidBlock(" + SUPPLIER + BLOCK_PROPS + ")V"),
                "the supplier is read through the stash: " + calls);
        assertTrue(calls.contains(LIQUID + ".<init>(Lnet/minecraft/world/level/material/FlowingFluid;" + BLOCK_PROPS + ")V"),
                "the super call uses NeoForge's fluid constructor: " + calls);
    }

    @Test
    void aNewBucketFromASupplierGoesThroughTheFactory() {
        RetromodTransformer transformer = register();

        List<String> calls = calls(transformer.transformClass(bucketFactory(), "test/ModFluids.class"), "bucket");

        assertTrue(calls.contains(LegacyFluidSupplierBridge.HELPER + ".newBucketItem(" + SUPPLIER + ITEM_PROPS + ")L" + BUCKET + ";"),
                "the supplier bucket goes through the factory: " + calls);
    }

    @Test
    void theGeneratedHelperIsStructurallyValid() throws Exception {
        ClassNode node = node(LegacyFluidSupplierBridge.generateHelper(LegacyFluidSupplierBridge.candidates()));
        for (MethodNode method : node.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(node.name, method);
        }
    }

    private static RetromodTransformer register() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyFluidSupplierBridge.registerRedirects(transformer, LegacyFluidSupplierBridge.candidates());
        return transformer;
    }

    private static byte[] liquidSubclass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/AcidBlock", null, LIQUID, null);
        String desc = "(" + SUPPLIER + BLOCK_PROPS + ")V";
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", desc, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, LIQUID, "<init>", desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] bucketFactory() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "test/ModFluids", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bucket",
                "(" + SUPPLIER + ITEM_PROPS + ")Ljava/lang/Object;", null, null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, BUCKET);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, BUCKET, "<init>", "(" + SUPPLIER + ITEM_PROPS + ")V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> calls(byte[] bytes, String methodName) {
        List<String> calls = new ArrayList<>();
        MethodNode method = node(bytes).methods.stream().filter(m -> m.name.equals(methodName))
                .findFirst().orElseThrow();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
        }
        return calls;
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
