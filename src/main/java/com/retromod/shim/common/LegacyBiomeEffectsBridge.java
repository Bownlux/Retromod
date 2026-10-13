/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.SyntheticEmbedder;
import com.retromod.shim.common.embedded.LegacyBiomeEffects;

/**
 * Puts back the biome effects 1.21.11 moved into environment attributes.
 *
 * <p>An old mod that builds a biome in code sets its fog, sky, and water fog colors, particles,
 * ambient sounds, and music on {@code BiomeSpecialEffects.Builder}. Those setters are gone, so the
 * biome failed to build, or, once the fuzzy resolver matched {@code waterFogColor} to
 * {@code waterColor}, built with the fog color as its water color. Each removed setter now keeps
 * its value, and the biome builder that receives the effects sets it as the matching attribute.
 * The particle, mood, and additions classes moved into the attribute package with the same shape.
 */
public final class LegacyBiomeEffectsBridge {

    static final String HELPER = "com/retromod/shim/common/embedded/LegacyBiomeEffects";
    private static final String BIOME = "net/minecraft/world/level/biome/";
    private static final String ATTRIBUTE = "net/minecraft/world/attribute/";
    private static final String EFFECTS_BUILDER = BIOME + "BiomeSpecialEffects$Builder";
    private static final String L_EFFECTS_BUILDER = "L" + EFFECTS_BUILDER + ";";
    private static final String L_OBJECT = "Ljava/lang/Object;";

    private LegacyBiomeEffectsBridge() {}

    public static void register(RetromodTransformer transformer) {
        SyntheticEmbedder.registerClassResource(transformer, HELPER, LegacyBiomeEffects.class);
        transformer.registerClassRedirect(BIOME + "AmbientParticleSettings", ATTRIBUTE + "AmbientParticle");
        transformer.registerClassRedirect(BIOME + "AmbientMoodSettings", ATTRIBUTE + "AmbientMoodSettings");
        transformer.registerClassRedirect(BIOME + "AmbientAdditionsSettings", ATTRIBUTE + "AmbientAdditionsSettings");

        for (String color : new String[] {"fogColor", "waterFogColor", "skyColor"}) {
            setter(transformer, color, "I", "I");
        }
        setter(transformer, "ambientParticle", "L" + ATTRIBUTE + "AmbientParticle;", L_OBJECT);
        setter(transformer, "ambientLoopSound", "Lnet/minecraft/core/Holder;", L_OBJECT);
        setter(transformer, "ambientMoodSound", "L" + ATTRIBUTE + "AmbientMoodSettings;", L_OBJECT);
        setter(transformer, "ambientAdditionsSound", "L" + ATTRIBUTE + "AmbientAdditionsSettings;", L_OBJECT);
        setter(transformer, "backgroundMusic", "Lnet/minecraft/sounds/Music;", L_OBJECT);
        setter(transformer, "backgroundMusic", "Lnet/minecraft/util/random/WeightedList;", L_OBJECT);

        transformer.registerMethodRedirect(EFFECTS_BUILDER, "build", "()L" + BIOME + "BiomeSpecialEffects;",
                HELPER, "build", "(" + L_OBJECT + ")" + L_OBJECT, true);
        transformer.registerMethodRedirect(BIOME + "Biome$BiomeBuilder", "specialEffects",
                "(L" + BIOME + "BiomeSpecialEffects;)L" + BIOME + "Biome$BiomeBuilder;",
                HELPER, "specialEffects", "(" + L_OBJECT + L_OBJECT + ")" + L_OBJECT, true);
    }

    private static void setter(RetromodTransformer transformer, String name, String oldParam, String helperParam) {
        transformer.registerMethodRedirect(EFFECTS_BUILDER, name, "(" + oldParam + ")" + L_EFFECTS_BUILDER,
                HELPER, name, "(" + L_OBJECT + helperParam + ")" + L_OBJECT, true);
    }
}
