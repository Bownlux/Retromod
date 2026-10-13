/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Pyrologer and Friends, an MCreator 1.19.2 Forge mod, on a NeoForge 26.3 client: its stairs lost
 * their properties, its overworld biome splice could not link, and its brewing recipes implemented
 * an interface NeoForge removed.
 */
class LegacyContentBridgesTest {

    private static final String BLOCKS = "net/minecraft/world/level/block/";
    private static final String PROPS = "L" + BLOCKS + "state/BlockBehaviour$Properties;";
    private final String savedHost = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void reset() {
        RetromodVersion.TARGET_MC_VERSION = savedHost;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    @DisplayName("Forge's stair constructor with a state supplier keeps the mod's own properties")
    void stairSupplierKeepsProperties() {
        ClassWriter host = new ClassWriter(0);
        host.visit(V17, ACC_PUBLIC, BLOCKS + "StairBlock", null, BLOCKS + "Block", null);
        host.visitMethod(ACC_PUBLIC, "<init>", "(L" + BLOCKS + "state/BlockState;" + PROPS + ")V", null, null).visitEnd();
        host.visitEnd();
        ClassNode parent = read(host.toByteArray());
        Function<String, ClassNode> hostClasses = name -> name.equals(BLOCKS + "StairBlock") ? parent : null;

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/SnowStairs", null, BLOCKS + "StairBlock", null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "(Ljava/util/function/Supplier;" + PROPS + ")V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, BLOCKS + "StairBlock", "<init>", "(Ljava/util/function/Supplier;" + PROPS + ")V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();

        List<String> calls = calls(read(Forge1122BlockConstructorAdapter.apply(cw.toByteArray(), hostClasses)));
        assertEquals(List.of(Forge1122BlockConstructorAdapter.STATES + ".resolve",
                BLOCKS + "StairBlock.<init>(L" + BLOCKS + "state/BlockState;" + PROPS + ")V"), calls,
                "the supplier is read and the properties pass through; no default properties are made");
    }

    @Test
    @DisplayName("an overworld splice handler gets a guard from 1.21.2, and verifies")
    void overworldSpliceIsGuarded() throws Exception {
        String event = "Lnet/neoforged/neoforge/event/server/ServerAboutToStartEvent;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC, "mod/ModBiomes", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "onServerAboutToStart", "(" + event + ")V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "net/minecraft/world/level/levelgen/NoiseGeneratorSettings");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/world/level/levelgen/NoiseGeneratorSettings", "<init>", "()V", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] splice = cw.toByteArray();

        RetromodVersion.TARGET_MC_VERSION = "1.21.1";
        assertSame(splice, LegacyWorldgenBridge.guardOverworldInjection(splice), "older hosts link the splice");

        RetromodVersion.TARGET_MC_VERSION = "26.3";
        ClassNode guarded = read(LegacyWorldgenBridge.guardOverworldInjection(splice));
        MethodNode handler = guarded.methods.stream().filter(m -> m.name.equals("onServerAboutToStart")).findFirst().orElseThrow();
        assertEquals(1, handler.tryCatchBlocks.size());
        assertEquals("java/lang/Throwable", handler.tryCatchBlocks.get(0).type);
        new Analyzer<>(new BasicVerifier()).analyze(guarded.name, handler);
    }

    @Test
    @DisplayName("old brewing recipes implement the generated interface and register through the store")
    void brewingRecipesAreRecorded() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        LegacyBrewingBridge.registerRedirects(transformer);
        String stack = "Lnet/minecraft/world/item/ItemStack;";

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/BlindingBrew", null, "java/lang/Object",
                new String[] {"net/minecraftforge/common/brewing/IBrewingRecipe"});
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "init", "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(NEW, "mod/BlindingBrew");
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKESPECIAL, "mod/BlindingBrew", "<init>", "()V", false);
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/common/brewing/BrewingRecipeRegistry", "addRecipe",
                "(Lnet/minecraftforge/common/brewing/IBrewingRecipe;)Z", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode out = read(transformer.transformClass(cw.toByteArray(), "mod/BlindingBrew.class"));
        assertEquals(List.of(LegacyBrewingBridge.RECIPE), out.interfaces);
        assertTrue(calls(out).contains(LegacyBrewingBridge.STORE + ".addRecipe"), "saw " + calls(out));
        ClassNode recipe = read(LegacyBrewingBridge.generateRecipe());
        assertTrue(recipe.methods.stream().anyMatch(m -> m.name.equals("getOutput") && m.desc.equals("(" + stack + stack + ")" + stack)));
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static List<String> calls(ClassNode node) {
        List<String> calls = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    calls.add(call.owner + "." + call.name + (call.name.equals("<init>") ? call.desc : ""));
                }
            }
        }
        return calls;
    }
}
