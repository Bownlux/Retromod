/*
 * Retromod Test Mod
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.client.gui.LayeredDrawer;
import net.minecraft.util.Identifier;

import java.util.function.Function;

/** Compile-only copy of the Fabric API 0.119.4 (1.21.4) signatures; the jar task leaves it out. */
public interface LayeredDrawerWrapper {
    LayeredDrawerWrapper addLayer(IdentifiedLayer layer);

    LayeredDrawerWrapper attachLayerBefore(Identifier beforeThis, IdentifiedLayer layer);

    default LayeredDrawerWrapper attachLayerBefore(Identifier beforeThis, Identifier identifier, LayeredDrawer.Layer layer) {
        throw new AssertionError("compile-only");
    }

    LayeredDrawerWrapper attachLayerAfter(Identifier afterThis, IdentifiedLayer layer);

    default LayeredDrawerWrapper attachLayerAfter(Identifier afterThis, Identifier identifier, LayeredDrawer.Layer layer) {
        throw new AssertionError("compile-only");
    }

    LayeredDrawerWrapper removeLayer(Identifier identifier);

    LayeredDrawerWrapper replaceLayer(Identifier identifier, Function<IdentifiedLayer, IdentifiedLayer> replacer);
}
