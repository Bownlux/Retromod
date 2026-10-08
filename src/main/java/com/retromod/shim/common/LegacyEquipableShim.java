/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps items that implement the removed {@code Equipable} interface loadable.
 *
 * <p>Minecraft 1.21.2 deleted {@code net.minecraft.world.item.Equipable} and moved equipping onto
 * the {@code equippable} data component. A 1.21.1 item that still implements the interface, such
 * as Sophisticated Backpacks' backpack, fails with {@code NoClassDefFoundError} the moment its
 * class loads, which happens during item registration and takes the whole mod down (#249).
 *
 * <p>The interface is replaced by a generated stand-in with the 1.21.1 members whose types still
 * exist: the abstract {@code getEquipmentSlot}, the default {@code getEquipSound}, and the static
 * {@code get(ItemStack)} lookup. {@code swapWithEquipmentSlot} is left out because its return
 * type, {@code InteractionResultHolder}, was deleted in the same release. The stand-in makes the
 * item load and register. It does not make the item equippable through the vanilla equipment
 * path, which now reads the data component the old item never sets.
 *
 * <p>The redirect is keyed on the Mojang name, so it reaches NeoForge and Forge mods directly and
 * Fabric mods after the intermediary remap on 26.1 and newer hosts. Fabric hosts from 1.21.2 to
 * 1.21.11 keep the intermediary name and are not covered.
 */
public class LegacyEquipableShim implements MinecraftVersionedApiShim {

    static final String OLD_EQUIPABLE = "net/minecraft/world/item/Equipable";
    /** The generated stand-in, embedded per mod. */
    public static final String STAND_IN = "com/retromod/shim/common/embedded/LegacyEquipable";

    private static final String ITEM_STACK = "net/minecraft/world/item/ItemStack";
    private static final String ITEM = "net/minecraft/world/item/Item";
    private static final String BLOCK_ITEM = "net/minecraft/world/item/BlockItem";
    private static final String BLOCK = "net/minecraft/world/level/block/Block";
    private static final String EQUIPMENT_SLOT = "net/minecraft/world/entity/EquipmentSlot";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final String SOUND_EVENTS = "net/minecraft/sounds/SoundEvents";

    @Override
    public String getShimName() {
        return "Removed Equipable Interface";
    }

    @Override
    public String getSourceVersion() {
        return "1.21.1";
    }

    /** Equipable still exists below 1.21.2, so nothing is rewritten on those hosts. */
    @Override
    public String getTargetVersion() {
        return "1.21.2";
    }

    @Override
    public String getModLoaderType() {
        return "common";
    }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(STAND_IN, generateStandIn());
        transformer.registerClassRedirect(OLD_EQUIPABLE, STAND_IN);
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }

    /** The stand-in interface with the 1.21.1 members whose types survived. */
    public static byte[] generateStandIn() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_ABSTRACT | ACC_INTERFACE, STAND_IN, null,
                "java/lang/Object", null);

        cw.visitMethod(ACC_PUBLIC | ACC_ABSTRACT, "getEquipmentSlot",
                "()L" + EQUIPMENT_SLOT + ";", null, null).visitEnd();

        MethodVisitor sound = cw.visitMethod(ACC_PUBLIC, "getEquipSound",
                "()L" + HOLDER + ";", null, null);
        sound.visitCode();
        sound.visitFieldInsn(GETSTATIC, SOUND_EVENTS, "ARMOR_EQUIP_GENERIC", "L" + HOLDER + ";");
        sound.visitInsn(ARETURN);
        sound.visitMaxs(0, 0);
        sound.visitEnd();

        emitGet(cw);

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The item itself, else a block item's block, when either implements the stand-in. */
    private static void emitGet(ClassWriter cw) {
        MethodVisitor get = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "get",
                "(L" + ITEM_STACK + ";)L" + STAND_IN + ";", null, null);
        get.visitCode();
        get.visitVarInsn(ALOAD, 0);
        get.visitMethodInsn(INVOKEVIRTUAL, ITEM_STACK, "getItem", "()L" + ITEM + ";", false);
        get.visitVarInsn(ASTORE, 1);

        Label notItem = new Label();
        get.visitVarInsn(ALOAD, 1);
        get.visitTypeInsn(INSTANCEOF, STAND_IN);
        get.visitJumpInsn(IFEQ, notItem);
        get.visitVarInsn(ALOAD, 1);
        get.visitTypeInsn(CHECKCAST, STAND_IN);
        get.visitInsn(ARETURN);

        get.visitLabel(notItem);
        Label none = new Label();
        get.visitVarInsn(ALOAD, 1);
        get.visitTypeInsn(INSTANCEOF, BLOCK_ITEM);
        get.visitJumpInsn(IFEQ, none);
        get.visitVarInsn(ALOAD, 1);
        get.visitTypeInsn(CHECKCAST, BLOCK_ITEM);
        get.visitMethodInsn(INVOKEVIRTUAL, BLOCK_ITEM, "getBlock", "()L" + BLOCK + ";", false);
        get.visitVarInsn(ASTORE, 2);
        get.visitVarInsn(ALOAD, 2);
        get.visitTypeInsn(INSTANCEOF, STAND_IN);
        get.visitJumpInsn(IFEQ, none);
        get.visitVarInsn(ALOAD, 2);
        get.visitTypeInsn(CHECKCAST, STAND_IN);
        get.visitInsn(ARETURN);

        get.visitLabel(none);
        get.visitInsn(ACONST_NULL);
        get.visitInsn(ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();
    }
}
