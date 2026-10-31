/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content.shared;

/** The old Fabric API {@code BuildCallback} of the brewing builder, one type for every mod. */
@FunctionalInterface
public interface LegacyBrewingBuildCallback {

    /** Old {@code build(PotionBrewing.Builder)}. */
    void build(LegacyPotionBrewingBuilder builder);
}
