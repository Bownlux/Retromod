/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.forge.common;

import com.retromod.shim.common.embedded.LegacyLog;
import net.minecraft.world.inventory.RecipeBookType;

/** Forge's {@code RecipeBookType.create(String)} where that call can no longer add a type: Forge 26.2 throws, NeoForge removed it. */
public final class LegacyRecipeBookTypes {

    private LegacyRecipeBookTypes() {}

    /** The smoker's book type, whose saved open and filter switches the mod's menus then share. */
    public static RecipeBookType create(String name) {
        // The smoker rarely uses its switches, and the host saves and sends this type like any vanilla one.
        LegacyLog.debug("Answered RecipeBookType.create(\"" + name + "\") with the smoker's recipe book type.");
        return RecipeBookType.SMOKER;
    }
}
