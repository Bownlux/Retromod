/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;

/**
 * Polyfills the pre-1.13 Forge registry APIs used by 1.12.2 and earlier mods:
 * GameRegistry, FML lifecycle events, OreDictionary (now tags), SidedProxy, and
 * FMLCommonHandler. References are redirected to embedded no-op/compat shims.
 */
public class ForgeRegistryPolyfill implements PolyfillProvider {

    @Override
    public String getName() {
        return "Forge Registry System Changes";
    }

    @Override
    public String getCategory() {
        return "forge";
    }

    @Override
    public String[] getRemovedClasses() {
        return new String[]{
            "net/minecraftforge/fml/common/registry/GameRegistry",
            "net/minecraftforge/fml/common/event/FMLPreInitializationEvent",
            "net/minecraftforge/fml/common/event/FMLInitializationEvent",
            "net/minecraftforge/fml/common/event/FMLPostInitializationEvent",
            "net/minecraftforge/fml/common/event/FMLServerStartingEvent",
            "net/minecraftforge/fml/common/SidedProxy",
            "net/minecraftforge/oredict/OreDictionary",
            "net/minecraftforge/fml/common/Loader",
            "net/minecraftforge/fml/common/FMLCommonHandler",
            "net/minecraftforge/fml/relauncher/Side",
            "net/minecraftforge/fml/relauncher/SideOnly"
        };
    }

    @Override
    public String[] getPolyfillClasses() {
        return new String[]{
            "com.retromod.polyfill.forge.embedded.GameRegistryShim",
            "com.retromod.polyfill.forge.embedded.FMLEventShim",
            "com.retromod.polyfill.forge.embedded.FMLEventShim$PreInit",
            "com.retromod.polyfill.forge.embedded.FMLEventShim$Init",
            "com.retromod.polyfill.forge.embedded.FMLEventShim$PostInit",
            "com.retromod.polyfill.forge.embedded.FMLEventShim$ServerStarting",
            "com.retromod.polyfill.forge.embedded.OreDictionaryShim",
            "com.retromod.polyfill.forge.embedded.SideShim",
            "com.retromod.polyfill.forge.embedded.FMLCommonHandlerShim"
        };
    }

    @Override
    public void registerPolyfills(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/registry/GameRegistry",
            "com/retromod/polyfill/forge/embedded/GameRegistryShim"
        );

        // On Forge and NeoForge hosts the 1.12.2 shim bridges the lifecycle events and
        // @SidedProxy itself. These inert stand-ins, registered after the shims, used to replace
        // its bridges, so @Mod.EventHandler methods and proxies never ran.
        if (!com.retromod.shim.forge.Forge1122LifecycleSynthetics.isActive()) {
            registerInertLifecycle(transformer);
        }
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLServerStartingEvent",
            "com/retromod/polyfill/forge/embedded/FMLEventShim$ServerStarting"
        );

        // OreDictionary, now tag-based
        transformer.registerClassRedirect(
            "net/minecraftforge/oredict/OreDictionary",
            "com/retromod/polyfill/forge/embedded/OreDictionaryShim"
        );

        // Loader queries to a no-op shim
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/Loader",
            "com/retromod/polyfill/forge/embedded/FMLCommonHandlerShim"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/FMLCommonHandler",
            "com/retromod/polyfill/forge/embedded/FMLCommonHandlerShim"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fml/relauncher/Side",
            "com/retromod/polyfill/forge/embedded/SideShim"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fml/relauncher/SideOnly",
            "com/retromod/polyfill/forge/embedded/SideShim"
        );

        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }
    }

    private static void registerInertLifecycle(RetromodTransformer transformer) {
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLPreInitializationEvent",
            "com/retromod/polyfill/forge/embedded/FMLEventShim$PreInit"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLInitializationEvent",
            "com/retromod/polyfill/forge/embedded/FMLEventShim$Init"
        );
        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/event/FMLPostInitializationEvent",
            "com/retromod/polyfill/forge/embedded/FMLEventShim$PostInit"
        );

        transformer.registerClassRedirect(
            "net/minecraftforge/fml/common/SidedProxy",
            "com/retromod/polyfill/forge/embedded/GameRegistryShim"
        );
    }
}
