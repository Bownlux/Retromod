/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;

/** Stamps known pre-registration Fabric items with the id required by current Item.Properties. */
public final class LegacyFabricItemIdRepair {

    private static final String ITEM_MOD_BOOK =
            "vazkii/patchouli/common/item/ItemModBook";
    private static final String PROPERTIES = "net/minecraft/class_1792$class_1793";

    private LegacyFabricItemIdRepair() {}

    public static byte[] apply(byte[] classBytes, String className) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.4") < 0
                || RetromodVersion.isUnobfuscatedTarget(RetromodVersion.TARGET_MC_VERSION)) {
            return classBytes;
        }
        String normalizedName = className != null && className.endsWith(".class")
                ? className.substring(0, className.length() - 6) : className;
        if (!ITEM_MOD_BOOK.equals(normalizedName)) return classBytes;

        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        boolean changed = false;
        for (var method : classNode.methods) {
            if (!"<init>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESPECIAL
                        || !PROPERTIES.equals(call.owner)
                        || !"<init>".equals(call.name)
                        || !"()V".equals(call.desc)
                        || alreadyStamped(call)) {
                    continue;
                }
                InsnList stamp = new InsnList();
                stamp.add(new FieldInsnNode(Opcodes.GETSTATIC,
                        "net/minecraft/class_7924", "field_41197", "Lnet/minecraft/class_5321;"));
                stamp.add(new FieldInsnNode(Opcodes.GETSTATIC,
                        "vazkii/patchouli/common/item/PatchouliItems", "BOOK_ID",
                        "Lnet/minecraft/class_2960;"));
                stamp.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "net/minecraft/class_5321", "method_29179",
                        "(Lnet/minecraft/class_5321;Lnet/minecraft/class_2960;)"
                                + "Lnet/minecraft/class_5321;", false));
                stamp.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                        PROPERTIES, "method_63686",
                        "(Lnet/minecraft/class_5321;)L" + PROPERTIES + ";", false));
                method.instructions.insert(call, stamp);
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean alreadyStamped(MethodInsnNode constructor) {
        AbstractInsnNode next = constructor.getNext();
        while (next != null && (next.getType() == AbstractInsnNode.LABEL
                || next.getType() == AbstractInsnNode.LINE
                || next.getType() == AbstractInsnNode.FRAME)) {
            next = next.getNext();
        }
        return next instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC
                && "net/minecraft/class_7924".equals(field.owner)
                && "field_41197".equals(field.name);
    }
}
