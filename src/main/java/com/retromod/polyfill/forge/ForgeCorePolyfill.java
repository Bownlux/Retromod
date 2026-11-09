/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.polyfill.forge;

import com.retromod.core.RetromodTransformer;
import com.retromod.polyfill.PolyfillProvider;
import com.retromod.util.McReflect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polyfill for removed Forge core APIs.
 * Covers SidedProxy, RegistryObject, MinecraftForge event bus,
 * capability system (LazyOptional, ICapabilityProvider).
 */
public class ForgeCorePolyfill implements PolyfillProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("Retromod");
    private static final String MINECRAFT_FORGE = "net/minecraftforge/common/MinecraftForge";

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
        // Each stand-in replaces a class only where the host does not have it. Forge 1.20.1 still
        // ships capabilities, and redirecting its live ICapabilityProvider made a mod's own
        // provider unassignable where AttachCapabilitiesEvent expects one: a VerifyError in the
        // mod's capability listener. MinecraftForge is the same story: on NeoForge,
        // Forge_1_20_to_NeoForge_1_21 maps it to NeoForge (which has EVENT_BUS), and on a Forge
        // host it is live, so this redirect exists only for hosts where the class is absent.
        // Probes use initialize=false (pitfall #14: never initialize an MC class).
        if (McReflect.isForgeTarget()) {
            // Every Forge host checked (1.20.1, 1.21.11, 26.2, 26.3) ships all three. Offline the
            // probe cannot see Forge at all, so a Forge target decides instead of the probe.
            LOGGER.debug("Forge target: keeping MinecraftForge, ICapabilityProvider and LazyOptional "
                    + "(the class probe found MinecraftForge: {})", hostHas(MINECRAFT_FORGE));
        } else if (!McReflect.isNeoForge()) {
            // Not NeoForge either: there ForgeNeoForgeApiBridge supplies the capability API under
            // Forge's names, and a redirect here would rename the mod's references before it applies.
            redirectWhereAbsent(transformer,
                    "net/minecraftforge/common/capabilities/ICapabilityProvider",
                    "com/retromod/shim/api/forge/embedded/CapabilityProviderShim");
            redirectWhereAbsent(transformer,
                    "net/minecraftforge/common/util/LazyOptional",
                    "com/retromod/shim/api/forge/embedded/LazyOptionalShim");
            redirectWhereAbsent(transformer, MINECRAFT_FORGE,
                    "com/retromod/shim/api/forge/embedded/ForgeCapabilitiesShim");
        }

        for (String cls : getPolyfillClasses()) {
            transformer.registerEmbeddedShim(cls);
        }
    }

    private static void redirectWhereAbsent(RetromodTransformer transformer, String original,
            String standIn) {
        if (!hostHas(original)) transformer.registerClassRedirect(original, standIn);
    }

    private static boolean hostHas(String internalName) {
        try {
            Class.forName(internalName.replace('/', '.'), false,
                    ForgeCorePolyfill.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }
}
