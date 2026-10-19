/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;

/** Updates NeoForge 1.20.1 mods for the 1.20.2 networking and advancement changes. */
public class NeoForge_1_20_1_to_1_20_2 implements VersionShim {

    @Override public String getShimName() { return "NeoForge 1.20.1 to 1.20.2"; }
    @Override public String getSourceVersion() { return "1.20.1"; }
    @Override public String getTargetVersion() { return "1.20.2"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        // 1.20.2 split Advancement into its data and an AdvancementHolder.
        com.retromod.shim.common.LegacyAdvancementBridge.register(transformer);
        // 1.20.2 removed the SimpleChannel and NetworkRegistry networking; map it onto PayloadRegistrar.
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/network/NetworkRegistry",
            "net/neoforged/neoforge/network/registration/PayloadRegistrar"
        );
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/network/simple/SimpleChannel", "sendToServer",
            "(Ljava/lang/Object;)V",
            "com/retromod/shim/neoforge/embedded/NetworkShim", "sendToServer",
            "(Ljava/lang/Object;Ljava/lang/Object;)V"
        );
        transformer.registerMethodRedirect(
            "net/neoforged/neoforge/network/PacketDistributor", "sendToPlayer",
            "(Ljava/lang/Object;Ljava/lang/Object;)V",
            "com/retromod/shim/neoforge/embedded/NetworkShim", "sendToPlayer",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
