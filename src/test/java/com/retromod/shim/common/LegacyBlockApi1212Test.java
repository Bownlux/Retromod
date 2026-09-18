/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.neoforge.NeoForge_1_21_1_to_1_21_2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 1.21.1 block mods on a 1.21.2+ host, found by filming Macaw's Bridges and Roofs on NeoForge 26.2.
 * Right-clicking a bridge with a bridge threw {@code NoSuchFieldError:
 * PASS_TO_DEFAULT_BLOCK_INTERACTION}, every block item showed a raw {@code item.} key, and the
 * {@code updateShape} overrides that connect bridges were never called.
 */
class LegacyBlockApi1212Test {

    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    private static final String PROPS = "net/minecraft/world/item/Item$Properties";
    private static final String IR = "net/minecraft/world/InteractionResult";

    private RetromodTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = RetromodTransformer.getInstance();
        transformer.clearRedirectsForTesting();
    }

    @AfterEach
    void tearDown() {
        transformer.clearRedirectsForTesting();
    }

    @Test
    @DisplayName("ItemInteractionResult constants resolve to their typed 1.21.2 fields")
    void itemInteractionResultConstants() {
        new NeoForge_1_21_1_to_1_21_2().registerRedirects(transformer);

        FieldInsnNode pass = readConstant("PASS_TO_DEFAULT_BLOCK_INTERACTION");
        assertEquals(IR, pass.owner);
        assertEquals("TRY_WITH_EMPTY_HAND", pass.name);
        assertEquals("L" + IR + "$TryEmptyHandInteraction;", pass.desc);

        FieldInsnNode success = readConstant("SUCCESS");
        assertEquals("L" + IR + "$Success;", success.desc, "the field type changed with the merge");

        FieldInsnNode skip = readConstant("SKIP_DEFAULT_BLOCK_INTERACTION");
        assertEquals("FAIL", skip.name);
        assertEquals("L" + IR + "$Fail;", skip.desc);
    }

    @Test
    @DisplayName("new BlockItem(block, props) keeps the block's description id")
    void blockItemKeepsBlockDescription() {
        byte[] out = LegacyBlockApiAdapter.apply(modClass("java/lang/Object", cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "item",
                    "(L" + BLOCK + ";L" + PROPS + ";)Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitTypeInsn(NEW, BLOCK_ITEM);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKESPECIAL, BLOCK_ITEM, "<init>",
                    "(L" + BLOCK + ";L" + PROPS + ";)V", false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }));

        MethodNode item = method(out, "item", null);
        MethodInsnNode helper = call(item, LegacyBlockApiSynthetic.INTERNAL, "blockItemProperties");
        assertNotNull(helper, "the properties must pass through the description helper");
        assertStackValid(out);
    }

    @Test
    @DisplayName("old updateShape override gets a 1.21.2 bridge and its super call is retyped")
    void updateShapeOverrideBridged() {
        String old = LegacyBlockApiAdapter.OLD_UPDATE_SHAPE;
        byte[] out = LegacyBlockApiAdapter.apply(modClass(BLOCK, cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "updateShape", old, null, null);
            mv.visitCode();
            for (int slot = 0; slot <= 6; slot++) mv.visitVarInsn(ALOAD, slot);
            mv.visitMethodInsn(INVOKESPECIAL, BLOCK, "updateShape", old, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }));

        MethodNode bridge = method(out, "updateShape", LegacyBlockApiAdapter.NEW_UPDATE_SHAPE);
        assertNotNull(bridge, "Minecraft calls only the new descriptor");
        assertNotNull(call(bridge, "test/mod/Thing", "updateShape"),
                "the bridge must forward to the mod's own override");

        MethodInsnNode superCall = call(method(out, "updateShape", old), BLOCK, "updateShape");
        assertEquals(LegacyBlockApiAdapter.NEW_UPDATE_SHAPE, superCall.desc,
                "the old super method no longer exists");
        assertStackValid(out);
    }

    @Test
    @DisplayName("old getOcclusionShape override gets a single-argument bridge")
    void occlusionShapeOverrideBridged() {
        byte[] out = LegacyBlockApiAdapter.apply(modClass(BLOCK, cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getOcclusionShape",
                    LegacyBlockApiAdapter.OLD_OCCLUSION_SHAPE, null, null);
            mv.visitCode();
            for (int slot = 0; slot <= 3; slot++) mv.visitVarInsn(ALOAD, slot);
            mv.visitMethodInsn(INVOKESPECIAL, BLOCK, "getOcclusionShape",
                    LegacyBlockApiAdapter.OLD_OCCLUSION_SHAPE, false);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }));

        assertNotNull(method(out, "getOcclusionShape", LegacyBlockApiAdapter.NEW_OCCLUSION_SHAPE));
        MethodInsnNode superCall = call(method(out, "getOcclusionShape",
                LegacyBlockApiAdapter.OLD_OCCLUSION_SHAPE), BLOCK, "getOcclusionShape");
        assertEquals(LegacyBlockApiAdapter.NEW_OCCLUSION_SHAPE, superCall.desc);
        assertStackValid(out);
    }

    @Test
    @DisplayName("the adapter stays off unless a 1.21.1 to 1.21.2 shim enabled it")
    void adapterIsGatedOnTheShim() {
        byte[] original = modClass(BLOCK, cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "getOcclusionShape",
                    LegacyBlockApiAdapter.OLD_OCCLUSION_SHAPE, null, null);
            mv.visitCode();
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        });
        // Any real shim chain registers redirects; with none the transformer skips post-processing.
        transformer.registerClassRedirect("test/Unrelated", "test/Renamed");
        byte[] untouched = transformer.transformClass(original, "test/mod/Thing");
        assertNull(method(untouched, "getOcclusionShape", LegacyBlockApiAdapter.NEW_OCCLUSION_SHAPE),
                "a mod written for 1.21.2 or newer must not be changed");

        LegacyBlockApiSynthetic.register(transformer);
        byte[] repaired = transformer.transformClass(original, "test/mod/Thing");
        assertNotNull(method(repaired, "getOcclusionShape", LegacyBlockApiAdapter.NEW_OCCLUSION_SHAPE));
    }

    private FieldInsnNode readConstant(String name) {
        byte[] out = transformer.transformClass(modClass("java/lang/Object", cw -> {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "read",
                    "()Ljava/lang/Object;", null, null);
            mv.visitCode();
            mv.visitFieldInsn(GETSTATIC, "net/minecraft/world/ItemInteractionResult", name,
                    "Lnet/minecraft/world/ItemInteractionResult;");
            mv.visitInsn(ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }), "test/mod/Thing");
        for (AbstractInsnNode insn : method(out, "read", null).instructions) {
            if (insn instanceof FieldInsnNode field) return field;
        }
        return fail("the constant read disappeared");
    }

    private static byte[] modClass(String superName, Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/mod/Thing", null, superName, null);
        body.accept(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(byte[] bytes, String name, String desc) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
        }
        return null;
    }

    private static MethodInsnNode call(MethodNode m, String owner, String name) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof MethodInsnNode c && c.owner.equals(owner) && c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    /** Stack shapes must still line up after the inserted instructions. */
    private static void assertStackValid(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<String> failures = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            try {
                new Analyzer<>(new BasicVerifier()).analyze(cn.name, m);
            } catch (Exception e) {
                failures.add(m.name + m.desc + ": " + e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }
}
