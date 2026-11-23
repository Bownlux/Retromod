/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Gives an old mod item's {@code getBurnTime(ItemStack, RecipeType)} the fuel values form NeoForge 21.2 to 26.2 furnaces call. */
public final class LegacyBurnTimeOverrideAdapter {

    /** Post-process flag: the host's items answer burn time with the world's fuel values. */
    public static final String FLAG = "legacy-burn-time-override";

    static final String OLD = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;)I";
    static final String NEW = "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/RecipeType;"
            + "Lnet/minecraft/world/level/block/entity/FuelValues;)I";
    /** The NeoForge release that added the fuel values form. */
    static final String VERSION = "1.21.2";

    private LegacyBurnTimeOverrideAdapter() {}

    /** Post-remap pass; {@code modVersion} is null when the mod declares none. Returns the original array when nothing changes. */
    public static byte[] apply(byte[] classBytes, String modVersion) {
        if (classBytes == null) return null;
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0 || "java/lang/Object".equals(reader.getSuperName())) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // A mixin's members land on its target, and a mod built for 1.21.2 or newer writes the new form itself.
        if (OldRenderShapes.isMixin(node) || !builtBefore(modVersion)) return classBytes;
        if (!declaresConcrete(node, OLD) || declares(node, NEW)) return classBytes;
        node.methods.add(fuelValuesForm(node));
        // The new method writes its own frame, so the class's other methods keep theirs as they are.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code getBurnTime(stack, type, values)}: the old override's answer, or the inherited one when it returns a negative. */
    private static MethodNode fuelValuesForm(ClassNode node) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "getBurnTime", NEW, null, null);
        Label inherited = new Label();
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, node.name, "getBurnTime", OLD, false);
        method.visitVarInsn(Opcodes.ISTORE, 4);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitJumpInsn(Opcodes.IFLT, inherited);
        method.visitVarInsn(Opcodes.ILOAD, 4);
        method.visitInsn(Opcodes.IRETURN);
        // A negative meant "use the default" to the old API, which is now the inherited fuel values form.
        method.visitLabel(inherited);
        if ((node.version & 0xFFFF) >= Opcodes.V1_6) method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {Opcodes.INTEGER}, 0, null);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, "getBurnTime", NEW, false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        return method;
    }

    /** Whether the mod was built before 1.21.2; without a version the old form alone says it is old. */
    private static boolean builtBefore(String modVersion) {
        return modVersion == null || RetromodVersion.compareMcVersions(modVersion, VERSION) < 0;
    }

    private static boolean declares(ClassNode node, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals("getBurnTime") && m.desc.equals(desc));
    }

    private static boolean declaresConcrete(ClassNode node, String desc) {
        return node.methods.stream().anyMatch(m -> m.name.equals("getBurnTime") && m.desc.equals(desc)
                && (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) == 0);
    }
}
