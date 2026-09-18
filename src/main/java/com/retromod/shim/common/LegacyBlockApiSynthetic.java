/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Helper for {@link LegacyBlockApiAdapter}, embedded into a pre-1.21.2 mod.
 *
 * <p>Registering it is also the adapter's gate. Only the 1.21.1 to 1.21.2 shims register it, so a
 * mod written for 1.21.2 or newer, whose {@code new BlockItem(...)} deliberately uses the item
 * description prefix, is never touched.
 */
public final class LegacyBlockApiSynthetic {

    private LegacyBlockApiSynthetic() {}

    public static final String INTERNAL = "com/retromod/shim/common/embedded/LegacyBlockApiBridge";

    static final String BLOCK = "net/minecraft/world/level/block/Block";
    static final String ITEM_PROPERTIES = "net/minecraft/world/item/Item$Properties";
    static final String BLOCK_ITEM_PROPERTIES_DESC =
            "(L" + BLOCK + ";L" + ITEM_PROPERTIES + ";)L" + ITEM_PROPERTIES + ";";

    /** Registers the helper, which enables the adapter for the mod being transformed. */
    public static void register(RetromodTransformer transformer) {
        if (!transformer.getSyntheticClasses().containsKey(INTERNAL)) {
            transformer.registerSyntheticClass(INTERNAL, generate());
        }
    }

    public static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, INTERNAL, null, "java/lang/Object", null);

        // Up to 1.21.1 a BlockItem always used its block's description id. 1.21.2 made it default
        // to "item.<ns>.<path>", so a 1.21.1 mod whose lang file has only "block." keys showed raw
        // keys for every block item. Pin the old id: return props.overrideDescription(block.id)
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "blockItemProperties",
                BLOCK_ITEM_PROPERTIES_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, BLOCK, "getDescriptionId", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, ITEM_PROPERTIES, "overrideDescription",
                "(Ljava/lang/String;)L" + ITEM_PROPERTIES + ";", false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
