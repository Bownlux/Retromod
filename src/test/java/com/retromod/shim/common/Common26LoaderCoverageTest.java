/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.core.VersionShim;
import com.retromod.shim.fabric.Fabric_1_21_11_to_26_1;
import com.retromod.shim.forge.Forge_1_21_11_to_26_1;
import com.retromod.shim.neoforge.NeoForge_1_21_11_to_26_1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Every loader's 26.1 shim registers the whole vanilla 26.1 set, so in-game Forge matches the CLI. */
class Common26LoaderCoverageTest {

    private static final String CHUNK_POS = "net/minecraft/world/level/ChunkPos";
    private static final String EXTRACTOR = "net/minecraft/client/gui/GuiGraphicsExtractor";

    private RetromodTransformer transformer;
    private String previousTarget;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
        previousTarget = RetromodVersion.TARGET_MC_VERSION;
        RetromodVersion.TARGET_MC_VERSION = "26.1";
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
        RetromodVersion.TARGET_MC_VERSION = previousTarget;
    }

    @Test
    @DisplayName("each 26.1 loader shim contains every key the common 26.1 set registers")
    void everyLoaderContainsTheCommonSet() {
        Common_1_21_11_to_26_1_ClassMoves.register(transformer);
        Map<String, Set<Object>> common = snapshot();

        List<VersionShim> shims = List.of(new Forge_1_21_11_to_26_1(), new NeoForge_1_21_11_to_26_1(),
                new Fabric_1_21_11_to_26_1());
        for (VersionShim shim : shims) {
            transformer.clearRedirectsForTesting();
            shim.registerRedirects(transformer);
            Map<String, Set<Object>> loader = snapshot();
            for (Map.Entry<String, Set<Object>> table : common.entrySet()) {
                Set<Object> missing = new HashSet<>(table.getValue());
                missing.removeAll(loader.get(table.getKey()));
                assertTrue(missing.isEmpty(), shim.getShimName() + " misses " + missing.size() + " "
                        + table.getKey() + " of the common 26.1 set, so its mods fail where the CLI "
                        + "repairs them: " + missing);
            }
        }
    }

    @Test
    @DisplayName("on Forge, removed 26.1 members are renamed and new ChunkPos(long) becomes unpack")
    void forgeRepairsTheRemovedMembers() {
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);
        List<String> out = trace(transformer.transformClass(removedMemberCalls(), "test/mod/Calls"));

        assertTrue(out.contains("call net/minecraft/resources/ResourceKey.identifier"), out.toString());
        assertTrue(out.contains("call net/minecraft/world/item/ItemStack.typeHolder"), out.toString());
        assertTrue(out.contains("call net/minecraft/world/level/block/state/BlockState.typeHolder"),
                out.toString());
        assertTrue(out.contains("static " + CHUNK_POS + ".unpack(J)L" + CHUNK_POS + ";"), out.toString());
        assertFalse(out.contains("new " + CHUNK_POS), "26.x has no ChunkPos(long): " + out);
        assertEquals("identifier",
                transformer.registeredMethodRename("net/minecraft/resources/ResourceKey", "location"));
    }

    @Test
    @DisplayName("on Forge, an old drawString call becomes text and still leaves an int for the caller")
    void forgeBridgesDrawString() {
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);
        String args = "(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Screen", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "draw",
                "(L" + EXTRACTOR + ";Lnet/minecraft/client/gui/Font;)I", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("hello");
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_0);
        mv.visitInsn(ICONST_M1);
        mv.visitMethodInsn(INVOKEVIRTUAL, EXTRACTOR, "drawString", args + "I", false);
        mv.visitInsn(IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(transformer.transformClass(cw.toByteArray(), "test/mod/Screen")).accept(cn, 0);
        MethodInsnNode text = null;
        for (AbstractInsnNode insn : cn.methods.get(0).instructions) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(EXTRACTOR)) text = call;
        }
        assertNotNull(text, "the draw call disappeared");
        assertEquals("text", text.name);
        assertEquals(args + "V", text.desc, "26.1's text() returns nothing");
        assertEquals(ICONST_0, text.getNext().getOpcode(), "the old int return is replaced by 0");
    }

    @Test
    @DisplayName("Forge keeps its own 26.1 renames beside the common set")
    void forgeKeepsItsOwnRenames() {
        new Forge_1_21_11_to_26_1().registerRedirects(transformer);

        assertEquals("net/minecraft/resources/Identifier",
                transformer.getClassRedirects().get("net/minecraft/resources/ResourceLocation"));
        Set<String> fields = new HashSet<>();
        transformer.getFieldRedirects().keySet().forEach(k -> fields.add(k.owner() + "." + k.name()));
        assertTrue(fields.contains("net/minecraft/world/entity/EntityType.BOAT"), fields.toString());
        assertTrue(fields.contains("net/minecraft/client/KeyMapping.boundKey"), fields.toString());
        Set<String> methods = new HashSet<>();
        transformer.getMethodRedirects().keySet().forEach(k -> methods.add(k.owner() + "." + k.name()));
        assertTrue(methods.contains("com/mojang/blaze3d/platform/Window.getWindow"), methods.toString());
        assertTrue(methods.contains("com/mojang/serialization/DataResult.get"), methods.toString());
    }

    /** The registered keys of each redirect table, by table name. */
    private Map<String, Set<Object>> snapshot() {
        return Map.of(
                "class redirects", keys(() -> transformer.getClassRedirects().keySet()),
                "method redirects", keys(() -> transformer.getMethodRedirects().keySet()),
                "field redirects", keys(() -> transformer.getFieldRedirects().keySet()),
                "synthetic classes", keys(() -> transformer.getSyntheticClasses().keySet()),
                "superclass rebases", keys(transformer::getSuperclassRebaseSources));
    }

    private static Set<Object> keys(Supplier<? extends Set<?>> table) {
        return new HashSet<>(table.get());
    }

    /** A 1.21.x Forge mod's calls to members 26.1 removed or renamed. */
    private static byte[] removedMemberCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Calls", null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "calls",
                "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/item/ItemStack;"
                        + "Lnet/minecraft/world/level/block/state/BlockState;J)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/resources/ResourceKey", "location",
                "()Lnet/minecraft/resources/ResourceLocation;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/item/ItemStack", "getItemHolder",
                "()Lnet/minecraft/core/Holder;", false);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/world/level/block/state/BlockState",
                "getBlockHolder", "()Lnet/minecraft/core/Holder;", false);
        mv.visitInsn(POP);
        mv.visitTypeInsn(NEW, CHUNK_POS);
        mv.visitInsn(DUP);
        mv.visitVarInsn(LLOAD, 3);
        mv.visitMethodInsn(INVOKESPECIAL, CHUNK_POS, "<init>", "(J)V", false);
        mv.visitInsn(POP);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> trace(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> out = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode insn : m.instructions) {
                if (insn instanceof MethodInsnNode c) {
                    out.add("call " + c.owner + "." + c.name);
                    if (c.getOpcode() == INVOKESTATIC) out.add("static " + c.owner + "." + c.name + c.desc);
                }
                if (insn instanceof TypeInsnNode t && t.getOpcode() == NEW) out.add("new " + t.desc);
            }
        }
        return out;
    }
}
