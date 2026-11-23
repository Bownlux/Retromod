/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.resources;

import net.minecraft.resources.Identifier;

/** {@code ResourceLocation.isValidResourceLocation}, which later versions dropped for {@code tryParse}. */
public final class LegacyIdentifierChecks {

    private LegacyIdentifierChecks() {}

    /** Whether {@code id} parses as a namespaced id. */
    public static boolean isValidResourceLocation(String id) {
        return Identifier.tryParse(id) != null;
    }
}
