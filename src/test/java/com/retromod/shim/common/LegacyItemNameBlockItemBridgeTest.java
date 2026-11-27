/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.Forge_1_21_1_to_1_21_2;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** A 1.20.1 or 1.21.1 mod's {@code ItemNameBlockItem}, which 1.21.2 removed, on hosts with and without it. */
class LegacyItemNameBlockItemBridgeTest {

    private static final String OLD = LegacyItemNameBlockItemBridge.OLD;
    private static final String STAND_IN = LegacyItemNameBlockItemBridge.STAND_IN;
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    private static final String INIT = LegacyItemNameBlockItemBridge.INIT;

    @TempDir
    Path dir;

    private final RetromodTransformer transformer = RetromodTransformer.getInstance();
    private final String savedTarget = RetromodVersion.TARGET_MC_VERSION;

    @BeforeEach
    void clear() {
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void restore() {
        ClassResourceInspector.setOfflineHost(null);
        RetromodVersion.TARGET_MC_VERSION = savedTarget;
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("On a 1.21.11 host, which has no ItemNameBlockItem, both 1.21.2 shims send it to the stand-in")
    void aHostWithoutTheClassGetsTheStandIn() throws Exception {
        host(BLOCK_ITEM);
        new NeoForge_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertEquals(STAND_IN, transformer.getClassRedirects().get(OLD));

        transformer.clearRedirectsForTesting();
        new Forge_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertEquals(STAND_IN, transformer.getClassRedirects().get(OLD), "Forge mods take the same stand-in");
        assertNotNull(transformer.getSyntheticClasses().get(STAND_IN));
    }

    @Test
    @DisplayName("A host that still has ItemNameBlockItem keeps it")
    void aHostWithTheClassKeepsIt() throws Exception {
        host(BLOCK_ITEM, OLD);
        new NeoForge_1_21_1_to_1_21_2().registerRedirects(transformer);
        assertNull(transformer.getClassRedirects().get(OLD), "the host's own class must not be replaced");
        assertNull(transformer.getSyntheticClasses().get(STAND_IN));
    }

    @Test
    @DisplayName("The stand-in is a block item with the old constructor, which calls BlockItem's")
    void theStandInExtendsBlockItem() {
        ClassNode node = node(LegacyItemNameBlockItemBridge.generate());
        assertEquals(BLOCK_ITEM, node.superName);
        assertEquals(0, node.access & ACC_FINAL, "mods extend it, as they extended the old class");
        MethodNode init = node.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals(INIT))
                .findFirst().orElseThrow();
        assertTrue(calls(init).contains(BLOCK_ITEM + ".<init>" + INIT), calls(init).toString());
        // An old override of Forge's removeFromBlockToItemMap calls super, which NeoForge 1.21.11 no longer has.
        MethodNode remove = node.methods.stream().filter(m -> m.name.equals("removeFromBlockToItemMap")
                && m.desc.equals(LegacyItemNameBlockItemBridge.REMOVE_DESC)).findFirst().orElseThrow();
        assertTrue(calls(remove).contains("java/util/Map.remove(Ljava/lang/Object;)Ljava/lang/Object;"), calls(remove).toString());
    }

    @Test
    @DisplayName("A mod's ItemNameBlockItem keeps its own name, while a plain BlockItem keeps the block's")
    void onlyAPlainBlockItemTakesTheBlockName() {
        LegacyItemNameBlockItemBridge.register(transformer, name -> false);
        LegacyBlockApiSynthetic.register(transformer);
        byte[] out = transformer.transformClass(modItems(), "test/mod/Items");

        MethodNode seeds = method(out, "seeds");
        assertTrue(calls(seeds).contains(STAND_IN + ".<init>" + INIT), calls(seeds).toString());
        assertFalse(calls(seeds).stream().anyMatch(c -> c.contains("blockItemProperties")),
                "the old class named the item after itself, so its description id is not pinned to the block: "
                        + calls(seeds));
        assertTrue(calls(method(out, "plain")).stream().anyMatch(c -> c.contains("blockItemProperties")),
                "a plain block item still keeps its block's name");
    }

    /** An offline host holding exactly {@code classes}, as {@code --host-jar} gives the CLI. */
    private void host(String... classes) throws Exception {
        Path jar = dir.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String name : classes) {
                ClassWriter cw = new ClassWriter(0);
                cw.visit(V21, ACC_PUBLIC | ACC_SUPER, name, null, "java/lang/Object", null);
                cw.visitEnd();
                out.putNextEntry(new JarEntry(name + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
        }
        ClassResourceInspector.setOfflineHost(new URLClassLoader(new java.net.URL[]{jar.toUri().toURL()}, null));
        RetromodVersion.TARGET_MC_VERSION = "1.21.11";
    }

    /** {@code new ItemNameBlockItem(block, props)} and {@code new BlockItem(block, props)}, as a 1.20.1 mod builds them. */
    private static byte[] modItems() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, "test/mod/Items", null, "java/lang/Object", null);
        for (String[] item : new String[][]{{"seeds", OLD}, {"plain", BLOCK_ITEM}}) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, item[0],
                    "(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/item/Item$Properties;)Ljava/lang/Object;",
                    null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, item[1]);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, item[1], "<init>", INIT, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode node(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(byte[] bytes, String name) {
        return node(bytes).methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    private static List<String> calls(MethodNode method) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call) out.add(call.owner + "." + call.name + call.desc);
        }
        return out;
    }
}
