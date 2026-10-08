/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Wither Storm builds its block cluster entity type with Forge's {@code setCustomClientFactory},
 * which NeoForge 1.21 removed together with {@code PlayMessages.SpawnEntity}.
 */
class LegacyEntityClientFactoryBridgeTest {

    private static final String BUILDER = "net/minecraft/world/entity/EntityType$Builder";
    private static final String L_BUILDER = "L" + BUILDER + ";";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void reset() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    void theClientFactoryCallIsDroppedAndTheBuilderChainContinues() {
        LegacyEntityClientFactoryBridge.register(transformer);

        ClassNode mod = read(transformer.transformClass(entityTypes(), "com/example/ModEntityTypes"));
        MethodInsnNode call = null;
        for (MethodNode method : mod.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode m && m.name.equals("setCustomClientFactory")) call = m;
            }
        }
        assertNotNull(call, "the call is redirected, not deleted");
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
        assertEquals(LegacyEntityClientFactoryBridge.HELPER, call.owner);
        assertEquals(LegacyEntityClientFactoryBridge.HELPER_DESC, call.desc);
    }

    @Test
    void theStandInsAreRegisteredForEmbedding() {
        LegacyEntityClientFactoryBridge.register(transformer);
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEntityClientFactoryBridge.SPAWN_ENTITY),
                "the factory lambda's signature loads SpawnEntity when it links");
        assertTrue(transformer.getSyntheticClasses().containsKey(LegacyEntityClientFactoryBridge.HELPER));
    }

    @Test
    void theHelperReturnsTheBuilderItWasGiven() {
        // The helper's signature names Minecraft's builder, so it is checked by shape here: the body
        // is "return the first argument".
        ClassNode helper = read(LegacyEntityClientFactoryBridge.generateHelper());
        MethodNode drop = helper.methods.stream()
                .filter(m -> m.name.equals("setCustomClientFactory")).findFirst().orElseThrow();
        assertEquals(Opcodes.ACC_STATIC, drop.access & Opcodes.ACC_STATIC);
        AbstractInsnNode first = drop.instructions.getFirst();
        while (first.getOpcode() < 0) first = first.getNext();
        assertEquals(Opcodes.ALOAD, first.getOpcode());
        assertEquals(Opcodes.ARETURN, first.getNext().getOpcode());

        ClassNode standIn = read(LegacyEntityClientFactoryBridge.generateSpawnEntityStandIn());
        assertTrue(standIn.fields.isEmpty(), "the stand-in carries nothing");
        assertEquals(1, standIn.methods.size(), "only the private constructor");
        assertEquals(Opcodes.ACC_PRIVATE, standIn.methods.get(0).access & Opcodes.ACC_PRIVATE);
    }

    /** {@code EntityType.Builder b; b.setCustomClientFactory(factory)} as Forge 1.20.1 compiled it. */
    private static byte[] entityTypes() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/ModEntityTypes", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "build",
                "(" + L_BUILDER + "Ljava/util/function/BiFunction;)" + L_BUILDER, null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUILDER, "setCustomClientFactory",
                "(Ljava/util/function/BiFunction;)" + L_BUILDER, false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
