/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26.screen.shared;

import java.util.function.Function;

/** The old Fabric API {@code LayeredDrawerWrapper} of 1.21.4 and 1.21.5, with erased ids. */
public interface LegacyLayeredDrawerWrapper {

    LegacyLayeredDrawerWrapper addLayer(LegacyIdentifiedLayer layer);

    LegacyLayeredDrawerWrapper attachLayerBefore(Object beforeThis, LegacyIdentifiedLayer layer);

    LegacyLayeredDrawerWrapper attachLayerBefore(Object beforeThis, Object id, LegacyHudLayer layer);

    LegacyLayeredDrawerWrapper attachLayerAfter(Object afterThis, LegacyIdentifiedLayer layer);

    LegacyLayeredDrawerWrapper attachLayerAfter(Object afterThis, Object id, LegacyHudLayer layer);

    LegacyLayeredDrawerWrapper removeLayer(Object id);

    LegacyLayeredDrawerWrapper replaceLayer(Object id,
            Function<LegacyIdentifiedLayer, LegacyIdentifiedLayer> replacer);
}
