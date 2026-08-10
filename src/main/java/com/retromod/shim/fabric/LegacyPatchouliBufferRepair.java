/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.fabric;

import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/** Disables Patchouli's obsolete direct BufferBuilder access while keeping normal rendering. */
public final class LegacyPatchouliBufferRepair {

    private static final String ACCESSOR =
            "vazkii/patchouli/mixin/client/AccessorMultiBufferSource";
    private static final String HANDLER =
            "vazkii/patchouli/client/handler/MultiblockVisualizationHandler";
    private static final String CLIENT_INITIALIZER =
            "vazkii/patchouli/fabric/client/FabricClientInitializer";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";

    private LegacyPatchouliBufferRepair() {}

    public static byte[] apply(byte[] classBytes, String className) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.6") < 0) {
            return classBytes;
        }
        String normalizedName = className != null && className.endsWith(".class")
                ? className.substring(0, className.length() - 6) : className;
        if (!ACCESSOR.equals(normalizedName) && !HANDLER.equals(normalizedName)
                && !CLIENT_INITIALIZER.equals(normalizedName)) return classBytes;

        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        boolean changed = ACCESSOR.equals(normalizedName)
                ? neutralizeAccessor(classNode)
                : HANDLER.equals(normalizedName)
                        ? bypassGhostBufferCopy(classNode)
                        : removeUnsupportedClientRegistrations(classNode);
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static boolean removeUnsupportedClientRegistrations(ClassNode classNode) {
        boolean changed = removeLegacyItemPropertyRegistration(classNode);
        changed = removeResourcePackBookListener(classNode) || changed;
        return removeLegacyModelPlugin(classNode) || changed;
    }

    private static boolean removeLegacyModelPlugin(ClassNode classNode) {
        String plugin = "net/fabricmc/fabric/api/client/model/loading/v1/ModelLoadingPlugin";
        for (var method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode register)
                        || register.getOpcode() != Opcodes.INVOKESTATIC
                        || !plugin.equals(register.owner)
                        || !"register".equals(register.name)) continue;
                AbstractInsnNode argument = register.getPrevious();
                while (argument != null && !(argument instanceof InvokeDynamicInsnNode)) {
                    argument = argument.getPrevious();
                }
                if (argument == null) continue;
                method.instructions.remove(argument);
                method.instructions.remove(register);
                return true;
            }
        }
        return false;
    }

    private static boolean removeResourcePackBookListener(ClassNode classNode) {
        String helper = "net/fabricmc/fabric/api/resource/ResourceManagerHelper";
        String listener = CLIENT_INITIALIZER + "$1";
        for (var method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode register)
                        || !helper.equals(register.owner)
                        || !"registerReloadListener".equals(register.name)) {
                    continue;
                }
                MethodInsnNode get = null;
                for (AbstractInsnNode cursor = register.getPrevious(); cursor != null;
                        cursor = cursor.getPrevious()) {
                    if (cursor instanceof MethodInsnNode candidate
                            && helper.equals(candidate.owner)
                            && "get".equals(candidate.name)) {
                        get = candidate;
                        break;
                    }
                }
                if (get == null) continue;

                boolean createsResourceBookListener = false;
                for (AbstractInsnNode cursor = get.getNext(); cursor != register;
                        cursor = cursor.getNext()) {
                    if (cursor instanceof TypeInsnNode type
                            && type.getOpcode() == Opcodes.NEW
                            && listener.equals(type.desc)) {
                        createsResourceBookListener = true;
                        break;
                    }
                }
                if (!createsResourceBookListener) continue;

                AbstractInsnNode first = get;
                for (AbstractInsnNode cursor = get.getPrevious(); cursor != null;
                        cursor = cursor.getPrevious()) {
                    if (cursor instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC) {
                        first = cursor;
                        break;
                    }
                }
                AbstractInsnNode cursor = first;
                while (cursor != null) {
                    AbstractInsnNode next = cursor.getNext();
                    method.instructions.remove(cursor);
                    if (cursor == register) break;
                    cursor = next;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean neutralizeAccessor(ClassNode classNode) {
        for (List<AnnotationNode> annotations : List.of(
                classNode.visibleAnnotations != null
                        ? classNode.visibleAnnotations : List.<AnnotationNode>of(),
                classNode.invisibleAnnotations != null
                        ? classNode.invisibleAnnotations : List.<AnnotationNode>of())) {
            for (AnnotationNode annotation : annotations) {
                if (!MIXIN_DESC.equals(annotation.desc)) continue;
                if (annotation.values == null) annotation.values = new ArrayList<>();
                for (int i = annotation.values.size() - 2; i >= 0; i -= 2) {
                    Object key = annotation.values.get(i);
                    if ("value".equals(key) || "targets".equals(key)) {
                        annotation.values.remove(i + 1);
                        annotation.values.remove(i);
                    }
                }
                annotation.values.add("targets");
                annotation.values.add(new ArrayList<>(List.of(
                        "retromod/stripped/PatchouliAccessorMultiBufferSource")));
                return true;
            }
        }
        return false;
    }

    private static boolean bypassGhostBufferCopy(ClassNode classNode) {
        for (var method : classNode.methods) {
            if (!"initBuffers".equals(method.name)
                    || !"(Lnet/minecraft/class_4597$class_4598;)"
                            .concat("Lnet/minecraft/class_4597$class_4598;").equals(method.desc)) {
                continue;
            }
            method.instructions = new InsnList();
            method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            method.tryCatchBlocks.clear();
            if (method.localVariables != null) method.localVariables.clear();
            method.maxStack = 1;
            method.maxLocals = 1;
            return true;
        }
        return false;
    }

    private static boolean removeLegacyItemPropertyRegistration(ClassNode classNode) {
        for (var method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !"net/minecraft/class_5272".equals(call.owner)
                        || !"method_27879".equals(call.name)) {
                    continue;
                }
                AbstractInsnNode first = call;
                while (first != null) {
                    if (first instanceof FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && "vazkii/patchouli/common/item/PatchouliItems".equals(field.owner)
                            && "BOOK".equals(field.name)) {
                        break;
                    }
                    first = first.getPrevious();
                }
                if (first == null) continue;
                AbstractInsnNode current = first;
                while (current != null) {
                    AbstractInsnNode next = current.getNext();
                    method.instructions.remove(current);
                    if (current == call) break;
                    current = next;
                }
                return true;
            }
        }
        return false;
    }
}
