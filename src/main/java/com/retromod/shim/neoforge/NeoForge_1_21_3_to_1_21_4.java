/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.shim.neoforge;

import com.retromod.core.RetromodTransformer;
import com.retromod.core.VersionShim;
import com.retromod.shim.common.Mc1_21_3To1_21_4CoreMoves;

/** NeoForge 1.21.3 to 1.21.4 shim: GatherDataEvent split into Client/Server sub-events. */
public class NeoForge_1_21_3_to_1_21_4 implements VersionShim {
    @Override public String getShimName() { return "NeoForge 1.21.3 to 1.21.4"; }
    @Override public String getSourceVersion() { return "1.21.3"; }
    @Override public String getTargetVersion() { return "1.21.4"; }
    @Override public String getModLoaderType() { return "neoforge"; }

    @Override
    public void registerRedirects(RetromodTransformer transformer) {
        Mc1_21_3To1_21_4CoreMoves.register(transformer);
        // Old unified event maps to the Client variant.
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/data/event/GatherDataEvent",
            "net/neoforged/neoforge/data/event/GatherDataEvent$Client"
        );

        // RecipesUpdatedEvent (gone after 1.21.1) becomes RecipesReceivedEvent in 1.21.4 (#82).
        transformer.registerClassRedirect(
            "net/neoforged/neoforge/client/event/RecipesUpdatedEvent",
            "net/neoforged/neoforge/client/event/RecipesReceivedEvent"
        );

        // NeoForge 21.4 removed its model generators and ExistingFileHelper with vanilla's model datagen change.
        com.retromod.shim.common.LegacyDatagenStandIns.registerNeoForge(transformer);
    }

    @Override public String[] getShimClasses() { return new String[0]; }
}
