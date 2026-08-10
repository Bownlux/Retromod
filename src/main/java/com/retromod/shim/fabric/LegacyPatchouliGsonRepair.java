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
import org.objectweb.asm.tree.MethodInsnNode;

/** Removes Patchouli's registration of Minecraft's deleted private Identifier Gson adapter. */
public final class LegacyPatchouliGsonRepair {

    private static final String BOOK_REGISTRY = "vazkii/patchouli/common/book/BookRegistry";
    private static final String SERIALIZATION_UTIL =
            "vazkii/patchouli/common/util/SerializationUtil";

    private LegacyPatchouliGsonRepair() {}

    public static byte[] apply(byte[] classBytes, String className) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.21.2") < 0) {
            return classBytes;
        }
        String normalizedName = className != null && className.endsWith(".class")
                ? className.substring(0, className.length() - 6) : className;
        if (!BOOK_REGISTRY.equals(normalizedName) && !SERIALIZATION_UTIL.equals(normalizedName)) {
            return classBytes;
        }

        ClassNode classNode = new ClassNode();
        new ClassReader(classBytes).accept(classNode, 0);
        boolean changed = false;
        for (var method : classNode.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKEVIRTUAL
                        || !"com/google/gson/GsonBuilder".equals(call.owner)
                        || !"registerTypeAdapter".equals(call.name)) {
                    continue;
                }
                AbstractInsnNode adapterCtor = previousCode(call);
                AbstractInsnNode duplicate = previousCode(adapterCtor);
                AbstractInsnNode allocation = previousCode(duplicate);
                AbstractInsnNode identifierType = previousCode(allocation);
                if (!(adapterCtor instanceof MethodInsnNode constructor)
                        || constructor.getOpcode() != Opcodes.INVOKESPECIAL
                        || !"<init>".equals(constructor.name)
                        || !"net/minecraft/class_2960$class_2961".equals(constructor.owner)
                        || duplicate == null || duplicate.getOpcode() != Opcodes.DUP
                        || allocation == null || allocation.getOpcode() != Opcodes.NEW
                        || identifierType == null || identifierType.getOpcode() != Opcodes.LDC) {
                    continue;
                }
                method.instructions.remove(identifierType);
                method.instructions.remove(allocation);
                method.instructions.remove(duplicate);
                method.instructions.remove(adapterCtor);
                method.instructions.remove(call);
                changed = true;
            }
        }
        if (!changed) return classBytes;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        AbstractInsnNode previous = instruction.getPrevious();
        while (previous != null && (previous.getType() == AbstractInsnNode.LABEL
                || previous.getType() == AbstractInsnNode.LINE
                || previous.getType() == AbstractInsnNode.FRAME)) {
            previous = previous.getPrevious();
        }
        return previous;
    }
}
