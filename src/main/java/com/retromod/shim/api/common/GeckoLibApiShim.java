/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Bridges GeckoLib's v3 -> v4 API rewrite. Common to Fabric and Forge/NeoForge.
 */
public class GeckoLibApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "GeckoLib API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "3.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "4.4.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // geckolib3 package -> geckolib
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/IAnimatable",
            "software/bernie/geckolib/animatable/GeoAnimatable"
        );
        
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/manager/AnimationData",
            "software/bernie/geckolib/animatable/instance/AnimatableInstanceCache"
        );
        
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/manager/AnimationFactory",
            "software/bernie/geckolib/util/GeckoLibUtil"
        );
        
        // getFactory() -> getAnimatableInstanceCache()
        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/core/IAnimatable",
            "getFactory",
            "()Lsoftware/bernie/geckolib3/core/manager/AnimationFactory;",
            "software/bernie/geckolib/animatable/GeoAnimatable",
            "getAnimatableInstanceCache",
            "()Lsoftware/bernie/geckolib/animatable/instance/AnimatableInstanceCache;"
        );

        // registerControllers param changed AnimationData -> ControllerRegistrar
        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/core/IAnimatable",
            "registerControllers",
            "(Lsoftware/bernie/geckolib3/core/manager/AnimationData;)V",
            "com/retromod/shim/api/common/embedded/GeckoLibShim",
            "registerControllersCompat",
            "(Ljava/lang/Object;Ljava/lang/Object;)V"
        );
        
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/controller/AnimationController",
            "software/bernie/geckolib/animation/AnimationController"
        );

        transformer.registerConstructorRedirect(
            "software/bernie/geckolib3/core/controller/AnimationController",
            "(Lsoftware/bernie/geckolib3/core/IAnimatable;Ljava/lang/String;FLsoftware/bernie/geckolib3/core/controller/AnimationController$IAnimationPredicate;)V",
            "com/retromod/shim/api/common/embedded/GeckoLibShim",
            "createController",
            "(Ljava/lang/Object;Ljava/lang/String;FLjava/lang/Object;)Ljava/lang/Object;"
        );
        
        // AnimationBuilder -> RawAnimation
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/builder/AnimationBuilder",
            "software/bernie/geckolib/animation/RawAnimation"
        );

        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/core/builder/AnimationBuilder",
            "addAnimation",
            "(Ljava/lang/String;)Lsoftware/bernie/geckolib3/core/builder/AnimationBuilder;",
            "com/retromod/shim/api/common/embedded/GeckoLibShim",
            "addAnimation",
            "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;"
        );
        
        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/core/builder/AnimationBuilder",
            "addAnimation",
            "(Ljava/lang/String;Z)Lsoftware/bernie/geckolib3/core/builder/AnimationBuilder;",
            "com/retromod/shim/api/common/embedded/GeckoLibShim",
            "addAnimationWithLoop",
            "(Ljava/lang/Object;Ljava/lang/String;Z)Ljava/lang/Object;"
        );
        
        // renderers moved geckolib3/renderers/geo -> geckolib/renderer
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/GeoEntityRenderer",
            "software/bernie/geckolib/renderer/GeoEntityRenderer"
        );
        
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/GeoItemRenderer",
            "software/bernie/geckolib/renderer/GeoItemRenderer"
        );
        
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/GeoArmorRenderer",
            "software/bernie/geckolib/renderer/GeoArmorRenderer"
        );
        
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/renderers/geo/GeoBlockRenderer",
            "software/bernie/geckolib/renderer/GeoBlockRenderer"
        );
        
        // AnimatedGeoModel -> GeoModel; getXxxLocation accessors renamed to getXxxResource
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/model/AnimatedGeoModel",
            "software/bernie/geckolib/model/GeoModel"
        );

        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/model/AnimatedGeoModel",
            "getModelLocation",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;",
            "software/bernie/geckolib/model/GeoModel",
            "getModelResource",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;"
        );
        
        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/model/AnimatedGeoModel",
            "getTextureLocation",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;",
            "software/bernie/geckolib/model/GeoModel",
            "getTextureResource",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;"
        );
        
        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/model/AnimatedGeoModel",
            "getAnimationFileLocation",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;",
            "software/bernie/geckolib/model/GeoModel",
            "getAnimationResource",
            "(Ljava/lang/Object;)Lnet/minecraft/resources/ResourceLocation;"
        );
        
        // PlayState enum moved package
        transformer.registerClassRedirect(
            "software/bernie/geckolib3/core/PlayState",
            "software/bernie/geckolib/animation/PlayState"
        );

        // GeckoLib.initialize() is gone in v4 (auto-init); route to a stub
        transformer.registerMethodRedirect(
            "software/bernie/geckolib3/GeckoLib",
            "initialize",
            "()V",
            "com/retromod/shim/api/common/embedded/GeckoLibShim",
            "initialize",
            "()V"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.common.embedded.GeckoLibShim"
        };
    }
}
