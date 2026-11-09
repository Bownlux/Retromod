/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Bridges the vanilla static members 1.21.4 removed, for mods built for 1.21.3 or older. */
public final class Legacy1214MemberBridge {

    static final String HELPER = "com/retromod/generated/Legacy1214Members";
    static final String FOLIAGE = "net/minecraft/world/level/FoliageColor";
    static final String FOLIAGE_INTERMEDIARY = "net/minecraft/class_1926";
    static final String COLOR_DESC = "()I";

    /** One removed foliage color getter: its Mojang name, its intermediary id, and the color it returned. */
    record FoliageGetter(String name, String intermediary, int color) {}

    // 1.21.3 returned these literals. 1.21.4 to 26.3 hold the same values in FOLIAGE_EVERGREEN,
    // FOLIAGE_BIRCH, FOLIAGE_DEFAULT and FOLIAGE_MANGROVE. Ids checked in the 1.21.1 to 1.21.3 tables.
    static final FoliageGetter[] FOLIAGE_GETTERS = {
            new FoliageGetter("getEvergreenColor", "method_8342", 0xFF619961),
            new FoliageGetter("getBirchColor", "method_8343", 0xFF80A755),
            new FoliageGetter("getDefaultColor", "method_8341", 0xFF48B518),
            new FoliageGetter("getMangroveColor", "method_43717", 0xFF92C648),
    };

    private Legacy1214MemberBridge() {}

    /** Sends each removed getter to a helper that returns the same color. */
    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(HELPER, generateHelper());
        for (FoliageGetter getter : FOLIAGE_GETTERS) {
            // Block and item color handlers call these while rendering leaves and vines.
            transformer.registerMethodRedirect(FOLIAGE, getter.name(), COLOR_DESC,
                    HELPER, getter.name(), COLOR_DESC);
            // Fabric hosts from 1.21.4 to 1.21.11 run intermediary names. On 26.1 and newer the
            // intermediary remap runs first and the Mojang key above answers.
            transformer.registerMethodRedirect(FOLIAGE_INTERMEDIARY, getter.intermediary(), COLOR_DESC,
                    HELPER, getter.name(), COLOR_DESC);
        }
    }

    /** Writes the helper: one static getter per color, returning the literal, so it names no game class. */
    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        for (FoliageGetter getter : FOLIAGE_GETTERS) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, getter.name(), COLOR_DESC,
                    null, null);
            mv.visitCode();
            mv.visitLdcInsn(getter.color());
            mv.visitInsn(Opcodes.IRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
