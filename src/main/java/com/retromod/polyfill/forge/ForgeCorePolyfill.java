/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;
import com.retromod.util.McReflect;

/**
 * Polyfill for removed Forge core APIs.
 * Covers SidedProxy, RegistryObject, MinecraftForge event bus,
 * capability system (LazyOptional, ICapabilityProvider).
 */
public class ForgeCorePolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Forge Core Removed APIs";
    }

    @Override
    public String getCategory() {
        return "forge";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "net/minecraftforge/fml/common/SidedProxy",
            "net/minecraftforge/registries/RegistryObject",
            "net/minecraftforge/common/MinecraftForge",
            "net/minecraftforge/common/capabilities/ICapabilityProvider",
            "net/minecraftforge/common/util/LazyOptional"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        // Stubs removed from net.minecraftforge.* packages to avoid JPMS
        // split-package conflicts. Forge classes are handled via class redirects
        // to embedded shims in com.retromod.shim.forge.embedded/
        return new String[]{
            "com.retromod.shim.forge.embedded.CapabilityShim",
            "com.retromod.shim.forge.embedded.ForgeRegistriesShim",
            "com.retromod.shim.forge.embedded.NetworkShim"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        // Register class redirects from old Forge classes to our embedded shims
        transformer.registerClassRedirect(
            "net/minecraftforge/common/capabilities/ICapabilityProvider",
            "com/retromod/shim/api/forge/embedded/CapabilityProviderShim"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/common/util/LazyOptional",
            "com/retromod/shim/api/forge/embedded/LazyOptionalShim"
        );
        // MinecraftForge (the class holding the static EVENT_BUS) maps to the embedded shim only on a
        // non-NeoForge host. On NeoForge, Forge_1_20_to_NeoForge_1_21 already maps MinecraftForge ->
        // net/neoforged/neoforge/common/NeoForge (which HAS a real IEventBus EVENT_BUS field) plus a
        // MinecraftForge.EVENT_BUS -> NeoForge.EVENT_BUS field redirect. This polyfill runs AFTER the
        // migration shim and would clobber that with a redirect to ForgeCapabilitiesShim, which has NO
        // EVENT_BUS field -> a Forge mod's `MinecraftForge.EVENT_BUS.register(this)` then dies at
        // construct with NoSuchFieldError (Macaw's on NeoForge 26.2). So skip it on NeoForge and let
        // the migration's correct mapping stand.
        // Also skip when MinecraftForge EXISTS on the host (a real Forge runtime): redirecting a
        // LIVE class to the capabilities shim breaks its real members - on Forge 26.1 the mod's
        // MinecraftForge.EVENT_BUS read died NoSuchFieldError once RetromodForge started loading
        // polyfills (review finding). This redirect exists for hosts where the class is absent
        // (Fabric). Probe with initialize=false (pitfall #14: never initialize an MC class).
        boolean minecraftForgePresent;
        try {
            Class.forName("net.minecraftforge.common.MinecraftForge", false,
                    ForgeCorePolyfill.class.getClassLoader());
            minecraftForgePresent = true;
        } catch (Throwable t) {
            minecraftForgePresent = false;
        }
        if (!McReflect.isNeoForge() && !minecraftForgePresent) {
            transformer.registerClassRedirect(
                "net/minecraftforge/common/MinecraftForge",
                "com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim"
            );
        }

        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }
    }
}
