/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Answers a biome's creature spawn probability, which 26.3 made one fixed value for every biome. */
public final class LegacyCreatureProbabilityBridge {

    static final String SETTINGS = "net/minecraft/world/level/biome/MobSpawnSettings";
    static final String BUILDER = SETTINGS + "$Builder";
    static final String NEOFORGE_BUILDER = "net/neoforged/neoforge/common/world/MobSpawnSettingsBuilder";
    static final String HELPER = "com/retromod/generated/LegacyCreatureProbability";
    private static final String L_BUILDER = "L" + BUILDER + ";";
    static final String SET_DESC = "(F)" + L_BUILDER;

    private LegacyCreatureProbabilityBridge() {}

    /** Registered for 26.3 and newer hosts, through each loader's 26.2 to 26.3 shim. */
    public static void register(RetromodTransformer t) {
        t.registerSyntheticClass(HELPER, generate());
        // Reads answer the fixed value; Citadel and Architectury read it to scale their own.
        t.registerMethodRedirect(SETTINGS, "getCreatureProbability", "()F", HELPER, "probability", "(Ljava/lang/Object;)F", true);
        t.registerMethodRedirect(NEOFORGE_BUILDER, "getProbability", "()F", HELPER, "probability", "(Ljava/lang/Object;)F", true);
        // A setter has nothing left to set, so it hands the builder back for the rest of the chain.
        for (String owner : new String[]{BUILDER, NEOFORGE_BUILDER}) {
            t.registerMethodRedirect(owner, "creatureGenerationProbability", SET_DESC, HELPER, "keep",
                    "(" + L_BUILDER + "F)" + L_BUILDER, true);
        }
    }

    static byte[] generate() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, HELPER, null, "java/lang/Object", null);
        MethodVisitor read = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "probability", "(Ljava/lang/Object;)F", null, null);
        read.visitCode();
        read.visitFieldInsn(GETSTATIC, SETTINGS, "DEFAULT_CREATURE_WORLD_GEN_SPAWN_PROBABILITY", "F");
        read.visitInsn(FRETURN);
        read.visitMaxs(0, 0);
        read.visitEnd();
        MethodVisitor keep = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "keep", "(" + L_BUILDER + "F)" + L_BUILDER, null, null);
        keep.visitCode();
        keep.visitVarInsn(ALOAD, 0);
        keep.visitInsn(ARETURN);
        keep.visitMaxs(0, 0);
        keep.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
