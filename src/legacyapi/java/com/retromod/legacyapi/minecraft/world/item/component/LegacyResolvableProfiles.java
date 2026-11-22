/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.legacyapi.minecraft.world.item.component;

import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.world.item.component.ResolvableProfile;

/** The profile properties a resolvable profile held as a record component before 1.21.9. */
public final class LegacyResolvableProfiles {

    private LegacyResolvableProfiles() {}

    /** {@code profile.properties()}: the properties of the profile it holds before resolving. */
    public static PropertyMap properties(ResolvableProfile profile) {
        return profile.partialProfile().properties();
    }
}
