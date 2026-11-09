/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Answers the no-argument {@code Item.getName()} and {@code getDescription()}, which 26.1 removed. */
public final class LegacyItemNameBridge {

    static final String HELPER = "com/retromod/generated/LegacyItemNames26x";
    static final String ITEM = "net/minecraft/world/item/Item";
    static final String COMPONENT = "net/minecraft/network/chat/Component";
    static final String OLD_DESC = "()L" + COMPONENT + ";";
    static final String HELPER_DESC = "(L" + ITEM + ";)L" + COMPONENT + ";";

    private static final String REFERENCE = "net/minecraft/core/Holder$Reference";
    private static final String COMPONENT_MAP = "net/minecraft/core/component/DataComponentMap";
    private static final String COMPONENT_TYPE = "net/minecraft/core/component/DataComponentType";

    private LegacyItemNameBridge() {}

    /** Sends both old calls to the helper; only 26.1 shims call this. */
    public static void register26x(RetromodTransformer transformer) {
        // A shim may reach this more than once; one helper is enough.
        if (!transformer.getSyntheticClasses().containsKey(HELPER)) {
            transformer.registerSyntheticClass(HELPER, generateHelper());
        }
        // 1.21.1 called it getDescription and 1.21.2 to 1.21.11 getName; 26.x has only getName(ItemStack).
        // Inherited, so a call on a mod's own item class is bridged unless that class declares it.
        for (String name : new String[]{"getName", "getDescription"}) {
            transformer.registerInheritedMethodRedirect(ITEM, name, OLD_DESC, HELPER, "name", HELPER_DESC);
        }
    }

    static byte[] generateHelper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                // Minecraft is not on Retromod's classpath when this class is generated.
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "name", HELPER_DESC, null, null);
        mv.visitCode();

        // Reading components before the registry binds them throws, and a creative tab title
        // built during registration asks that early, so check first.
        Label unbound = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "builtInRegistryHolder", "()L" + REFERENCE + ";", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REFERENCE, "areComponentsBound", "()Z", false);
        mv.visitJumpInsn(Opcodes.IFEQ, unbound);

        // Bound: the item_name component, which is what the old getName() returned.
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "components", "()L" + COMPONENT_MAP + ";", false);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/core/component/DataComponents", "ITEM_NAME",
                "L" + COMPONENT_TYPE + ";");
        mv.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/network/chat/CommonComponents", "EMPTY",
                "L" + COMPONENT + ";");
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, COMPONENT_MAP, "getOrDefault",
                "(L" + COMPONENT_TYPE + ";Ljava/lang/Object;)Ljava/lang/Object;", true);
        mv.visitTypeInsn(Opcodes.CHECKCAST, COMPONENT);
        mv.visitInsn(Opcodes.ARETURN);

        // Unbound: the translated description id, as 1.21.1's getDescription() built it.
        mv.visitLabel(unbound);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, ITEM, "getDescriptionId", "()Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, COMPONENT, "translatable",
                "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;", true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
