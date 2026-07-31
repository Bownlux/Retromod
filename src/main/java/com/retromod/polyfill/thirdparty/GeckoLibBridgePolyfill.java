/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.thirdparty;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Bridge polyfill for GeckoLib 3.x -> 4.x API changes (MIT licensed).
 *
 * GeckoLib is the most popular animation library for Minecraft mods.
 * In GeckoLib 4.0, the package was renamed from {@code software.bernie.geckolib3}
 * to {@code software.bernie.geckolib}, and several core classes were renamed:
 *
 * <ul>
 *   <li>{@code AnimationBuilder} -> {@code RawAnimation}</li>
 *   <li>{@code AnimationEvent} -> {@code AnimationState}</li>
 *   <li>{@code IAnimatable} -> {@code GeoAnimatable}</li>
 *   <li>{@code AnimationData} -> {@code AnimatableManager}</li>
 *   <li>{@code AnimationFactory} -> {@code AnimatableInstanceCache}</li>
 *   <li>{@code AnimatedGeoModel} -> {@code GeoModel}</li>
 *   <li>{@code IGeoRenderer} -> {@code GeoRenderer}</li>
 *   <li>{@code ExtendedGeoEntityRenderer} -> {@code DynamicGeoEntityRenderer}</li>
 * </ul>
 *
 * This bridge does not bundle GeckoLib. The user installs GeckoLib 4.x normally;
 * Retromod only redirects old 3.x class references to their 4.x equivalents.
 */
public class GeckoLibBridgePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "GeckoLib 3.x -> 4.x Bridge";
    }

    @Override
    public String getCategory() {
        return "thirdparty";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            // Core classes renamed in GeckoLib 4.x (geckolib3 -> geckolib package)
            "software/bernie/geckolib3/core/builder/AnimationBuilder",
            "software/bernie/geckolib3/core/event/predicate/AnimationEvent",
            "software/bernie/geckolib3/core/IAnimatable",
            "software/bernie/geckolib3/core/manager/AnimationData",
            "software/bernie/geckolib3/core/manager/AnimationFactory",
            "software/bernie/geckolib3/core/controller/AnimationController",
            "software/bernie/geckolib3/core/PlayState",
            "software/bernie/geckolib3/model/AnimatedGeoModel",
            "software/bernie/geckolib3/renderers/geo/IGeoRenderer",
            "software/bernie/geckolib3/renderers/geo/ExtendedGeoEntityRenderer",
            "software/bernie/geckolib3/renderers/geo/GeoLayerRenderer"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // No embedded stubs needed: pure class redirects to GeckoLib 4.x
        return new String[]{};
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Package rename: software.bernie.geckolib3 -> software.bernie.geckolib
        // Plus class renames within the new package structure.
        // Only REMOVED/RENAMED classes are redirected here.

        // AnimationBuilder -> RawAnimation (core animation definition class)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/builder/AnimationBuilder",
            "software/bernie/geckolib/core/animation/RawAnimation");

        // AnimationEvent -> AnimationState (animation tick context)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/event/predicate/AnimationEvent",
            "software/bernie/geckolib/core/animation/AnimationState");

        // IAnimatable -> GeoAnimatable (main animatable interface)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/IAnimatable",
            "software/bernie/geckolib/animatable/GeoAnimatable");

        // AnimationData -> AnimatableManager (per-entity animation state)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/manager/AnimationData",
            "software/bernie/geckolib/core/animatable/manager/AnimatableManager");

        // AnimationFactory -> AnimatableInstanceCache (animation cache)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/manager/AnimationFactory",
            "software/bernie/geckolib/util/GeckoLibUtil");

        // AnimationController stayed but moved packages (geckolib3 -> geckolib)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/controller/AnimationController",
            "software/bernie/geckolib/core/animation/AnimationController");

        // PlayState moved packages (geckolib3 -> geckolib)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/PlayState",
            "software/bernie/geckolib/core/animation/PlayState");

        // AnimatedGeoModel -> GeoModel (model definition class)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/model/AnimatedGeoModel",
            "software/bernie/geckolib/model/GeoModel");

        // IGeoRenderer -> GeoRenderer (renderer interface)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/IGeoRenderer",
            "software/bernie/geckolib/renderer/GeoRenderer");

        // ExtendedGeoEntityRenderer -> DynamicGeoEntityRenderer
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/ExtendedGeoEntityRenderer",
            "software/bernie/geckolib/renderer/DynamicGeoEntityRenderer");

        // GeoLayerRenderer -> GeoRenderLayer (render layer system)
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/GeoLayerRenderer",
            "software/bernie/geckolib/renderer/layer/GeoRenderLayer");
    }
}
