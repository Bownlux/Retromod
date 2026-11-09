/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.forge.embedded.LegacyBiomeCarvers;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import static org.objectweb.asm.Opcodes.*;

/** Moves Forge's removed biome generation builder onto the vanilla builder Forge 26.2 gives biome modifiers. */
public final class LegacyBiomeGenerationBridge {

    static final String FORGE_BUILDER = "net/minecraftforge/common/world/BiomeGenerationSettingsBuilder";
    static final String PLAIN_BUILDER = "net/minecraft/world/level/biome/BiomeGenerationSettings$PlainBuilder";
    static final String CARVERS = "com/retromod/shim/forge/embedded/LegacyBiomeCarvers";
    static final String CALLS = "com/retromod/generated/LegacyBiomeCarverCalls";

    private static final String CARVING = "net/minecraft/world/level/levelgen/GenerationStep$Carving";
    private static final String L_HOLDER = "Lnet/minecraft/core/Holder;";
    private static final String L_PLAIN = "L" + PLAIN_BUILDER + ";";
    static final String GET_CARVERS = "(L" + CARVING + ";)Ljava/util/List;";
    static final String ADD_CARVER = "(L" + CARVING + ";" + L_HOLDER + ")" + L_PLAIN;

    private LegacyBiomeGenerationBridge() {}

    /** Registers where the Forge host lacks the builder; offline, a Forge target from 26.2. */
    public static void register(RetromodTransformer t) {
        if (!ForgeHostShape.lacks(FORGE_BUILDER)) return;
        registerRedirects(t);
    }

    static void registerRedirects(RetromodTransformer t) {
        // Forge 26.2 patched getFeatures onto PlainBuilder and returns it from BiomeInfo.Builder,
        // so the class move alone fixes the feature calls and the getter's descriptor.
        t.registerClassRedirect(FORGE_BUILDER, PLAIN_BUILDER);
        SyntheticEmbedder.registerClassResource(t, CARVERS, LegacyBiomeCarvers.class);
        t.registerSyntheticClass(CALLS, generateCalls());
        // The carving steps are gone, so both carver calls ignore the step; either owner may be named.
        for (String owner : new String[] {FORGE_BUILDER, PLAIN_BUILDER}) {
            t.registerMethodRedirect(owner, "getCarvers", GET_CARVERS,
                    CARVERS, "carvers", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;", true);
            t.registerMethodRedirect(owner, "addCarver", ADD_CARVER,
                    CALLS, "addCarver", "(" + L_PLAIN + "Ljava/lang/Object;" + L_HOLDER + ")" + L_PLAIN, true);
        }
    }

    /** {@code addCarver(builder, step, carver)}: the host's one-list {@code addCarver(carver)}. */
    static byte[] generateCalls() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, CALLS, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "addCarver",
                "(" + L_PLAIN + "Ljava/lang/Object;" + L_HOLDER + ")" + L_PLAIN, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitMethodInsn(INVOKEVIRTUAL, PLAIN_BUILDER, "addCarver", "(" + L_HOLDER + ")" + L_PLAIN, false);
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }
}
