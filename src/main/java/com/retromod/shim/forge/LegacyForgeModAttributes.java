/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Bridges the attributes Forge kept on {@code ForgeMod} onto where they live on NeoForge 1.21.
 *
 * <p>Forge 1.20.1 registered reach, gravity and step height as its own attributes. 1.20.5 made
 * them vanilla attributes ({@code minecraft:entity_interaction_range} and friends), and NeoForge
 * keeps swim speed and name tag distance on {@code NeoForgeMod}. A mod reads
 * {@code ForgeMod.ENTITY_REACH} as a registry object and calls {@code get()} on it, so each read
 * becomes a holder for the attribute's current id, resolved from the attribute registry when it
 * is used. Fields without a counterpart, such as the fluid types, are left alone.
 */
public final class LegacyForgeModAttributes {

    static final String HELPER = "com/retromod/generated/LegacyForgeModAttributes";
    static final String FORGE_MOD = "net/minecraftforge/common/ForgeMod";
    static final String HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    static final String REGISTRY_OBJECT = "Lnet/minecraftforge/registries/RegistryObject;";
    static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    static final String REGISTRIES = "net/minecraft/core/registries/Registries";

    /** Forge field, then the attribute id that replaced it. */
    static final String[][] ATTRIBUTES = {
            {"ENTITY_REACH", "minecraft", "entity_interaction_range"},
            {"BLOCK_REACH", "minecraft", "block_interaction_range"},
            {"ENTITY_GRAVITY", "minecraft", "gravity"},
            {"STEP_HEIGHT_ADDITION", "minecraft", "step_height"},
            {"SWIM_SPEED", "neoforge", "swim_speed"},
            {"NAMETAG_DISTANCE", "neoforge", "nametag_distance"},
    };

    private LegacyForgeModAttributes() {}

    /** Registers the reads when the host's {@code DeferredHolder} can be made from an id. */
    public static void register(RetromodTransformer transformer) {
        String id = hostIdType();
        if (id != null) registerRedirects(transformer, id);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer transformer, String id) {
        transformer.registerSyntheticClass(HELPER, generateHelper(id));
        for (String[] attribute : ATTRIBUTES) {
            transformer.registerFieldRedirect(FORGE_MOD, attribute[0], REGISTRY_OBJECT,
                    HELPER, attribute[0], "()L" + HOLDER + ";");
        }
    }

    /** The id type {@code DeferredHolder.create(ResourceKey, id)} takes on this host, or null. */
    private static String hostIdType() {
        ClassNode holder = ClassResourceInspector.read(HOLDER);
        if (holder == null) return null;
        for (MethodNode method : holder.methods) {
            Type[] params = Type.getArgumentTypes(method.desc);
            if (method.name.equals("create") && (method.access & Opcodes.ACC_STATIC) != 0 && params.length == 2
                    && params[0].getDescriptor().equals("L" + RESOURCE_KEY + ";")
                    && !params[1].getDescriptor().equals("L" + RESOURCE_KEY + ";")) {
                return params[1].getInternalName();
            }
        }
        return null;
    }

    static byte[] generateHelper(String id) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                HELPER, null, "java/lang/Object", null);
        for (String[] attribute : ATTRIBUTES) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, attribute[0],
                    "()L" + HOLDER + ";", null, null);
            mv.visitCode();
            mv.visitFieldInsn(Opcodes.GETSTATIC, REGISTRIES, "ATTRIBUTE", "L" + RESOURCE_KEY + ";");
            mv.visitLdcInsn(attribute[1]);
            mv.visitLdcInsn(attribute[2]);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, id, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + id + ";", false);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOLDER, "create",
                    "(L" + RESOURCE_KEY + ";L" + id + ";)L" + HOLDER + ";", false);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }
}
