/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.*;

/**
 * Keeps 1.19.2 armor working on 1.19.4 through 1.20.4 hosts.
 *
 * <p>1.19.4 replaced the {@code EquipmentSlot} in armor with {@code ArmorItem.Type}: the item's
 * constructor takes a type, and {@code ArmorMaterial} asks for durability and defense per type
 * through {@code getDurabilityForType} and {@code getDefenseForType}. A 1.19.2 mod passes a slot
 * and implements the per-slot methods, so its armor failed to construct, and a material it
 * implemented itself left the per-type methods abstract.
 *
 * <p>Armor items are rebased onto a generated subclass that converts the slot to its type, and a
 * mod's material gains per-type methods that ask its own per-slot ones. 1.20.5 made
 * {@code ArmorMaterial} a registered record, which a mod cannot implement, so this bridge stops
 * there.
 */
public final class LegacyArmorBridge {

    public static final String ARMOR_ITEM_BASE = "com/retromod/generated/LegacyArmorItem";
    private static final String ARMOR_ITEM = "net/minecraft/world/item/ArmorItem";
    private static final String TYPE = ARMOR_ITEM + "$Type";
    private static final String L_TYPE = "L" + TYPE + ";";
    private static final String MATERIAL = "net/minecraft/world/item/ArmorMaterial";
    private static final String L_MATERIAL = "L" + MATERIAL + ";";
    private static final String L_SLOT = "Lnet/minecraft/world/entity/EquipmentSlot;";
    private static final String L_PROPERTIES = "Lnet/minecraft/world/item/Item$Properties;";
    private static final String OLD_CTOR = "(" + L_MATERIAL + L_SLOT + L_PROPERTIES + ")V";
    private static final String HOST_CTOR = "(" + L_MATERIAL + L_TYPE + L_PROPERTIES + ")V";

    /** Slot names in 1.19.2 order, paired with the armor type each became. */
    private static final String[][] SLOT_TYPES = {
        {"HEAD", "HELMET"}, {"CHEST", "CHESTPLATE"}, {"LEGS", "LEGGINGS"}, {"FEET", "BOOTS"}
    };

    private LegacyArmorBridge() {}

    public static void register(RetromodTransformer transformer) {
        transformer.registerSyntheticClass(ARMOR_ITEM_BASE, generateArmorItem());
        transformer.registerSuperclassRebase(ARMOR_ITEM, ARMOR_ITEM_BASE);
        transformer.registerConstructorRedirect(ARMOR_ITEM, OLD_CTOR, ARMOR_ITEM_BASE, "create",
                "(" + L_MATERIAL + L_SLOT + L_PROPERTIES + ")L" + ARMOR_ITEM + ";");
    }

