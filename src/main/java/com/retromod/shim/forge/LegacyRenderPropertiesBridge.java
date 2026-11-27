/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.shim.common.LegacyWidgetInputAdapter;
import com.retromod.shim.common.OldRenderShapes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Moves Forge 1.18's client render properties onto the client extensions that replaced them in Forge 1.19. */
public final class LegacyRenderPropertiesBridge {

    /** Post-process flag for the pass that gives old implementations the new method names. */
    public static final String FLAG = "legacy-render-properties";

    static final String OLD_ITEM = "net/minecraftforge/client/IItemRenderProperties";
    static final String OLD_BLOCK = "net/minecraftforge/client/IBlockRenderProperties";
    static final String OLD_LOOKUP = "net/minecraftforge/client/RenderProperties";
    static final String ITEM_EXT = "net/minecraftforge/client/extensions/common/IClientItemExtensions";
    static final String BLOCK_EXT = "net/minecraftforge/client/extensions/common/IClientBlockExtensions";

    private static final String RENDERER = "Lnet/minecraft/client/renderer/BlockEntityWithoutLevelRenderer;";
    private static final String HUMANOID = "Lnet/minecraft/client/model/HumanoidModel;";
    private static final String STACK = "Lnet/minecraft/world/item/ItemStack;";
    private static final String FONT = "Lnet/minecraft/client/gui/Font;";
    private static final String ARMOR_ARGS = "(Lnet/minecraft/world/entity/LivingEntity;" + STACK
            + "Lnet/minecraft/world/entity/EquipmentSlot;" + HUMANOID + ")";

    /** One old method and the 1.19 method that answers for it: old name, old descriptor, new name, new descriptor. */
    record Rename(String oldName, String oldDesc, String newName, String newDesc) {}

    static final List<Rename> RENAMES = List.of(
            new Rename("getItemStackRenderer", "()" + RENDERER, "getCustomRenderer", "()" + RENDERER),
            new Rename("getArmorModel", ARMOR_ARGS + HUMANOID, "getHumanoidArmorModel", ARMOR_ARGS + HUMANOID),
            new Rename("getBaseArmorModel", ARMOR_ARGS + "Lnet/minecraft/client/model/Model;",
                    "getGenericArmorModel", ARMOR_ARGS + "Lnet/minecraft/client/model/Model;"),
            new Rename("getFont", "(" + STACK + ")" + FONT, "getFont",
                    "(" + STACK + "L" + ITEM_EXT + "$FontContext;)" + FONT));

    private LegacyRenderPropertiesBridge() {}

    /** Registers the moves for a Forge 1.18 or older mod; on NeoForge the Forge client extensions move on from there. */
    public static void register(RetromodTransformer t) {
        t.registerClassRedirect(OLD_ITEM, ITEM_EXT);
        t.registerClassRedirect(OLD_BLOCK, BLOCK_EXT);
        // DUMMY became DEFAULT; keyed on the new names too, since the class move can apply first.
        for (String[] dummy : new String[][]{{OLD_ITEM, ITEM_EXT}, {OLD_BLOCK, BLOCK_EXT}, {ITEM_EXT, ITEM_EXT}, {BLOCK_EXT, BLOCK_EXT}}) {
            t.registerFieldRedirect(dummy[0], "DUMMY", "L" + dummy[0] + ";", dummy[1], "DEFAULT", "L" + dummy[1] + ";");
        }
        // A call through the old interface reaches the method that replaced it. The block methods kept their names.
        for (Rename rename : RENAMES) {
            if (rename.oldName().equals(rename.newName())) continue;
            t.registerMethodRedirect(OLD_ITEM, rename.oldName(), rename.oldDesc(), ITEM_EXT, rename.newName(), rename.newDesc());
        }
        // RenderProperties.get(...) became the static of(...) on each extension interface.
        String lookup = "(" + STACK + ")L" + OLD_ITEM + ";";
        t.registerMethodRedirect(OLD_LOOKUP, "get", lookup, ITEM_EXT, "of", "(" + STACK + ")L" + ITEM_EXT + ";");
        String item = "(Lnet/minecraft/world/item/Item;)";
        t.registerMethodRedirect(OLD_LOOKUP, "get", item + "L" + OLD_ITEM + ";", ITEM_EXT, "of", item + "L" + ITEM_EXT + ";");
        String state = "(Lnet/minecraft/world/level/block/state/BlockState;)";
        t.registerMethodRedirect(OLD_LOOKUP, "get", state + "L" + OLD_BLOCK + ";", BLOCK_EXT, "of", state + "L" + BLOCK_EXT + ";");
        String block = "(Lnet/minecraft/world/level/block/Block;)";
        t.registerMethodRedirect(OLD_LOOKUP, "get", block + "L" + OLD_BLOCK + ";", BLOCK_EXT, "of", block + "L" + BLOCK_EXT + ";");
        t.enablePostProcess(FLAG);
    }

