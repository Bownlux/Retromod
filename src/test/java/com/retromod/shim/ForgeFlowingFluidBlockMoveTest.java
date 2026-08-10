/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.mapping.SrgToMojangMapper;
import com.retromod.shim.forge.Forge_1_16_5_to_1_17;

/** Pins the first missing class reported by YUNG's Better Portals on Forge 1.20.1. */
class ForgeFlowingFluidBlockMoveTest {
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodTransformer.getInstance().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
    }

    @Test
    void flowingFluidBlockBecomesLiquidBlock() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_16_5_to_1_17().registerRedirects(transformer);

        byte[] output = transformer.transformClass(portalFluidBlock(),
                "example/PortalFluidBlock");

        assertEquals("net/minecraft/world/level/block/LiquidBlock",
                new ClassReader(output).getSuperName());
    }

    @Test
    void legacyMcpMovesUseTheHostValidated1201Table() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        new Forge_1_16_5_to_1_17().registerRedirects(transformer);

        byte[] output = transformer.transformClass(subclass("net/minecraft/block/ContainerBlock"),
                "example/ReclaimerBlock");

        assertEquals("net/minecraft/world/level/block/BaseEntityBlock",
                new ClassReader(output).getSuperName());
    }

    @Test
    void removedMaterialFactoryDropsItsLegacyArguments() {
        RetromodVersion.TARGET_MC_VERSION = "1.20.1";
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        SrgToMojangMapper.getInstance().applyTo(transformer);
        new Forge_1_16_5_to_1_17().registerRedirects(transformer);

        byte[] output = transformer.transformClass(oldMaterialFactory(),
                "example/OldMaterialFactory");
        ClassNode node = new ClassNode();
        new ClassReader(output).accept(node, 0);
        MethodInsnNode call = node.methods.stream()
                .flatMap(method -> java.util.Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance)
                .map(MethodInsnNode.class::cast)
                .findFirst()
                .orElseThrow();

        assertEquals("net/minecraft/world/level/block/state/BlockBehaviour$Properties",
                call.owner);
        assertEquals("of", call.name);
        assertEquals("()Lnet/minecraft/world/level/block/state/BlockBehaviour$Properties;",
                call.desc);
    }

    private static byte[] portalFluidBlock() {
        return subclass("net/minecraft/block/FlowingFluidBlock");
    }

    private static byte[] subclass(String superName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/PortalFluidBlock", null,
                superName, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] oldMaterialFactory() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/OldMaterialFactory", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "create", "(Lnet/minecraft/block/material/Material;"
                        + "Lnet/minecraft/block/material/MaterialColor;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKESTATIC,
                "net/minecraft/block/AbstractBlock$Properties", "func_200945_a",
                "(Lnet/minecraft/block/material/Material;"
                        + "Lnet/minecraft/block/material/MaterialColor;)"
                        + "Lnet/minecraft/block/AbstractBlock$Properties;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
