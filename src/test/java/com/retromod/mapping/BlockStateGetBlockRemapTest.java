/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.mapping;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.fabric.Fabric_1_16_5_to_1_17;
import com.retromod.shim.fabric.Fabric_1_21_9_to_1_21_10;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Regression for the 26.2 {@code BlockState.getBlock()} linkage failure. */
class BlockStateGetBlockRemapTest {

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();

    @AfterEach
    void clearRedirects() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("method_26204 remains BlockState.getBlock after the full Fabric shim chain")
    void intermediaryGetBlockMapsToLiveInheritedMethod() {
        transformer.clearRedirectsForTesting();

        // Runtime registration order matters. The old Yarn package move is registered before
        // the intermediary map, which used to create a harmful getBlockType redirect alias.
        new Fabric_1_16_5_to_1_17().registerRedirects(transformer);
        new Fabric_1_21_9_to_1_21_10().registerRedirects(transformer);
        IntermediaryToMojangMapper.applyTo(transformer);

        ClassNode output = new ClassNode();
        new ClassReader(transformer.transformClass(fixture(), "probe/BlockStateCall"))
                .accept(output, 0);
        MethodInsnNode call = output.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("net/minecraft/world/level/block/state/BlockState", call.owner);
        assertEquals("getBlock", call.name);
        assertEquals("()Lnet/minecraft/world/level/block/Block;", call.desc);
    }

    private static byte[] fixture() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "probe/BlockStateCall", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "get", "(Lnet/minecraft/class_2680;)Lnet/minecraft/class_2248;", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_2680",
                "method_26204", "()Lnet/minecraft/class_2248;", false);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