    /**
     * Pre-remap pass: a class that implements the old item interface, directly or through the jar's own classes, gets
     * each 1.19 method its old override answers. {@code jarClasses} reads the jar's classes as shipped.
     */
    public static byte[] apply(byte[] classBytes, Function<String, byte[]> jarClasses) {
        if (classBytes == null || !namesAnOldMethod(classBytes)) return classBytes;
        ClassReader reader = new ClassReader(classBytes);
        if ((reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) return classBytes;
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        // A mixin's methods land on its target, which is no render properties object.
        if (OldRenderShapes.isMixin(node) || !implementsOldItem(node, jarClasses)) return classBytes;
        int before = node.methods.size();
        for (Rename rename : RENAMES) {
            if (declares(node, rename.oldName(), rename.oldDesc()) && !declares(node, rename.newName(), rename.newDesc())) {
                node.methods.add(bridge(node.name, rename));
            }
        }
        if (node.methods.size() == before) return classBytes;
        // The bridges have no branches, so the class's frames stay valid as they are.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** {@code newName(newDesc)} calling the class's own old method, with the host's model where the old one gave null. */
    private static MethodNode bridge(String owner, Rename rename) {
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, rename.newName(), rename.newDesc(), null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        Type[] oldArguments = Type.getArgumentTypes(rename.oldDesc());
        for (int i = 0; i < oldArguments.length; i++) method.visitVarInsn(Opcodes.ALOAD, i + 1);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, rename.oldName(), rename.oldDesc(), false);
        // The old armor hooks answered null for the default model, which the new ones must return themselves.
        if (rename.oldDesc().startsWith(ARMOR_ARGS)) {
            method.visitVarInsn(Opcodes.ALOAD, 4);
            method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
            method.visitTypeInsn(Opcodes.CHECKCAST, Type.getReturnType(rename.newDesc()).getInternalName());
        }
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        return method;
    }

    private static boolean namesAnOldMethod(byte[] classBytes) {
        for (Rename rename : RENAMES) {
            if (LegacyWidgetInputAdapter.mentions(classBytes, rename.oldName())) return true;
        }
        return false;
    }

    /** Whether the class or a supertype the jar ships lists the old item interface. */
    private static boolean implementsOldItem(ClassNode node, Function<String, byte[]> jarClasses) {
        Deque<String> pending = new ArrayDeque<>(node.interfaces);
        if (node.superName != null) pending.add(node.superName);
        Set<String> seen = new HashSet<>();
        while (!pending.isEmpty() && seen.size() < 64) {
            String type = pending.poll();
            if (type.equals(OLD_ITEM)) return true;
            if (!seen.add(type) || jarClasses == null) continue;
            byte[] bytes = jarClasses.apply(type);
            if (bytes == null) continue;
            ClassReader parent = new ClassReader(bytes);
            pending.addAll(List.of(parent.getInterfaces()));
            if (parent.getSuperName() != null) pending.add(parent.getSuperName());
        }
        return false;
    }

    private static boolean declares(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
