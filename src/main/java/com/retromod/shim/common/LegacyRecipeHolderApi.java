/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.common;

import com.retromod.core.ClassResourceInspector;
import com.retromod.core.RetromodTransformer;
import com.retromod.core.RetromodVersion;

/** Rebuilds the 1.20.1 {@code RecipeHolder} interface, which 1.20.2 renamed and retyped, inside each translated jar that implements it. */
public final class LegacyRecipeHolderApi {

    static final String OLD = "net/minecraft/world/inventory/RecipeHolder";
    static final String REBUILT = RebuiltApiClasses.ROOT + "minecraft/world/inventory/RecipeHolder";
    private static final String SUCCESSOR = "net/minecraft/world/inventory/RecipeCraftingHolder";

    private LegacyRecipeHolderApi() {}

    /** Whether the host renamed the interface: read from the host when it can be, else from the target. */
    static boolean hostRenamedRecipeHolder() {
        if (ClassResourceInspector.exists(SUCCESSOR)) return !ClassResourceInspector.exists(OLD);
        if (ClassResourceInspector.exists(OLD)) return false;
        return RetromodVersion.compareMcVersions(RetromodVersion.TARGET_MC_VERSION, "1.20.2") >= 0;
    }

    /**
     * Mojang-named mods only. A Fabric mod names the interface by its intermediary id, which maps
     * to the renamed {@code RecipeCraftingHolder}, but that interface's methods take recipe holders
     * now, so a Fabric block entity's old methods no longer implement them. That is left for a
     * later change.
     */
    public static void register(RetromodTransformer t) {
        if (!hostRenamedRecipeHolder()) return;
        // Farmer's Delight's cooking pot implements it, so its block entity type cannot load without it.
        RebuiltApiClasses.registerTree(t, REBUILT);
        t.registerClassRedirect(OLD, REBUILT);
    }
}
