/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen.shared;

import com.retromod.loaderapi.fabric.v26.screen.LegacyHudLayers;
import net.fabricmc.fabric.api.event.Event;

/** The old Fabric API {@code HudLayerRegistrationCallback} of 1.21.4 and 1.21.5. */
@FunctionalInterface
public interface LegacyHudLayerRegistrationCallback {

    /** Fires once when the client has started, with a wrapper over the host HUD registry. */
    Event<LegacyHudLayerRegistrationCallback> EVENT = LegacyHudLayers.registrationEvent();

    // Named for this type alone, so one class can implement it beside other shared types.
    void registerLegacyHudLayers(Object layeredDrawer);
}
