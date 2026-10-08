/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a read of an old bare sound event or potion constant working where the host holds a
 * registry {@code Holder} instead.
 *
 * <p>1.20.5 turned many {@code SoundEvents} constants, such as the armor equip sounds, and every
 * {@code Potions} constant into holders. A 1.20.1 mod reading {@code SoundEvents.ARMOR_EQUIP_IRON}
 * as a {@code SoundEvent}, typically inside its armor material enum, fails with
 * {@code NoSuchFieldError} while that class initializes. The read becomes a generated getter that
 * unwraps the host's holder. A mod that reads the field as a holder is left alone, because only the
 * old descriptor is redirected. The host's own field list decides which constants apply.
 */
public final class LegacyHolderConstantBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyHolderConstants";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final Map<String, String> OWNERS = Map.of(
            "net/minecraft/sounds/SoundEvents", "net/minecraft/sounds/SoundEvent",
            "net/minecraft/world/item/alchemy/Potions", "net/minecraft/world/item/alchemy/Potion");

    private LegacyHolderConstantBridge() {}

    public static void register(RetromodTransformer transformer) {
        Map<String, Map<String, String>> constants = new LinkedHashMap<>();
        for (String owner : OWNERS.keySet()) {
            constants.put(owner, holderFields(ClassResourceInspector.read(owner)));
        }
        int count = constants.values().stream().mapToInt(Map::size).sum();
        if (count == 0) return;
        registerRedirects(transformer, constants);
        LOGGER.debug("Bridged {} sound event and potion constants that became holders", count);
    }

    /** {@code constants}: owner to field name to the field's holder descriptor on the host. */
    static void registerRedirects(RetromodTransformer transformer, Map<String, Map<String, String>> constants) {
        transformer.registerSyntheticClass(HELPER, generate(constants));
        constants.forEach((owner, fields) -> {
            String valueDesc = "L" + OWNERS.get(owner) + ";";
            for (String field : fields.keySet()) {
                transformer.registerExactFieldRedirect(owner, field, valueDesc,
                        HELPER, getterName(owner, field), "()" + valueDesc);
            }
        });
    }

    /** Static fields typed {@code Holder} or {@code Holder.Reference}, with their descriptors. */
    static Map<String, String> holderFields(ClassNode owner) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (owner == null) return fields;
        for (FieldNode field : owner.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0) continue;
            if (field.desc.equals("L" + HOLDER + ";") || field.desc.equals("L" + HOLDER + "$Reference;")) {
                fields.put(field.name, field.desc);
            }
        }
        return fields;
    }

    static String getterName(String owner, String field) {
        return owner.substring(owner.lastIndexOf('/') + 1) + "$" + field;
    }

    static byte[] generate(Map<String, Map<String, String>> constants) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, HELPER, null,
                "java/lang/Object", null);
        constants.forEach((owner, fields) -> {
            String value = OWNERS.get(owner);
            fields.forEach((field, holderDesc) -> {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        getterName(owner, field), "()L" + value + ";", null, null);
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, owner, field, holderDesc);
                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, HOLDER, "value", "()Ljava/lang/Object;", true);
                mv.visitTypeInsn(Opcodes.CHECKCAST, value);
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            });
        });
        cw.visitEnd();
        return cw.toByteArray();
    }
}
