/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 * 
 * Architectury API Compatibility Shim
 */
package com.retromod.shim.api.common;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.AuxiliaryVersionShim;

/**
 * Architectury API shim: its package and registration/event APIs moved around across v1-v9+.
 */
public class ArchitecturyApiShim implements AuxiliaryVersionShim {
    
    @Override
    public String getShimName() {
        return "Architectury API Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.0.0";
    }
    
    @Override
    public String getTargetVersion() {
        return "9.0.0";
    }
    
    @Override
    public String getModLoaderType() {
        return "common";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // me.shedaniel.architectury -> dev.architectury (v6+)
        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/Registries",
            "dev/architectury/registry/registries/Registries"
        );
        
        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/DeferredRegister",
            "dev/architectury/registry/registries/DeferredRegister"
        );
        
        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/RegistrySupplier",
            "dev/architectury/registry/registries/RegistrySupplier"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/platform/Platform",
            "dev/architectury/platform/Platform"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/annotations/ExpectPlatform",
            "dev/architectury/injectables/annotations/ExpectPlatform"
        );

        // Registries.get(modId) -> DeferredRegister.create(modId, registry)
        transformer.registerMethodRedirect(
            "me/shedaniel/architectury/registry/Registries",
            "get",
            "(Ljava/lang/String;)Lme/shedaniel/architectury/registry/Registries;",
            "com/retromod/shim/api/common/embedded/ArchitecturyShim",
            "getRegistries",
            "(Ljava/lang/String;)Ljava/lang/Object;"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/architectury/registry/Registries",
            "get",
            "(Lnet/minecraft/core/Registry;)Lme/shedaniel/architectury/registry/DeferredRegister;",
            "com/retromod/shim/api/common/embedded/ArchitecturyShim",
            "getDeferredRegister",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/event/events/common/LifecycleEvent",
            "dev/architectury/event/events/common/LifecycleEvent"
        );
        
        transformer.registerClassRedirect(
            "me/shedaniel/architectury/event/events/common/PlayerEvent",
            "dev/architectury/event/events/common/PlayerEvent"
        );
        
        transformer.registerClassRedirect(
            "me/shedaniel/architectury/event/events/common/TickEvent",
            "dev/architectury/event/events/common/TickEvent"
        );
        
        transformer.registerClassRedirect(
            "me/shedaniel/architectury/event/events/client/ClientLifecycleEvent",
            "dev/architectury/event/events/client/ClientLifecycleEvent"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/architectury/event/Event",
            "register",
            "(Ljava/lang/Object;)V",
            "dev/architectury/event/Event",
            "register",
            "(Ljava/lang/Object;)V"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/networking/NetworkManager",
            "dev/architectury/networking/NetworkManager"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/architectury/networking/NetworkManager",
            "registerReceiver",
            "(Lme/shedaniel/architectury/networking/NetworkManager$Side;Lnet/minecraft/resources/ResourceLocation;Lme/shedaniel/architectury/networking/NetworkManager$NetworkReceiver;)V",
            "com/retromod/shim/api/common/embedded/ArchitecturyShim",
            "registerReceiver",
            "(Ljava/lang/Object;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/MenuRegistry",
            "dev/architectury/registry/menu/MenuRegistry"
        );

        transformer.registerMethodRedirect(
            "me/shedaniel/architectury/registry/MenuRegistry",
            "register",
            "(Lnet/minecraft/resources/ResourceLocation;Lme/shedaniel/architectury/registry/MenuRegistry$SimpleMenuTypeFactory;)Lnet/minecraft/world/inventory/MenuType;",
            "com/retromod/shim/api/common/embedded/ArchitecturyShim",
            "registerMenu",
            "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)Lnet/minecraft/world/inventory/MenuType;"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/BlockEntityRegistry",
            "dev/architectury/registry/registries/DeferredRegister"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/fuel/FuelRegistry",
            "dev/architectury/registry/fuel/FuelRegistry"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/GameRulesRegistry",
            "dev/architectury/registry/level/GameRules"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/KeyMappingRegistry",
            "dev/architectury/registry/client/keymappings/KeyMappingRegistry"
        );

        transformer.registerClassRedirect(
            "me/shedaniel/architectury/registry/RenderTypeRegistry",
            "dev/architectury/registry/client/rendering/RenderTypeRegistry"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.common.embedded.ArchitecturyShim"
        };
    }
}
