/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Rebuilds the old density function API on 26.3's compiled samplers, for every loader. */
public final class Legacy263DensityFunctionBridge {

    static final String LEVELGEN = "net/minecraft/world/level/levelgen/";
    static final String OLD = LEVELGEN + "DensityFunction";
    static final String OLD_FACTORIES = LEVELGEN + "DensityFunctions";
    static final String SAMPLER = LEVELGEN + "densityfunction/DensitySampler";
    static final String CLIMATE_SAMPLER = "net/minecraft/world/level/biome/Climate$Sampler";
    static final String TYPE = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/LegacyDensityFunction";
    static final String HELPER = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/LegacyDensityFunctions";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String SIMPLE_BRIDGE = "com/retromod/generated/LegacySimpleDensityFunctionCodec";

    static final String[] NESTED = {"FunctionContext", "ContextProvider", "SimpleFunction", "SinglePointContext",
            "Visitor", "NoiseHolder"};
    static final String[] CLIMATE = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
    static final String[] OLD_CACHES = {"flatCache", "cache2d", "cacheOnce", "cacheAllInCell"};

    private Legacy263DensityFunctionBridge() {}

    /** Registers the rebuilt API when the host samples density functions through compiled samplers. */
    public static void register(RetromodTransformer t) {
        if (!hostHasSamplers(ClassResourceInspector.read(SAMPLER), ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null)) {
            return;
        }
        RebuiltApiClasses.registerTree(t, TYPE);
        RebuiltApiClasses.registerTree(t, HELPER);
        registerTypes(t);
        registerCalls(t);
        registerFactories(t);
        registerClimateGetters(t);
    }

    /** Whether 26.3's samplers replaced compute; offline, where no game class is readable, the version decides. */
    static boolean hostHasSamplers(Object sampler, boolean gameReadable) {
        if (sampler != null) return true;
        if (gameReadable) return false;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }

    /** The old nested types 26.3 deleted, now the rebuilt ones. */
    static void registerTypes(RetromodTransformer t) {
        for (String nested : NESTED) {
            t.registerClassRedirect(OLD + "$" + nested, TYPE + "$" + nested);
        }
        // An old simple function still needs the codec bridge every old density function gets.
        LegacyKeyDispatchCodecBridge.registerSubBridge(t, LegacyKeyDispatchCodecBridge.DENSITY_FUNCTION_BRIDGE,
                SIMPLE_BRIDGE, TYPE + "$SimpleFunction", OLD + "$SimpleFunction", TYPE + "$SimpleFunction");
    }

    /** Calls through the host interface reach old and host functions alike through the helper. */
    static void registerCalls(RetromodTransformer t) {
        String function = "L" + LEVELGEN + "densityfunction/DensityFunction;";
        String context = "L" + TYPE + "$FunctionContext;";
        String provider = "L" + TYPE + "$ContextProvider;";
        String visitor = "L" + TYPE + "$Visitor;";
        // Keyed by the old names; the transformer also keys each by the names the class redirects give it.
        String old = "L" + OLD + ";";
        t.registerMethodRedirect(OLD, "compute", "(L" + OLD + "$FunctionContext;)D",
                HELPER, "compute", "(" + function + context + ")D", true);
        t.registerMethodRedirect(OLD, "fillArray", "([DL" + OLD + "$ContextProvider;)V",
                HELPER, "fillArray", "(" + function + "[D" + provider + ")V", true);
        t.registerMethodRedirect(OLD, "mapAll", "(L" + OLD + "$Visitor;)" + old,
                HELPER, "mapAll", "(" + function + visitor + ")" + function, true);
        // 26.2 split mapAll into a mapChildren each function implements, and renamed reciprocal invert.
        t.registerMethodRedirect(OLD, "mapChildren", "(L" + OLD + "$Visitor;)" + old,
                HELPER, "mapChildren", "(" + function + visitor + ")" + function, true);
        t.registerMethodRedirect(OLD, "invert", "()" + old, HELPER, "invert", "(" + function + ")" + function, true);
        t.registerMethodRedirect(OLD, "minValue", "()D", HELPER, "minValue", "(" + function + ")D", true);
        t.registerMethodRedirect(OLD, "maxValue", "()D", HELPER, "maxValue", "(" + function + ")D", true);
        t.registerMethodRedirect(OLD, "clamp", "(DD)" + old, HELPER, "clamp", "(" + function + "DD)" + function, true);
    }

    /** Factories whose numbers 26.3 made floats, and the codecs that moved. */
    static void registerFactories(RetromodTransformer t) {
        String old = "L" + OLD + ";";
        String function = "L" + LEVELGEN + "densityfunction/DensityFunction;";
        // Before 26.2 the codec that reads a function or a reference to one had its own name; 26.3 calls it CODEC.
        // Under both owner names, since a read can meet this before or after the class redirect.
        for (String owner : new String[] {OLD, LEVELGEN + "densityfunction/DensityFunction"}) {
            t.registerFieldRedirect(owner, "HOLDER_HELPER_CODEC", LEVELGEN + "densityfunction/DensityFunction", "CODEC");
            t.registerFieldRedirect(owner, "DIRECT_CODEC", LEVELGEN + "densityfunction/DensityFunctions", "DIRECT_CODEC");
        }
        t.registerMethodRedirect(OLD_FACTORIES, "constant", "(D)" + old, HELPER, "constant", "(D)" + function);
        t.registerMethodRedirect(OLD_FACTORIES, "yClampedGradient", "(IIDD)" + old,
                HELPER, "yClampedGradient", "(IIDD)" + function);
        // Interpolation took its cells from the noise settings; 26.3 names them in each function.
        t.registerMethodRedirect(OLD_FACTORIES, "interpolated", "(" + old + ")" + old,
                HELPER, "interpolated", "(" + function + ")" + function);
        // 26.3 has one cache, which its compiler shares between equal inputs.
        for (String cache : OLD_CACHES) {
            t.registerMethodRedirect(OLD_FACTORIES, cache, "(" + old + ")" + old,
                    LEVELGEN + "densityfunction/DensityFunctions", "cache", "(" + function + ")" + function);
        }
        // An old caller hands the constructor an old noise; it is a 26.3 noise only when Retromod made it one.
        String holder = "(Lnet/minecraft/core/Holder;Lnet/minecraft/world/level/levelgen/synth/NormalNoise;)V";
        for (String owner : new String[] {OLD + "$NoiseHolder", TYPE + "$NoiseHolder"}) {
            t.registerConstructorRedirect(owner, holder, HELPER, "noiseHolder",
                    "(Lnet/minecraft/core/Holder;Ljava/lang/Object;)L" + TYPE + "$NoiseHolder;");
        }
    }

    /** The climate sampler's getters now return bound samplers; old callers get functions over them. */
    static void registerClimateGetters(RetromodTransformer t) {
        String function = "L" + LEVELGEN + "densityfunction/DensityFunction;";
        for (String getter : CLIMATE) {
            t.registerMethodRedirect(CLIMATE_SAMPLER, getter, "()L" + OLD + ";",
                    HELPER, getter, "(L" + CLIMATE_SAMPLER + ";)" + function, true);
        }
    }
}
