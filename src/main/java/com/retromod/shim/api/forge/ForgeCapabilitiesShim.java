/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.api.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.MinecraftVersionedApiShim;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Maps Forge's capability system onto NeoForge's rewrite: LazyOptional, ICapabilityProvider, CapabilityManager, the ForgeCapabilities fields, and the item/fluid/energy handlers. */
public class ForgeCapabilitiesShim implements MinecraftVersionedApiShim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod-ForgeCapabilitiesShim");

    @Override
    public String getShimName() {
        return "Forge Capabilities Compatibility";
    }
    
    @Override
    public String getSourceVersion() {
        return "1.20.1";
    }
    
    @Override
    public String getTargetVersion() {
        return "1.21.11";
    }
    
    @Override
    public String getModLoaderType() {
        return "forge";
    }
    
    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // Targets are NeoForge classes; skip on a non-NeoForge runtime.
        if (!McReflect.isNeoForge()) {
            LOGGER.debug("Skipping Forge → NeoForge capabilities migration (runtime is not NeoForge)");
            return;
        }

        transformer.registerClassRedirect(
            "net/minecraftforge/common/util/LazyOptional",
            "com/retromod/shim/api/forge/embedded/LazyOptionalShim"
        );

        transformer.registerMethodRedirect(
            "net/minecraftforge/common/util/LazyOptional",
            "of",
            "(Lnet/minecraftforge/common/util/NonNullSupplier;)Lnet/minecraftforge/common/util/LazyOptional;",
            "com/retromod/shim/api/forge/embedded/LazyOptionalShim",
            "of",
            "(Ljava/util/function/Supplier;)Lcom/retromod/shim/api/forge/embedded/LazyOptionalShim;"
        );

        transformer.registerMethodRedirect(
            "net/minecraftforge/common/util/LazyOptional",
            "empty",
            "()Lnet/minecraftforge/common/util/LazyOptional;",
            "com/retromod/shim/api/forge/embedded/LazyOptionalShim",
            "empty",
            "()Lcom/retromod/shim/api/forge/embedded/LazyOptionalShim;"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/common/capabilities/ICapabilityProvider",
            "com/retromod/shim/api/forge/embedded/CapabilityProviderShim"
        );

        transformer.registerMethodRedirect(
            "net/minecraftforge/common/capabilities/ICapabilityProvider",
            "getCapability",
            "(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;",
            "com/retromod/shim/api/forge/embedded/CapabilityProviderShim",
            "getCapability",
            "(Ljava/lang/Object;Ljava/lang/Object;Lnet/minecraft/core/Direction;)Lcom/retromod/shim/api/forge/embedded/LazyOptionalShim;"
        );

        // @CapabilityInject is handled in the class transform pass, not here.
        transformer.registerMethodRedirect(
            "net/minecraftforge/common/capabilities/CapabilityManager",
            "register",
            "(Ljava/lang/Class;Lnet/minecraftforge/common/capabilities/Capability$IStorage;Ljava/util/concurrent/Callable;)V",
            "com/retromod/shim/api/forge/embedded/CapabilityManagerShim",
            "register",
            "(Ljava/lang/Class;Ljava/lang/Object;Ljava/util/concurrent/Callable;)V"
        );

        transformer.registerFieldRedirect(
            "net/minecraftforge/common/capabilities/ForgeCapabilities",
            "ITEM_HANDLER",
            "Lnet/minecraftforge/common/capabilities/Capability;",
            "com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim",
            "getItemHandler",
            "()Ljava/lang/Object;"
        );

        transformer.registerFieldRedirect(
            "net/minecraftforge/common/capabilities/ForgeCapabilities",
            "FLUID_HANDLER",
            "Lnet/minecraftforge/common/capabilities/Capability;",
            "com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim",
            "getFluidHandler",
            "()Ljava/lang/Object;"
        );

        transformer.registerFieldRedirect(
            "net/minecraftforge/common/capabilities/ForgeCapabilities",
            "ENERGY",
            "Lnet/minecraftforge/common/capabilities/Capability;",
            "com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim",
            "getEnergy",
            "()Ljava/lang/Object;"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/items/IItemHandler",
            "net/neoforged/neoforge/items/IItemHandler"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/items/ItemStackHandler",
            "net/neoforged/neoforge/items/ItemStackHandler"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/items/SlotItemHandler",
            "net/neoforged/neoforge/items/SlotItemHandler"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fluids/capability/IFluidHandler",
            "net/neoforged/neoforge/fluids/capability/IFluidHandler"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fluids/FluidStack",
            "net/neoforged/neoforge/fluids/FluidStack"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fluids/capability/templates/FluidTank",
            "net/neoforged/neoforge/fluids/capability/templates/FluidTank"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/energy/IEnergyStorage",
            "net/neoforged/neoforge/energy/IEnergyStorage"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/energy/EnergyStorage",
            "net/neoforged/neoforge/energy/EnergyStorage"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/event/AttachCapabilitiesEvent",
            "com/retromod/shim/api/forge/embedded/AttachCapabilitiesEventShim"
        );

        transformer.registerMethodRedirect(
            "net/minecraftforge/event/AttachCapabilitiesEvent",
            "addCapability",
            "(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraftforge/common/capabilities/ICapabilityProvider;)V",
            "com/retromod/shim/api/forge/embedded/AttachCapabilitiesEventShim",
            "addCapability",
            "(Ljava/lang/Object;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V"
        );
    }
    
    @Override
    public String[] getShimClasses() {
        return new String[] {
            "com.retromod.shim.api.forge.embedded.LazyOptionalShim",
            "com.retromod.shim.api.forge.embedded.CapabilityProviderShim",
            "com.retromod.shim.api.forge.embedded.CapabilityManagerShim",
            "com.retromod.shim.api.forge.embedded.ForgeCapabilitiesShim",
            "com.retromod.shim.api.forge.embedded.AttachCapabilitiesEventShim"
        };
    }
}
