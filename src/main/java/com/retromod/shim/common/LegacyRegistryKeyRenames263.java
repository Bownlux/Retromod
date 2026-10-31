/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.Map;

/** Renames the registry keys 26.3 gave to other registries, in one pass, so a renamed key is never renamed again. */
public final class LegacyRegistryKeyRenames263 {

    static final String REGISTRIES = "net/minecraft/core/registries/Registries";
    static final String BUILT_IN = "net/minecraft/core/registries/BuiltInRegistries";

    // CARVER and the material ones now name the data registries, and the old type registries moved
    // to _TYPE. Done as one map: chained field redirects would send CONFIGURED_CARVER on to CARVER_TYPE.
    static final Map<String, String> REGISTRY_KEYS = Map.of(
            "CARVER", "CARVER_TYPE",
            "CONFIGURED_CARVER", "CARVER",
            "MATERIAL_CONDITION", "MATERIAL_CONDITION_TYPE",
            "MATERIAL_RULE", "MATERIAL_RULE_TYPE");
    static final Map<String, String> BUILT_IN_REGISTRIES = Map.of(
            "CARVER", "CARVER_TYPE",
            "MATERIAL_CONDITION", "MATERIAL_CONDITION_TYPE",
            "MATERIAL_RULE", "MATERIAL_RULE_TYPE",
            "BLOCKSTATE_PROVIDER_TYPE", "BLOCK_STATE_PROVIDER_TYPE");

    private static volatile Boolean hostRenamed;

    private LegacyRegistryKeyRenames263() {}

    /** The class with its old registry keys renamed, or the same array on an older host or when it names none. */
    public static byte[] apply(byte[] classBytes) {
        if (classBytes == null || !hostRenamedKeys()) return classBytes;
        return rename(classBytes);
    }

    static byte[] rename(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean changed = false;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETSTATIC) continue;
                Map<String, String> renames = field.owner.equals(REGISTRIES) ? REGISTRY_KEYS
                        : field.owner.equals(BUILT_IN) ? BUILT_IN_REGISTRIES : null;
                String renamed = renames == null ? null : renames.get(field.name);
                if (renamed == null) continue;
                field.name = renamed;
                changed = true;
            }
        }
        if (!changed) return classBytes;
        // Only names changed, so the frames stay valid.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Read from the host once: 26.3 has Registries.CARVER_TYPE. Offline, the target version decides. */
    static boolean hostRenamedKeys() {
        Boolean known = hostRenamed;
        if (known != null) return known;
        ClassNode registries = ClassResourceInspector.read(REGISTRIES);
        if (registries == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        }
        // Only an answer read from the host is kept; the version can differ between offline runs.
        boolean renamed = registries.fields.stream().map((FieldNode f) -> f.name).anyMatch("CARVER_TYPE"::equals);
        hostRenamed = renamed;
        return renamed;
    }
}
