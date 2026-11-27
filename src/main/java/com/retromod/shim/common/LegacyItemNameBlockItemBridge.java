/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.util.function.Predicate;

import static org.objectweb.asm.Opcodes.*;

/** Keeps an old mod's {@code ItemNameBlockItem}, which 1.21.2 removed, as a block item named after itself. */
public final class LegacyItemNameBlockItemBridge {

    static final String OLD = "net/minecraft/world/item/ItemNameBlockItem";
    public static final String STAND_IN = "com/retromod/generated/LegacyItemNameBlockItem";
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    static final String INIT = "(Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/item/Item$Properties;)V";
    static final String REMOVE_DESC = "(Ljava/util/Map;Lnet/minecraft/world/item/Item;)V";

    private LegacyItemNameBlockItemBridge() {}

    /** Registers the stand-in where the host lacks the class; the 1.21.2 shims and the 26.1 class moves call this. */
    public static void register(RetromodTransformer transformer) {
        register(transformer, ClassResourceInspector::exists);
    }

    static void register(RetromodTransformer transformer, Predicate<String> hostHas) {
        // A host that still has the class keeps it, so a mod built for it is left alone.
        if (hostHas.test(OLD)) return;
        if (!transformer.getSyntheticClasses().containsKey(STAND_IN)) {
            transformer.registerSyntheticClass(STAND_IN, generate());
        }
        transformer.registerClassRedirect(OLD, STAND_IN);
    }

    /**
     * {@code class LegacyItemNameBlockItem extends BlockItem}, with the old {@code (Block, Item.Properties)} constructor.
     * Its own name keeps it out of the adapter that pins a plain {@code BlockItem} to the block's description id, so
     * the item is named after itself, as the old class did: from 1.21.2 that is the item properties' default.
     */
    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, STAND_IN, null, BLOCK_ITEM, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", INIT, null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitVarInsn(ALOAD, 1);
        init.visitVarInsn(ALOAD, 2);
        init.visitMethodInsn(INVOKESPECIAL, BLOCK_ITEM, "<init>", INIT, false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // Forge's removeFromBlockToItemMap, gone from NeoForge 1.21.11: an old override's super call drops the block's entry.
        MethodVisitor remove = cw.visitMethod(ACC_PUBLIC, "removeFromBlockToItemMap", REMOVE_DESC, null, null);
        remove.visitCode();
        remove.visitVarInsn(ALOAD, 1);
        remove.visitVarInsn(ALOAD, 0);
        remove.visitMethodInsn(INVOKEVIRTUAL, BLOCK_ITEM, "getBlock", "()Lnet/minecraft/world/level/block/Block;", false);
        remove.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "remove", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        remove.visitInsn(POP);
        remove.visitInsn(RETURN);
        remove.visitMaxs(0, 0);
        remove.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
