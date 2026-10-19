/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticLibraries;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Rockhounding Rocks' rock blocks extend its own {@code MetaIO}, which extends Rockhounding Core's
 * {@code BaseMetaBlock}, which extends {@code Block}. Each rock block calls Forge 1.12's removed
 * {@code setHarvestLevel} on itself, so the call has to be dropped even though the block's direct
 * superclass is a mod class, partly in another jar.
 */
class Forge1122BlockChainAdapterTest {

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
    private static final String HARVEST = "(Ljava/lang/String;I" + STATE + ")V";
    private static final String CORE_BASE = "core/blocks/BaseMetaBlock";
    private static final String ADDON_BASE = "addon/blocks/io/MetaIO";
    private static final String ROCK = "addon/blocks/RocksA";

    @AfterEach
    void reset() {
        RetromodTransformer.getInstance().clearLinkedLibraries();
    }

    @Test
    @DisplayName("setHarvestLevel on a block built on mod base classes is dropped, and the block verifies")
    void harvestLevelThroughModBasesIsDropped() throws Exception {
        Function<String, ClassNode> chain = classes(Map.of(
                BLOCK, node(type(BLOCK, "java/lang/Object")),
                CORE_BASE, node(type(CORE_BASE, BLOCK)),
                ADDON_BASE, node(type(ADDON_BASE, CORE_BASE))));

        ClassNode adapted = node(Forge1122BlockConstructorAdapter.apply(rockCallingSetHarvestLevel(), chain));

        assertFalse(calls(adapted).contains(ROCK + ".setHarvestLevel" + HARVEST),
                "the removed Forge setter is not called on the rock block any more");
        for (MethodNode method : adapted.methods) {
            new Analyzer<>(new BasicVerifier()).analyze(adapted.name, method);
        }
    }

    @Test
    @DisplayName("a mod base that declares its own setHarvestLevel keeps the call")
    void ownSetHarvestLevelIsKept() throws Exception {
        ClassWriter base = type(ADDON_BASE, CORE_BASE);
        // Read before translation, so the parameter still names the 1.12 state type.
        base.visitMethod(ACC_PUBLIC, "setHarvestLevel",
                "(Ljava/lang/String;ILnet/minecraft/block/state/IBlockState;)V", null, null).visitEnd();
        Function<String, ClassNode> chain = classes(Map.of(
                BLOCK, node(type(BLOCK, "java/lang/Object")),
                CORE_BASE, node(type(CORE_BASE, BLOCK)),
                ADDON_BASE, node(base)));

        byte[] rock = rockCallingSetHarvestLevel();
        assertSame(rock, Forge1122BlockConstructorAdapter.apply(rock, chain));
    }

    @Test
    @DisplayName("a chain that cannot be followed to Minecraft leaves the block unchanged")
    void unreadableChainIsLeftAlone() {
        Function<String, ClassNode> chain = classes(Map.of(
                ADDON_BASE, node(type(ADDON_BASE, CORE_BASE))));

        byte[] rock = rockCallingSetHarvestLevel();
        assertSame(rock, Forge1122BlockConstructorAdapter.apply(rock, chain),
                "without the core jar nothing proves the rock is a block");
    }

    @Test
    @DisplayName("adapters read a linked library's translated classes through the transformer")
    void linkedLibraryClassesAreReadable(@TempDir Path dir) throws Exception {
        Path core = dir.resolve("core.jar");
        ClassWriter coreWriter = type(CORE_BASE, BLOCK);
        coreWriter.visitEnd();
        byte[] coreBase = coreWriter.toByteArray();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(core))) {
            out.putNextEntry(new JarEntry(CORE_BASE + ".class"));
            out.write(coreBase);
            out.closeEntry();
        }
        SyntheticLibraries libraries = SyntheticLibraries.create();
        libraries.add(core);

        RetromodTransformer.getInstance().setLinkedLibraries(libraries);

        assertArrayEquals(coreBase, RetromodTransformer.readLinkedLibraryClassForAdapter(CORE_BASE));
        assertNull(RetromodTransformer.readLinkedLibraryClassForAdapter(ADDON_BASE));
    }

    /** {@code RocksA(...) { super(); setHarvestLevel("pickaxe", 1, getDefaultState()); }} */
    private static byte[] rockCallingSetHarvestLevel() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, ROCK, null, ADDON_BASE, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, ADDON_BASE, "<init>", "()V", false);
        init.visitVarInsn(ALOAD, 0);
        init.visitLdcInsn("pickaxe");
        init.visitInsn(ICONST_1);
        init.visitInsn(ACONST_NULL);
        init.visitMethodInsn(INVOKEVIRTUAL, ROCK, "setHarvestLevel", HARVEST, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassWriter type(String name, String superName) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V17, ACC_PUBLIC, name, null, superName, null);
        return cw;
    }

    /** The given classes, plus the JDK's, as the host's class reader would see them. */
    private static Function<String, ClassNode> classes(Map<String, ClassNode> known) {
        return name -> {
            if (known.containsKey(name)) return known.get(name);
            if (!name.startsWith("java/")) return null;
            try {
                ClassNode node = new ClassNode();
                new ClassReader(name.replace('/', '.')).accept(node, ClassReader.SKIP_CODE);
                return node;
            } catch (java.io.IOException e) {
                return null;
            }
        };
    }

    private static ClassNode node(ClassWriter writer) {
        writer.visitEnd();
        return node(writer.toByteArray());
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static java.util.List<String> calls(ClassNode node) {
        java.util.List<String> calls = new java.util.ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) calls.add(call.owner + "." + call.name + call.desc);
            }
        }
        return calls;
    }
}
