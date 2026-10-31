/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.content.shared;

import com.retromod.loaderapi.fabric.v26.content.LegacyBrewing;
import net.fabricmc.fabric.api.event.Event;

/** The old brewing builder, Fabric API's and vanilla's, on hosts that keep brewing in data. */
public interface LegacyPotionBrewingBuilder {

    /** The old {@code BUILD} event; Retromod fires it once each server has started. */
    Event<LegacyBrewingBuildCallback> BUILD = LegacyBrewing.buildEvent();
}