    /** {@code class LegacyArmorItem extends ArmorItem} with the slot and type constructors. */
    static byte[] generateArmorItem() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String first, String second) {
                return "java/lang/Object";
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_SUPER, ARMOR_ITEM_BASE, null, ARMOR_ITEM, null);

        MethodVisitor old = cw.visitMethod(ACC_PUBLIC, "<init>", OLD_CTOR, null, null);
        old.visitCode();
        old.visitVarInsn(ALOAD, 0);
        old.visitVarInsn(ALOAD, 1);
        old.visitVarInsn(ALOAD, 2);
        old.visitMethodInsn(INVOKESTATIC, ARMOR_ITEM_BASE, "typeOf", "(" + L_SLOT + ")" + L_TYPE, false);
        old.visitVarInsn(ALOAD, 3);
        old.visitMethodInsn(INVOKESPECIAL, ARMOR_ITEM, "<init>", HOST_CTOR, false);
        old.visitInsn(RETURN);
        old.visitMaxs(0, 0);
        old.visitEnd();

        // A mod already on the host's shape is rebased too, so it passes straight through.
        MethodVisitor host = cw.visitMethod(ACC_PUBLIC, "<init>", HOST_CTOR, null, null);
        host.visitCode();
        for (int slot = 0; slot <= 3; slot++) host.visitVarInsn(ALOAD, slot);
        host.visitMethodInsn(INVOKESPECIAL, ARMOR_ITEM, "<init>", HOST_CTOR, false);
        host.visitInsn(RETURN);
        host.visitMaxs(0, 0);
        host.visitEnd();

        MethodVisitor create = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "create",
                "(" + L_MATERIAL + L_SLOT + L_PROPERTIES + ")L" + ARMOR_ITEM + ";", null, null);
        create.visitCode();
        create.visitTypeInsn(NEW, ARMOR_ITEM_BASE);
        create.visitInsn(DUP);
        for (int slot = 0; slot <= 2; slot++) create.visitVarInsn(ALOAD, slot);
        create.visitMethodInsn(INVOKESPECIAL, ARMOR_ITEM_BASE, "<init>", OLD_CTOR, false);
        create.visitInsn(ARETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();

        MethodVisitor typeOf = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "typeOf",
                "(" + L_SLOT + ")" + L_TYPE, null, null);
        typeOf.visitCode();
        for (String[] pair : SLOT_TYPES) {
            Label next = new Label();
            typeOf.visitVarInsn(ALOAD, 0);
            typeOf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Enum", "name", "()Ljava/lang/String;", false);
            typeOf.visitLdcInsn(pair[0]);
            typeOf.visitMethodInsn(INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
            typeOf.visitJumpInsn(IFEQ, next);
            typeOf.visitFieldInsn(GETSTATIC, TYPE, pair[1], L_TYPE);
            typeOf.visitInsn(ARETURN);
            typeOf.visitLabel(next);
        }
        // Hands and the body slot never held armor; a chestplate keeps the item constructing.
        typeOf.visitFieldInsn(GETSTATIC, TYPE, "CHESTPLATE", L_TYPE);
        typeOf.visitInsn(ARETURN);
        typeOf.visitMaxs(0, 0);
        typeOf.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Adds {@code getDurabilityForType} and {@code getDefenseForType} to a class that implements
     * {@code ArmorMaterial} with the 1.19.2 per-slot methods. Each asks the per-slot method for the
     * type's slot. The names are the host's, which on an SRG host are SRG names.
     */
    public static byte[] adaptMaterial(byte[] classBytes, RetromodTransformer transformer) {
        ClassReader reader = new ClassReader(classBytes);
        if (!java.util.Arrays.asList(reader.getInterfaces()).contains(MATERIAL)) return classBytes;

        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        String typeDesc = "(" + L_TYPE + ")I";
        String getSlot = transformer.remapQualifiedMethodName(TYPE, "getSlot", "()" + L_SLOT);
        boolean changed = false;
        for (String[] pair : new String[][]{{"getDurabilityForSlot", "getDurabilityForType"},
                {"getDefenseForSlot", "getDefenseForType"}}) {
            String perType = transformer.remapQualifiedMethodName(MATERIAL, pair[1], typeDesc);
            if (!declares(node, pair[0], "(" + L_SLOT + ")I") || declares(node, perType, typeDesc)) {
                continue;
            }
            MethodNode bridge = new MethodNode(ACC_PUBLIC, perType, typeDesc, null, null);
            bridge.visitCode();
            bridge.visitVarInsn(ALOAD, 0);
            bridge.visitVarInsn(ALOAD, 1);
            bridge.visitMethodInsn(INVOKEVIRTUAL, TYPE, getSlot, "()" + L_SLOT, false);
            bridge.visitMethodInsn(INVOKEVIRTUAL, node.name, pair[0], "(" + L_SLOT + ")I", false);
            bridge.visitInsn(IRETURN);
            bridge.visitMaxs(2, 2);
            bridge.visitEnd();
            node.methods.add(bridge);
            changed = true;
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(desc));
    }
}
