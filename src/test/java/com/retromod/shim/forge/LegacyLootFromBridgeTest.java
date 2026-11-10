/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.ShimRegistry;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static com.retromod.shim.forge.LegacyLootFromBridge.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A Forge 1.20.1 or NeoForge 1.21.1 block that copies another block's drops with lootFrom. */
class LegacyLootFromBridgeTest {

    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @AfterEach
    void restore() {
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        RetromodTransformer.getInstance().clearRedirectsForTesting();
    }

    @Test
    void forge1212ShimBridgesLootFrom() {
        assertBridged("1.21.11", new Forge_1_21_1_to_1_21_2());
        assertBridged("26.3", new Forge_1_21_1_to_1_21_2());
    }

    @Test
    void neoForge1212ShimBridgesLootFrom() {
        assertBridged("1.21.11", new NeoForge_1_21_1_to_1_21_2());
    }

    @Test
    void neoForge26xShimStillBridgesLootFrom() {
        assertBridged("26.3", new NeoForge_1_21_11_to_26_1());
    }

    @Test
    void theShimThatCarriesItStaysOffHostsThatKeepLootFrom() {
        // 1.21.1 hosts still have lootFrom; the 1.21.2 shim is their gate.
        assertFalse(ShimRegistry.isAvailableOnHost(new Forge_1_21_1_to_1_21_2(), "1.21.1"));
        assertFalse(ShimRegistry.isAvailableOnHost(new NeoForge_1_21_1_to_1_21_2(), "1.21.1"));
        assertTrue(ShimRegistry.isAvailableOnHost(new Forge_1_21_1_to_1_21_2(), "26.3"));
    }

    @Test
    void hostThatStillHasLootFromKeepsTheCall() {
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        Thread thread = Thread.currentThread();
        ClassLoader saved = thread.getContextClassLoader();
        thread.setContextClassLoader(new HostWithLootFrom(saved));
        try {
            LegacyLootFromBridge.register(transformer);
        } finally {
            thread.setContextClassLoader(saved);
        }

        assertFalse(transformer.getSyntheticClasses().containsKey(HELPER), "nothing to embed on such a host");
        MethodInsnNode call = onlyCall(transformer.transformClass(blocks(), "mod/ModBlocks.class"));
        assertEquals(INVOKEVIRTUAL, call.getOpcode());
        assertEquals(PROPERTIES + ".lootFrom" + LOOT_FROM_DESC, call.owner + "." + call.name + call.desc);
    }

    /** The shim turns the call into the embedded helper and a cast back to the properties. */
    private static void assertBridged(String target, VersionShim shim) {
        RetromodVersion.TARGET_MC_VERSION = target;
        RetromodTransformer transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        shim.registerRedirects(transformer);
        String name = shim.getShimName() + " on " + target;

        ClassNode node = node(transformer.transformClass(blocks(), "mod/ModBlocks.class"));

        MethodInsnNode call = onlyCall(node);
        assertEquals(INVOKESTATIC, call.getOpcode(), name);
        assertEquals(HELPER + ".lootFrom" + HELPER_DESC, call.owner + "." + call.name + call.desc, name);
        assertTrue(call.getNext() instanceof TypeInsnNode cast && cast.getOpcode() == CHECKCAST
                && cast.desc.equals(PROPERTIES), name + ": the helper's Object must be cast back to Properties");
        assertTrue(transformer.getSyntheticClasses().containsKey(HELPER), name + " must embed the helper");
    }

    /** {@code static Properties copyDrops(Properties p, Supplier s) { return p.lootFrom(s); }} */
    private static byte[] blocks() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "mod/ModBlocks", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "copyDrops",
                "(L" + PROPERTIES + ";Ljava/util/function/Supplier;)L" + PROPERTIES + ";", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, PROPERTIES, "lootFrom", LOOT_FROM_DESC, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        return onlyCall(node(bytes));
    }

    private static MethodInsnNode onlyCall(ClassNode node) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : node.methods.get(0).instructions.toArray()) {
            if (insn instanceof MethodInsnNode call) calls.add(call);
        }
        assertEquals(1, calls.size(), "one call expected in " + node.name);
        return calls.get(0);
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    /** Serves a host {@code Properties} that still declares lootFrom, as Forge 1.20.1 does. */
    private static final class HostWithLootFrom extends ClassLoader {
        HostWithLootFrom(ClassLoader parent) {
            super(parent);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (!name.equals(PROPERTIES + ".class")) return super.getResourceAsStream(name);
            ClassWriter cw = new ClassWriter(0);
            cw.visit(V17, ACC_PUBLIC, PROPERTIES, null, "java/lang/Object", null);
            cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "lootFrom", LOOT_FROM_DESC, null, null).visitEnd();
            cw.visitEnd();
            return new ByteArrayInputStream(cw.toByteArray());
        }
    }
}
