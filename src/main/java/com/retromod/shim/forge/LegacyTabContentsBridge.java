/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Forge's {@code BuildCreativeModeTabContentsEvent.accept(Supplier)} on NeoForge, which has no
 * such method. Forge 1.20.1 mods pass their {@code RegistryObject}s to it, so their items were
 * never listed and the handler failed with {@code NoSuchMethodError}. The supplier is resolved
 * and handed to {@code CreativeModeTab.Output.accept(ItemLike)}, which the event implements.
 */
public final class LegacyTabContentsBridge implements Opcodes {

    static final String HELPER = "com/retromod/generated/LegacyTabContents";
    private static final String OUTPUT = "net/minecraft/world/item/CreativeModeTab$Output";
    private static final String VISIBILITY = "Lnet/minecraft/world/item/CreativeModeTab$TabVisibility;";
    private static final String ITEM_LIKE = "net/minecraft/world/level/ItemLike";
    private static final String SUPPLIER = "Ljava/util/function/Supplier;";
    private static final String[] EVENTS = {"net/minecraftforge/event/BuildCreativeModeTabContentsEvent",
            "net/neoforged/neoforge/event/BuildCreativeModeTabContentsEvent"};

    private LegacyTabContentsBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generate());
        for (String event : EVENTS) {
            transformer.registerMethodRedirect(event, "accept", "(" + SUPPLIER + ")V",
                    HELPER, "accept", "(L" + OUTPUT + ";" + SUPPLIER + ")V", true);
            transformer.registerMethodRedirect(event, "accept", "(" + SUPPLIER + VISIBILITY + ")V",
                    HELPER, "accept", "(L" + OUTPUT + ";" + SUPPLIER + VISIBILITY + ")V", true);
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        for (boolean visibility : new boolean[] {false, true}) {
            String tail = visibility ? VISIBILITY : "";
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "accept",
                    "(L" + OUTPUT + ";" + SUPPLIER + tail + ")V", null, null);
            mv.visitCode();
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, ITEM_LIKE);
            if (visibility) mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEINTERFACE, OUTPUT, "accept", "(L" + ITEM_LIKE + ";" + tail + ")V", true);
            mv.visitInsn(RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
