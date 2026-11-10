/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;

/** Bridges the attributes Forge 1.20.1 kept on {@code ForgeMod} to where NeoForge 1.21 and Forge 26.2 keep them. */
public final class LegacyForgeModAttributes {

    static final String HELPER = "com/retromod/generated/LegacyForgeModAttributes";
    static final String FORGE_MOD = "net/minecraftforge/common/ForgeMod";
    static final String HOLDER = "net/neoforged/neoforge/registries/DeferredHolder";
    static final String REGISTRY_OBJECT = "Lnet/minecraftforge/registries/RegistryObject;";
    static final String RESOURCE_KEY = "net/minecraft/resources/ResourceKey";
    static final String REGISTRIES = "net/minecraft/core/registries/Registries";

    /**
     * Forge field, then the attribute id that replaced it. 1.20.5 made reach, gravity and step
     * height vanilla attributes, and NeoForge keeps swim speed and name tag distance on
     * {@code NeoForgeMod}. Fields without a counterpart, such as the fluid types, are left alone.
     */
    static final String[][] ATTRIBUTES = {
            {"ENTITY_REACH", "minecraft", "entity_interaction_range"},
            {"BLOCK_REACH", "minecraft", "block_interaction_range"},
            {"ENTITY_GRAVITY", "minecraft", "gravity"},
            {"STEP_HEIGHT_ADDITION", "minecraft", "step_height"},
            {"SWIM_SPEED", "neoforge", "swim_speed"},
            {"NAMETAG_DISTANCE", "neoforge", "nametag_distance"},
    };

    static final String FORGE_HELPER = "com/retromod/generated/LegacyForgeModAttributeObjects";
    static final String FORGE_REGISTRY_OBJECT = "net/minecraftforge/registries/RegistryObject";
    private static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    private static final String FORGE_REGISTRIES = "net/minecraftforge/registries/ForgeRegistries";
    private static final String FORGE_REGISTRY = "Lnet/minecraftforge/registries/IForgeRegistry;";

    private LegacyForgeModAttributes() {}

    /**
     * Forge hosts whose {@code ForgeMod} lost the reach, gravity and step fields, as Forge 26.2 did;
     * offline, a Forge target from 26.2. Each read becomes a Forge registry object for the vanilla
     * attribute, made once, so {@code get()} binds like the old field did.
     */
    public static void registerForge(RetromodTransformer transformer) {
        List<String[]> removed = new ArrayList<>();
        for (String[] attribute : ATTRIBUTES) {
            // Forge 26.2 keeps swim speed and name tag distance; only vanilla attributes are bridged.
            if (attribute[1].equals("minecraft")
                    && ForgeHostMembers.lacksField(FORGE_MOD, attribute[0], ForgeHostShape.FIRST_CHECKED_VERSION)) {
                removed.add(attribute);
            }
        }
        if (!removed.isEmpty()) registerForgeRedirects(transformer, removed);
    }

    static void registerForgeRedirects(RetromodTransformer transformer, List<String[]> attributes) {
        transformer.registerSyntheticClass(FORGE_HELPER, generateForgeHelper(attributes));
        for (String[] attribute : attributes) {
            transformer.registerFieldRedirect(FORGE_MOD, attribute[0], REGISTRY_OBJECT,
                    FORGE_HELPER, attribute[0], "()" + REGISTRY_OBJECT);
        }
    }

    /**
     * NeoForge: registers the reads when the host's {@code DeferredHolder} can be made from an id. A
     * mod calls {@code get()} on the field, so each read becomes a holder for the attribute's
     * current id, resolved from the attribute registry when it is used.
     */
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

    /**
     * One getter per field. Each caches its registry object, because every
     * {@code RegistryObject.create} adds a listener that lives as long as the game.
     */
    static byte[] generateForgeHelper(List<String[]> attributes) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                FORGE_HELPER, null, "java/lang/Object", null);
        for (String[] attribute : attributes) {
            String cache = "cached" + attribute[0];
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE, cache,
                    REGISTRY_OBJECT, null, null).visitEnd();
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, attribute[0],
                    "()" + REGISTRY_OBJECT, null, null);
            mv.visitCode();
            Label made = new Label();
            mv.visitFieldInsn(Opcodes.GETSTATIC, FORGE_HELPER, cache, REGISTRY_OBJECT);
            mv.visitInsn(Opcodes.DUP);
            mv.visitJumpInsn(Opcodes.IFNONNULL, made);
            mv.visitInsn(Opcodes.POP);
            // RegistryObject.create(Identifier.fromNamespaceAndPath(ns, path), ForgeRegistries.ATTRIBUTES)
            mv.visitLdcInsn(attribute[1]);
            mv.visitLdcInsn(attribute[2]);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, IDENTIFIER, "fromNamespaceAndPath",
                    "(Ljava/lang/String;Ljava/lang/String;)L" + IDENTIFIER + ";", false);
            mv.visitFieldInsn(Opcodes.GETSTATIC, FORGE_REGISTRIES, "ATTRIBUTES", FORGE_REGISTRY);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, FORGE_REGISTRY_OBJECT, "create",
                    "(L" + IDENTIFIER + ";" + FORGE_REGISTRY + ")" + REGISTRY_OBJECT, false);
            mv.visitInsn(Opcodes.DUP);
            mv.visitFieldInsn(Opcodes.PUTSTATIC, FORGE_HELPER, cache, REGISTRY_OBJECT);
            mv.visitLabel(made);
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The attribute's path on the host: 1.20.5 to 1.21.1 named the vanilla ones {@code generic.} or {@code player.}. */
    static String hostPath(String[] attribute, String host) {
        if (!attribute[1].equals("minecraft") || RetromodVersion.compareMcVersions(host, "1.21.2") >= 0) return attribute[2];
        return (attribute[2].endsWith("interaction_range") ? "player." : "generic.") + attribute[2];
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
            mv.visitLdcInsn(hostPath(attribute, RetromodVersion.TARGET_MC_VERSION));
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
