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

/** Keeps a mod's structure placements and their registered types working on 26.3, which removed the type. */
public final class LegacyStructurePlacementBridge {

    static final String PLACEMENT = "net/minecraft/world/level/levelgen/structure/placement/StructurePlacement";
    static final String TYPE = PLACEMENT + "Type";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/structure/placement/";
    static final String BASE = REBUILT + "LegacyStructurePlacement";
    static final String LEGACY_TYPE = REBUILT + "LegacyStructurePlacementType";
    static final String TYPE_CODEC = RebuiltApiClasses.ROOT + "minecraft/world/level/levelgen/LegacyTypeCodec";
    static final String MAP_CODEC = "com/mojang/serialization/MapCodec";
    static final String SUPPLIER = "java/util/function/Supplier";

    private LegacyStructurePlacementBridge() {}

    /** Registers on a host without the placement type; offline, the target version decides. */
    public static void register(RetromodTransformer t) {
        if (hostRemovedType(ClassResourceInspector.read(PLACEMENT))) registerRedirects(t);
    }

    /** Unconditional registration for transform-shape tests. */
    static void registerRedirects(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, BASE);
        RebuiltApiClasses.registerTree(t, LEGACY_TYPE);
        // A placement that extended the old abstract class moves onto a rebuilt one that keeps its fields and
        // answers the codec from the type the mod registered. Only a mod built before 26.3 extends the name.
        t.registerSuperclassRebase(PLACEMENT, BASE);

        // 26.3 registers the placement's codec where the type was. The type becomes a codec class of its own,
        // so a mod that sorts its registrations with instanceof still tells placement types apart.
        t.registerClassRedirect(TYPE, LEGACY_TYPE);
        t.limitClassRedirectToModsBefore(TYPE, "26.3");
        for (String owner : new String[] {TYPE, LEGACY_TYPE}) {
            t.registerMethodRedirect(owner, "codec", "()L" + MAP_CODEC + ";",
                    TYPE_CODEC, "codecOf", "(Ljava/lang/Object;)L" + MAP_CODEC + ";", true);
        }

        // The type was a lambda, () -> CODEC, which now builds the codec class around the mod's codec. The
        // interface is read either way, since the class redirect may already have renamed it.
        RetromodTransformer.LambdaAdapter wrap = new RetromodTransformer.LambdaAdapter(SUPPLIER, "get",
                "()Ljava/lang/Object;", LEGACY_TYPE, "wrap", "(L" + SUPPLIER + ";)L" + LEGACY_TYPE + ";");
        for (String iface : new String[] {TYPE, LEGACY_TYPE}) {
            // A 1.20.5 or newer type returned a map codec; an older one a plain codec.
            t.registerLambdaAdapter(iface, "codec", "()L" + MAP_CODEC + ";", wrap);
            t.registerLambdaAdapter(iface, "codec", "()Lcom/mojang/serialization/Codec;", wrap);
        }
    }

    /** Whether the host's placement is the 26.3 interface; {@code placement} is null offline, where 26.3 decides. */
    static boolean hostRemovedType(ClassNode placement) {
        if (placement == null) {
            return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.3") >= 0;
        }
        return (placement.access & Opcodes.ACC_INTERFACE) != 0;
    }
}
