/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

/** Keeps an older mod's block state providers and their registered types working on 26.3, which made the provider an interface. */
public final class LegacyBlockStateProviderBridge {

    static final String PROVIDERS = "net/minecraft/world/level/levelgen/feature/stateproviders/";
    static final String PROVIDER = PROVIDERS + "BlockStateProvider";
    static final String TYPE = PROVIDERS + "BlockStateProviderType";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/feature/stateproviders/";
    static final String BASE = REBUILT + "LegacyBlockStateProvider";
    static final String CALLS = REBUILT + "LegacyBlockStateProviders";
    static final String REBUILT_TYPE = REBUILT + "LegacyBlockStateProviderType";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";

    private LegacyBlockStateProviderBridge() {}

    /** Registers on a host whose block state provider is an interface; offline, the target version decides. */
    public static void register(RetromodTransformer t) {
        if (!hostMadeProviderAnInterface()) return;
        RebuiltApiClasses.registerTree(t, BASE);
        RebuiltApiClasses.registerTree(t, CALLS);
        RebuiltApiClasses.registerTree(t, REBUILT_TYPE);
        // A mod's provider extends the rebuilt class, which answers the host's getState and codec from the
        // old methods. Only a mod built before 26.3 can extend this name, since it is an interface from 26.3.
        t.registerSuperclassRebase(PROVIDER, BASE);

        // 26.3 registers the provider's codec where the type was, so the removed type becomes that codec, as a
        // class of its own that instanceof tells from other codecs.
        t.registerClassRedirect(TYPE, REBUILT_TYPE);
        t.limitClassRedirectToModsBefore(TYPE, "26.3");
        String factory = "(Ljava/lang/Object;)L" + REBUILT_TYPE + ";";
        // 1.20.5 built the type from a map codec, and older versions from a plain codec.
        t.registerConstructorRedirect(TYPE, "(L" + MAP_CODEC + ";)V", CALLS, "type", factory);
        t.registerConstructorRedirect(TYPE, "(Lcom/mojang/serialization/Codec;)V", CALLS, "type", factory);
        t.registerMethodRedirect(TYPE, "codec", "()L" + MAP_CODEC + ";", CALLS, "codec", "(Ljava/lang/Object;)L" + MAP_CODEC + ";");

        // Old calls ask a provider for a state without a level, and 26.3 renamed the simple factories.
        String pos = "Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;";
        String state = "Lnet/minecraft/world/level/block/state/BlockState;";
        t.registerMethodRedirect(PROVIDER, "getState", "(" + pos + ")" + state,
                CALLS, "getState", "(L" + PROVIDER + ";" + pos + ")" + state);
        String simple = "L" + PROVIDERS + "SimpleStateProvider;";
        t.registerMethodRedirect(PROVIDER, "simple", "(" + state + ")" + simple, PROVIDER, "of", "(" + state + ")" + simple);
        t.registerMethodRedirect(PROVIDER, "simple", "(Lnet/minecraft/world/level/block/Block;)" + simple,
                PROVIDER, "of", "(Lnet/minecraft/world/level/block/Block;)" + simple);
    }

    /** Whether the host's block state provider is an interface, as from 26.3. */
    static boolean hostMadeProviderAnInterface() {
        ClassNode provider = ClassResourceInspector.read(PROVIDER);
        if (provider != null) return (provider.access & Opcodes.ACC_INTERFACE) != 0;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }
}
