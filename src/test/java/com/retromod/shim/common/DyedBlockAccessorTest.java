/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
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
import org.objectweb.asm.tree.TypeInsnNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * 26.2 collapsed the per-color block families into {@code ColorCollection<Block>} fields and removed
 * the per-color statics, so a 1.21.x mod reading {@code Blocks.WHITE_CANDLE} hits NoSuchFieldError.
 * The replacement is a call, {@code Blocks.DYED_CANDLE.pick(DyeColor.WHITE)}, so the GETSTATIC is
 * rewritten into that accessor sequence rather than a field-to-field redirect.
 */
class DyedBlockAccessorTest {

    private static final String BLOCKS = "net/minecraft/world/level/block/Blocks";
    private static final String COLOR_COLLECTION = "net/minecraft/world/level/block/ColorCollection";
    private static final String DYE_COLOR = "net/minecraft/world/item/DyeColor";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String BLOCK_DESC = "Lnet/minecraft/world/level/block/Block;";

    private static byte[] classReadingRemovedFields() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/candle/UsesDyed", null, "java/lang/Object", null);
        MethodVisitor a = cw.visitMethod(ACC_PUBLIC, "whiteCandle", "()" + BLOCK_DESC, null, null);
        a.visitCode();
        a.visitFieldInsn(GETSTATIC, BLOCKS, "WHITE_CANDLE", BLOCK_DESC);
        a.visitInsn(ARETURN);
        a.visitMaxs(1, 1);
        a.visitEnd();
        MethodVisitor b = cw.visitMethod(ACC_PUBLIC, "blackShulker", "()" + BLOCK_DESC, null, null);
        b.visitCode();
        b.visitFieldInsn(GETSTATIC, BLOCKS, "BLACK_SHULKER_BOX", BLOCK_DESC);
        b.visitInsn(ARETURN);
        b.visitMaxs(1, 1);
        b.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodNode method(ClassNode cn, String name) {
        return cn.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("all 28 dyed families (14 block + 14 item) x 16 colours register as static-field accessors")
    void allFamiliesRegistered() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            Mc26_1To26_2CoreMoves.register(t);
            // 448 dyed (14 Blocks families + 14 Items families, 16 colours each) + 33 BlockItemTags
            // accessors (17 deleted BlockTags + 16 deleted ItemTags constants -> .block()/.item()),
            // which share the static-field-accessor table (snapshot.3).
            assertEquals(448 + 33, t.getStaticFieldAccessorCount(),
                    "448 dyed accessors + 33 BlockItemTags accessors");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("a removed per-color ITEM field becomes Items.<collection>.pick(color) cast to Item (not Block)")
    void itemFamilyCastsToItem() {
        String ITEMS = "net/minecraft/world/item/Items";
        String ITEM = "net/minecraft/world/item/Item";
        String ITEM_DESC = "Lnet/minecraft/world/item/Item;";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC, "test/item/UsesDyedItem", null, "java/lang/Object", null);
        MethodVisitor a = cw.visitMethod(ACC_PUBLIC, "whiteWoolItem", "()" + ITEM_DESC, null, null);
        a.visitCode();
        a.visitFieldInsn(GETSTATIC, ITEMS, "WHITE_WOOL", ITEM_DESC);
        a.visitInsn(ARETURN);
        a.visitMaxs(1, 1);
        a.visitEnd();
        cw.visitEnd();

        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            Mc26_1To26_2CoreMoves.register(t);
            ClassNode cn = new ClassNode();
            new ClassReader(t.transformClass(cw.toByteArray(), "test/item/UsesDyedItem")).accept(cn, 0);
            MethodNode m = method(cn, "whiteWoolItem");
            assertFalse(hasField(m, GETSTATIC, ITEMS, "WHITE_WOOL"), "Items.WHITE_WOOL should be rewritten away");
            assertTrue(hasField(m, GETSTATIC, ITEMS, "WOOL"), "should load Items.WOOL (the ColorCollection)");
            assertTrue(hasField(m, GETSTATIC, DYE_COLOR, "WHITE"), "should load DyeColor.WHITE");
            assertTrue(hasMethod(m, INVOKEVIRTUAL, COLOR_COLLECTION, "pick"), "should call ColorCollection.pick");
            assertTrue(hasCast(m, ITEM), "should checkcast to Item (a Block cast here would VerifyError at load)");
            assertFalse(hasCast(m, BLOCK), "must NOT cast an Item collection result to Block");
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    @Test
    @DisplayName("GETSTATIC of a removed per-color field becomes DYED_<F>.pick(DyeColor.<C>) + checkcast")
    void removedFieldBecomesAccessorCall() {
        RetromodTransformer t = RetromodTransformer.getInstance();
        t.clearRedirectsForTesting();
        try {
            Mc26_1To26_2CoreMoves.register(t);
            byte[] out = t.transformClass(classReadingRemovedFields(), "test/candle/UsesDyed");
            ClassNode cn = new ClassNode();
            new ClassReader(out).accept(cn, 0);

            assertWhiteCandle(method(cn, "whiteCandle"));
            assertBlackShulker(method(cn, "blackShulker"));
        } finally {
            t.clearRedirectsForTesting();
        }
    }

    private static void assertWhiteCandle(MethodNode m) {
        assertFalse(hasField(m, GETSTATIC, BLOCKS, "WHITE_CANDLE"),
                "Blocks.WHITE_CANDLE access should be rewritten away");
        assertTrue(hasField(m, GETSTATIC, BLOCKS, "DYED_CANDLE"), "should load Blocks.DYED_CANDLE");
        assertTrue(hasField(m, GETSTATIC, DYE_COLOR, "WHITE"), "should load DyeColor.WHITE");
        assertTrue(hasMethod(m, INVOKEVIRTUAL, COLOR_COLLECTION, "pick"), "should call ColorCollection.pick");
        assertTrue(hasCast(m, BLOCK), "should checkcast the result back to Block");
    }

    private static void assertBlackShulker(MethodNode m) {
        assertFalse(hasField(m, GETSTATIC, BLOCKS, "BLACK_SHULKER_BOX"),
                "Blocks.BLACK_SHULKER_BOX access should be rewritten away");
        assertTrue(hasField(m, GETSTATIC, BLOCKS, "DYED_SHULKER_BOX"), "should load Blocks.DYED_SHULKER_BOX");
        assertTrue(hasField(m, GETSTATIC, DYE_COLOR, "BLACK"), "should load DyeColor.BLACK");
        assertTrue(hasMethod(m, INVOKEVIRTUAL, COLOR_COLLECTION, "pick"), "should call ColorCollection.pick");
        assertTrue(hasCast(m, BLOCK), "should checkcast back to Block");
    }

    private static boolean hasField(MethodNode m, int op, String owner, String name) {
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == op
                    && owner.equals(f.owner) && name.equals(f.name)) return true;
        }
        return false;
    }

    private static boolean hasMethod(MethodNode m, int op, String owner, String name) {
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (in instanceof MethodInsnNode mi && mi.getOpcode() == op
                    && owner.equals(mi.owner) && name.equals(mi.name)) return true;
        }
        return false;
    }

    private static boolean hasCast(MethodNode m, String type) {
        for (AbstractInsnNode in : m.instructions.toArray()) {
            if (in instanceof TypeInsnNode ti && ti.getOpcode() == CHECKCAST && type.equals(ti.desc)) return true;
        }
        return false;
    }
}
