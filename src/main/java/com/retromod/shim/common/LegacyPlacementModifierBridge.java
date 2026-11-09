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

/** Keeps an older mod's placement modifiers and their registered types working on 26.3, which made the modifier an interface. */
public final class LegacyPlacementModifierBridge {

    static final String PLACEMENT = "net/minecraft/world/level/levelgen/placement/";
    static final String MODIFIER = PLACEMENT + "PlacementModifier";
    static final String FILTER = PLACEMENT + "PlacementFilter";
    static final String REPEATING = PLACEMENT + "RepeatingPlacement";
    static final String TYPE = PLACEMENT + "PlacementModifierType";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/placement/";
    static final String BASE = REBUILT + "LegacyPlacementModifier";
    static final String FILTER_BASE = REBUILT + "LegacyPlacementFilter";
    static final String REPEATING_BASE = REBUILT + "LegacyRepeatingPlacement";
    static final String TYPES = REBUILT + "LegacyPlacementModifierTypes";
    static final String REBUILT_TYPE = REBUILT + "LegacyPlacementModifierType";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String SUPPLIER = "java/util/function/Supplier";

    private LegacyPlacementModifierBridge() {}

    /** Registers on a host whose placement modifier is an interface; offline, the target version decides. */
    public static void register(RetromodTransformer t) {
        if (!hostMadeModifierAnInterface()) return;
        for (String root : new String[] {BASE, FILTER_BASE, REPEATING_BASE, TYPES, REBUILT_TYPE}) {
            RebuiltApiClasses.registerTree(t, root);
        }
        // A mod's modifier, filter, or repeating placement extends the rebuilt class that still has the old
        // abstract methods, which answers the host's modify and codec from them. Only a mod built before
        // 26.3 can extend these names, since they are interfaces from 26.3.
        t.registerSuperclassRebase(MODIFIER, BASE);
        t.registerSuperclassRebase(FILTER, FILTER_BASE);
        t.registerSuperclassRebase(REPEATING, REPEATING_BASE);

        // 26.3 registers the modifier's codec where the type was, so the removed type becomes that codec. It is a
        // class of its own, so a mod that sorts registrations with instanceof tells it from other codecs.
        t.registerClassRedirect(TYPE, REBUILT_TYPE);
        t.limitClassRedirectToModsBefore(TYPE, "26.3");
        t.registerMethodRedirect(TYPE, "codec", "()L" + MAP_CODEC + ";",
                TYPES, "codec", "(Ljava/lang/Object;)L" + MAP_CODEC + ";");

        // The type was a lambda, () -> CODEC, which now builds the registered codec from the mod's. The
        // interface is read either way, since the class redirect may already have renamed it.
        RetromodTransformer.LambdaAdapter wrap = new RetromodTransformer.LambdaAdapter(SUPPLIER, "get",
                "()Ljava/lang/Object;", TYPES, "wrap", "(L" + SUPPLIER + ";)L" + REBUILT_TYPE + ";");
        for (String iface : new String[] {TYPE, REBUILT_TYPE}) {
            // A 1.20.5 or newer type returned a map codec; an older one a plain codec.
            t.registerLambdaAdapter(iface, "codec", "()L" + MAP_CODEC + ";", wrap);
            t.registerLambdaAdapter(iface, "codec", "()Lcom/mojang/serialization/Codec;", wrap);
        }
    }

    /** Whether the host's placement modifier is an interface, as from 26.3. */
    static boolean hostMadeModifierAnInterface() {
        ClassNode modifier = ClassResourceInspector.read(MODIFIER);
        if (modifier != null) return (modifier.access & Opcodes.ACC_INTERFACE) != 0;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
    }
}
