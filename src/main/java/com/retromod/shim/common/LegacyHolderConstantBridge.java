/*
 * Retromod: Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.FuzzyMethodResolver;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
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
 * old descriptor is redirected. The host's own field list decides which constants apply, read from
 * the indexed target jar when the host class is not on the classpath, as in the CLI.
 */
public final class LegacyHolderConstantBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");

    static final String HELPER = "com/retromod/generated/LegacyHolderConstants";
    private static final String HOLDER = "net/minecraft/core/Holder";
    private static final Map<String, String> OWNERS = Map.of(
            "net/minecraft/sounds/SoundEvents", "net/minecraft/sounds/SoundEvent",
            "net/minecraft/world/item/alchemy/Potions", "net/minecraft/world/item/alchemy/Potion",
            // GameEvent's own constants turned into holders with them; Sophisticated Core's
            // allay mixin reads JUKEBOX_PLAY's radius on every allay tick.
            "net/minecraft/world/level/gameevent/GameEvent", "net/minecraft/world/level/gameevent/GameEvent");

    /** The tool sounds 26.3 turned into holders, for an offline transform that cannot read the host. */
    static final List<String> TOOL_SOUNDS_HELD_FROM_26_3 = List.of(
            "AXE_STRIP", "AXE_SCRAPE", "AXE_WAX_OFF", "HOE_TILL", "SHOVEL_FLATTEN");

    private LegacyHolderConstantBridge() {}

    public static void register(RetromodTransformer transformer) {
        Map<String, Map<String, String>> constants = new LinkedHashMap<>();
        for (String owner : OWNERS.keySet()) {
            ClassNode host = ClassResourceInspector.read(owner);
            Map<String, String> fields = host != null ? holderFields(host)
                    : indexedHolderFields(transformer.getFuzzyResolver(), owner);
            // With neither the host class nor an indexed jar, only the 26.3 tool sounds are known.
            if (fields == null) {
                fields = new LinkedHashMap<>();
                if (owner.equals("net/minecraft/sounds/SoundEvents")
                        && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0) {
                    for (String sound : TOOL_SOUNDS_HELD_FROM_26_3) fields.put(sound, "L" + HOLDER + "$Reference;");
                }
            }
            constants.put(owner, fields);
        }
        int count = constants.values().stream().mapToInt(Map::size).sum();
        if (count == 0) return;
        registerRedirects(transformer, constants);
        LOGGER.debug("Bridged {} sound event, potion and game event constants that became holders", count);
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
            if (isHolder(field.desc)) fields.put(field.name, field.desc);
        }
        return fields;
    }

    /**
     * Holder fields of {@code owner} in the indexed target jar, which the CLI and offline AOT have
     * when the host class is not on their classpath. Null when the jar does not have the class.
     */
    static Map<String, String> indexedHolderFields(FuzzyMethodResolver index, String owner) {
        if (index == null || !index.hasClass(owner)) return null;
        Map<String, String> fields = new LinkedHashMap<>();
        // The index keeps no access flags, but every holder field on these constant classes is static.
        for (FuzzyMethodResolver.FieldInfo field : index.getDeclaredFields(owner)) {
            if (isHolder(field.descriptor())) fields.put(field.name(), field.descriptor());
        }
        return fields;
    }

    private static boolean isHolder(String descriptor) {
        return descriptor.equals("L" + HOLDER + ";") || descriptor.equals("L" + HOLDER + "$Reference;");
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
