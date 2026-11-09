/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.fabric.Fabric_1_21_5_to_1_21_6;
import com.retromod.shim.forge.Forge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_10_to_1_21_11;
import com.retromod.shim.neoforge.NeoForge_1_21_5_to_1_21_6;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 1.21.11 moved RenderType into the rendertype package and its static factories into RenderTypes.
 * NeoForge's 1.21.11 shim sent the block layers to a RenderTypes class no version has, and 19 of
 * 200 NeoForge 1.21.1 mods still called entitySolid on the moved RenderType.
 */
class RenderTypeFactoryMovesTest {

    private static final String OLD = "net/minecraft/client/renderer/RenderType";
    private static final String RT = "net/minecraft/client/renderer/rendertype/RenderType";
    private static final String RTS = "net/minecraft/client/renderer/rendertype/RenderTypes";
    private static final String OLD_ID = "Lnet/minecraft/resources/ResourceLocation;";
    private static final String ID = "Lnet/minecraft/resources/Identifier;";

    private final String previousHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = previousHost;
        transformer().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("on 1.21.11 old factories reach RenderTypes under their own names, for NeoForge and Forge")
    void factoriesMoveOn1_21_11() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        for (Runnable shim : List.<Runnable>of(
                () -> new NeoForge_1_21_10_to_1_21_11().registerRedirects(transformer()),
                () -> new Forge_1_21_10_to_1_21_11().registerRedirects(transformer()))) {
            transformer().clearRedirectsForTesting();
            registerHostMoves();
            shim.run();

            assertEquals(List.of(
                    RTS + ".cutoutMovingBlock()L" + RT + ";",
                    RTS + ".entityCutout(" + ID + ")L" + RT + ";",
                    RTS + ".eyes(" + ID + ")L" + RT + ";"), staticCalls(transform()));
        }
    }

    @Test
    @DisplayName("on 26.x entityCutout keeps its 26.1 meaning while other factories keep their names")
    void cullFlipWinsOn26x() {
        RetromodVersion.TARGET_MC_VERSION = "26.1";
        registerHostMoves();
        Common_1_21_11_to_26_1_ClassMoves.registerRenderApiBridges26x(transformer());

        assertEquals(List.of(
                RTS + ".cutoutMovingBlock()L" + RT + ";",
                RTS + ".entityCutoutCull(" + ID + ")L" + RT + ";",
                RTS + ".eyes(" + ID + ")L" + RT + ";"), staticCalls(transform()));
    }

    @Test
    @DisplayName("translucent() becomes translucentMovingBlock() on 1.21.6 to 1.21.10 only")
    void translucentRenameStopsBefore1_21_11() {
        String desc = "()L" + OLD + ";";
        RetromodVersion.TARGET_MC_VERSION = "1.21.8";
        new NeoForge_1_21_5_to_1_21_6().registerRedirects(transformer());
        assertEquals(List.of(OLD + ".translucentMovingBlock" + desc),
                staticCalls(transformer().transformClass(caller(OLD, "translucent", desc), "test/mod/Layers")));

        transformer().clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
        new NeoForge_1_21_5_to_1_21_6().registerRedirects(transformer());
        assertEquals(List.of(OLD + ".translucent" + desc),
                staticCalls(transformer().transformClass(caller(OLD, "translucent", desc), "test/mod/Layers")),
                "1.21.11's own shim moves translucent(), so 1.21.6 must leave it");
    }

    @Test
    @DisplayName("Fabric hosts 1.21.6 to 1.21.10 get the intermediary form of the translucent rename")
    void fabricIntermediaryTranslucentRename() {
        RetromodVersion.TARGET_MC_VERSION = "1.21.8";
        new Fabric_1_21_5_to_1_21_6().registerRedirects(transformer());
        String desc = "()Lnet/minecraft/class_1921;";

        assertEquals(List.of("net/minecraft/class_1921.method_29380" + desc), staticCalls(transformer()
                .transformClass(caller("net/minecraft/class_1921", "method_23583", desc), "test/mod/Layers")));
    }

    private static RetromodTransformer transformer() {
        return RetromodTransformer.getInstance();
    }

    /** The class moves the host index proves on 1.21.11 and newer. */
    private static void registerHostMoves() {
        transformer().registerClassRedirect(OLD, RT);
        transformer().registerClassRedirect("net/minecraft/resources/ResourceLocation", "net/minecraft/resources/Identifier");
    }

    /** A 1.21.1 renderer calling RenderType.cutout(), entityCutout(id) and eyes(id). */
    private static byte[] transform() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/mod/Layers", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "layers", "(" + OLD_ID + ")V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OLD, "cutout", "()L" + OLD + ";", false);
        mv.visitInsn(Opcodes.POP);
        for (String factory : new String[] {"entityCutout", "eyes"}) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, OLD, factory, "(" + OLD_ID + ")L" + OLD + ";", false);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writer.visitEnd();
        return transformer().transformClass(writer.toByteArray(), "test/mod/Layers");
    }

    /** A class with one static call to the named no-argument method. */
    private static byte[] caller(String owner, String name, String desc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/mod/Layers", null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "layer", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, name, desc, false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static List<String> staticCalls(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
                    calls.add(call.owner + "." + call.name + call.desc);
                }
            }
        }
        return calls;
    }
}
