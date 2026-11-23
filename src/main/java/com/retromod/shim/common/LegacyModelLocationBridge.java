/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;
import com.retromod.shim.forge.LegacyForgeBakedModelBridge;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Rebuilds ModelResourceLocation and ModelManager's model lookups for NeoForge and Fabric mods on 26.x, where a location is an Identifier. */
public final class LegacyModelLocationBridge {

    static final String OLD_LOCATION = "net/minecraft/client/resources/model/ModelResourceLocation";
    static final String MODEL_MANAGER = "net/minecraft/client/resources/model/ModelManager";
    static final String MODEL_SET = "net/minecraft/client/renderer/block/BlockStateModelSet";
    static final String IDENTIFIER = "net/minecraft/resources/Identifier";
    static final String BUILT_IN_REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
    static final String LOCATIONS = RebuiltApiClasses.ROOT + "forge/client/model/LegacyModelLocations";
    static final String LOOKUPS = RebuiltApiClasses.ROOT + "forge/client/model/LegacyBlockModelLookups";

    // An old baked model reaches the transformer under its old name or, after the Fabric remap, as the block state model.
    static final List<String> MODEL_TYPES = List.of("Lnet/minecraft/client/resources/model/BakedModel;",
            "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;");
    static final String BLOCK_STATE_MODEL = "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;";

    private LegacyModelLocationBridge() {}

    /** Registers both parts when the host lacks the old location and keeps block models in a BlockStateModelSet (26.1 and newer). */
    public static void register(RetromodTransformer t) {
        boolean gameReadable = ClassResourceInspector.read(BUILT_IN_REGISTRIES) != null;
        if (!hostMatches(ClassResourceInspector.read(OLD_LOCATION), ClassResourceInspector.read(MODEL_SET), gameReadable)) return;
        registerLocations(t);
        registerLookups(t);
    }

    /** The location becomes an Identifier whose path carries the variant, with the same helpers Forge mods use. */
    static void registerLocations(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, LOCATIONS);
        // Class redirect first, so the member redirects also match once the old type is renamed.
        t.registerClassRedirect(OLD_LOCATION, IDENTIFIER);
        LegacyForgeBakedModelBridge.registerLocations(t);
        // Fabric names the record's id() and variant() by their intermediary components, which the remap leaves as they are.
        t.registerMethodRedirect(OLD_LOCATION, "comp_2875", "()L" + IDENTIFIER + ";", LOCATIONS, "id",
                "(L" + IDENTIFIER + ";)L" + IDENTIFIER + ";", true);
        t.registerMethodRedirect(OLD_LOCATION, "comp_2876", "()Ljava/lang/String;", LOCATIONS, "getVariant",
                "(L" + IDENTIFIER + ";)Ljava/lang/String;", true);
    }

    /** getModel by location or id, and getMissingModel, answered from the host's block state models. */
    static void registerLookups(RetromodTransformer t) {
        RebuiltApiClasses.registerTree(t, LOOKUPS);
        String manager = "L" + MODEL_MANAGER + ";";
        String id = "L" + IDENTIFIER + ";";
        for (String model : MODEL_TYPES) {
            for (String key : List.of("L" + OLD_LOCATION + ";", id)) {
                t.registerMethodRedirect(MODEL_MANAGER, "getModel", "(" + key + ")" + model, LOOKUPS, "getModel",
                        "(" + manager + id + ")" + BLOCK_STATE_MODEL, true);
            }
            t.registerMethodRedirect(MODEL_MANAGER, "getMissingModel", "()" + model, LOOKUPS, "getMissingModel",
                    "(" + manager + ")" + BLOCK_STATE_MODEL, true);
        }
    }

    /** Whether the host lacks the old location and its model set has missingModel(); offline, the target version decides. */
    static boolean hostMatches(ClassNode oldLocation, ClassNode modelSet, boolean gameReadable) {
        if (oldLocation != null) return false;
        if (modelSet != null) return hasMethod(modelSet, "missingModel", "()" + BLOCK_STATE_MODEL);
        return !gameReadable && RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "26.1") >= 0;
    }

    private static boolean hasMethod(ClassNode owner, String name, String desc) {
        for (MethodNode method : owner.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }
}
