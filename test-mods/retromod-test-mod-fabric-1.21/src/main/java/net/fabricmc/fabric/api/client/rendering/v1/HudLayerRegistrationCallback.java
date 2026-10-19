/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.fabricmc.fabric.api.client.rendering.v1;

import net.fabricmc.fabric.api.event.Event;

/** Compile-only copy of the Fabric API 0.119.4 (1.21.4) signature; the jar task leaves it out. */
@FunctionalInterface
public interface HudLayerRegistrationCallback {
    Event<HudLayerRegistrationCallback> EVENT = null;

    void register(LayeredDrawerWrapper layeredDrawer);
}
