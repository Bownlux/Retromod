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

        // LazyOptional, ICapabilityProvider, CapabilityManager, ForgeCapabilities and
        // AttachCapabilitiesEvent are supplied by LegacyCapabilitySynthetics on NeoForge.

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
