/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

/**
 * Old reads of {@code BlockStateProvider.CODEC} and {@code Recipe.CODEC} take {@code DIRECT_CODEC}, since 26.3 made
 * CODEC decode a holder; Moonlight reads a recipe with it to build Supplementaries' sign post recipes.
 */
public final class LegacyStateProviderCodecAdapter {

    public static final String FLAG = "legacy-state-provider-codec";

    static final String PROVIDER = "net/minecraft/world/level/levelgen/feature/stateproviders/BlockStateProvider";
    static final String RECIPE = "net/minecraft/world/item/crafting/Recipe";
    static final String FIRST_VERSION_WITH_HOLDER = "26.3";
    private static final byte[] MARKER = "stateproviders/BlockStateProvider".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] RECIPE_MARKER = RECIPE.getBytes(StandardCharsets.ISO_8859_1);

    private LegacyStateProviderCodecAdapter() {}

    /** On hosts from 26.3, where CODEC reads a holder and DIRECT_CODEC keeps the old provider codec. */
    public static void register(RetromodTransformer t) {
        if (RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, FIRST_VERSION_WITH_HOLDER) >= 0) {
            t.enablePostProcess(FLAG);
        }
    }

    /** Rewrites the reads in a class of a mod built before 26.3. */
    public static byte[] apply(byte[] classBytes, String modVersion) {
        if (classBytes == null || !StaticFieldIdStamper.containsBytes(classBytes, MARKER)
                && !StaticFieldIdStamper.containsBytes(classBytes, RECIPE_MARKER)) {
            return classBytes;
        }
        // A mod of unknown version may be written for 26.3, where CODEC is the holder codec it means.
        if (modVersion == null || RetromodVersion.compareMcVersions(modVersion, FIRST_VERSION_WITH_HOLDER) >= 0) {
            return classBytes;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        int changed = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && (field.owner.equals(PROVIDER) || field.owner.equals(RECIPE)) && field.name.equals("CODEC")) {
                    field.name = "DIRECT_CODEC";
                    changed++;
                }
            }
        }
        if (changed == 0) return classBytes;
        // Only a field name changed, so the class keeps its stack map frames.
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }
}
