package net.fabricmc.fabric.api.client.rendering.v1;

import net.minecraft.class_2960;
import net.minecraft.class_9080;

import java.util.function.Function;

/** Compile-only copy of the Fabric API 0.119.4 (1.21.4) signature. */
public interface LayeredDrawerWrapper {
    LayeredDrawerWrapper addLayer(IdentifiedLayer layer);

    LayeredDrawerWrapper attachLayerBefore(class_2960 beforeThis, IdentifiedLayer layer);

    default LayeredDrawerWrapper attachLayerBefore(class_2960 beforeThis, class_2960 identifier, class_9080.class_9081 layer) {
        throw new AssertionError("compile-only");
    }

    LayeredDrawerWrapper attachLayerAfter(class_2960 afterThis, IdentifiedLayer layer);

    default LayeredDrawerWrapper attachLayerAfter(class_2960 afterThis, class_2960 identifier, class_9080.class_9081 layer) {
        throw new AssertionError("compile-only");
    }

    LayeredDrawerWrapper removeLayer(class_2960 identifier);

    LayeredDrawerWrapper replaceLayer(class_2960 identifier, Function<IdentifiedLayer, IdentifiedLayer> replacer);
}
