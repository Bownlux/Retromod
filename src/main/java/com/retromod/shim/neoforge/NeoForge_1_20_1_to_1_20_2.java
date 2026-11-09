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
        // The same release read advancement triggers with codecs instead of a mod's JSON reader.
        com.retromod.shim.common.LegacyCriterionTriggerBridge.register(transformer);
        // Recipe serializers became codecs, and recipe lookups return holders, from 1.20.2 to 1.21.
        com.retromod.shim.common.LegacyRecipeApiBridge.register(transformer);
        // 1.20.2 removed the SimpleChannel and NetworkRegistry networking; map it onto PayloadRegistrar.
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/network/NetworkRegistry",
            "net/neoforged/neoforge/network/registration/PayloadRegistrar"
        );
    }

    @Override
    public String[] getShimClasses() {
        return new String[0];
    }
}
