/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.shim.common.RebuiltApiClasses;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Answers what old code asks of Forge 1.20.1's ModelResourceLocation type, before the remap makes that type Identifier. */
public final class LegacyModelLocationAdapter {

    /** Turns the pass on, from {@link LegacyForgeBakedModelBridge}. */
    public static final String FLAG = "forge-legacy-model-location";
    public static final String OLD_TYPE = "net/minecraft/client/resources/model/ModelResourceLocation";
    static final String HELPER = RebuiltApiClasses.ROOT + "forge/client/model/LegacyModelLocations";
    private static final String MODEL_BAKERY = "net/minecraft/client/resources/model/ModelBakery";
    // The Identifier members Forge 1.20.1's location answered from its path alone; the helpers keep that answer.
    private static final Set<String> PATH_MEMBERS = Set.of("getPath", "withPrefix", "withSuffix", "withPath", "toLanguageKey",
            "toShortLanguageKey", "toDebugFileName");
    private static final Set<String> MISSING_FIELDS = Set.of("MISSING_MODEL_LOCATION", "MISSING_MODEL_VARIANT");
    private static final Set<String> ID_TYPES = Set.of("L" + OLD_TYPE + ";", "Lnet/minecraft/resources/ResourceLocation;",
            "Lnet/minecraft/resources/Identifier;");
    private static final byte[] OLD_TYPE_TEXT = OLD_TYPE.getBytes(StandardCharsets.UTF_8);
    private static final byte[] MISSING_TEXT = "MISSING_MODEL_".getBytes(StandardCharsets.UTF_8);

    private LegacyModelLocationAdapter() {}

    /** The class with its old location checks, path reads and missing-model reads sent to the rebuilt helpers. */
    public static byte[] apply(byte[] classBytes, UnaryOperator<String> methodName, UnaryOperator<String> fieldName) {
        // Forge 1.20.1 reads name the old type in their descriptor; Forge 1.21 reads name the field.
        if (classBytes == null || !contains(classBytes, OLD_TYPE_TEXT) && !contains(classBytes, MISSING_TEXT)) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                AbstractInsnNode replacement = replacement(insn, methodName, fieldName);
                if (replacement == null) continue;
                method.instructions.set(insn, replacement);
                changed = true;
            }
        }
        if (!changed) return classBytes;
        // Each replacement leaves the same type on the stack as before, so the frames and stack sizes still hold.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode replacement(AbstractInsnNode insn, UnaryOperator<String> methodName,
            UnaryOperator<String> fieldName) {
        // instanceof the old type would read instanceof Identifier, true for every id.
        if (insn instanceof TypeInsnNode type && insn.getOpcode() == Opcodes.INSTANCEOF && type.desc.equals(OLD_TYPE)) {
            return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "isLocation", "(Ljava/lang/Object;)Z", false);
        }
        if (insn instanceof MethodInsnNode call && insn.getOpcode() == Opcodes.INVOKEVIRTUAL && call.owner.equals(OLD_TYPE)) {
            String name = methodName.apply(call.name);
            if (!PATH_MEMBERS.contains(name)) return null;
            // The receiver becomes the first argument; the remap then names both as Identifier.
            String helper = name.equals("getPath") ? "path" : name;
            return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, helper, "(L" + OLD_TYPE + ";" + call.desc.substring(1), false);
        }
        if (insn instanceof FieldInsnNode field && insn.getOpcode() == Opcodes.GETSTATIC && field.owner.equals(MODEL_BAKERY)
                && MISSING_FIELDS.contains(fieldName.apply(field.name)) && ID_TYPES.contains(field.desc)) {
            // Forge 1.20.1's MISSING_MODEL_LOCATION and Forge 1.21's MISSING_MODEL_VARIANT are the located form;
            // Forge 1.21's MISSING_MODEL_LOCATION is the plain id.
            String getter = field.desc.equals("L" + OLD_TYPE + ";") ? "missingModelLocation" : "missingModelId";
            return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, getter, "()" + field.desc, false);
        }
        return null;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
