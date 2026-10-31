/*
 * Retromod - Backwards Compatibility Layer for Minecraft Mods
 * Copyright (c) 2026 Bownlux. Licensed under MIT License.
 */
package com.retromod.loaderapi.fabric.v26;

import com.retromod.core.RedirectKey;
import com.retromod.loaderapi.LoaderApiRegistration;

import java.util.List;

/** Registers batches C1 and C2: the old content registries, trades and brewing 26.x moved to data and item components. */
public final class ContentRows {

    private static final String CONTENT = "com/retromod/loaderapi/fabric/v26/content/";

    /** Compiled helpers that redirected calls land on. */
    public static final List<String> HELPERS = List.of(
            CONTENT + "LegacyContentRegistries",
            CONTENT + "LegacyHoeActions",
            CONTENT + "LegacyTrades",
            CONTENT + "LegacyBrewing");

    /** Shared interfaces the rename table sends the old registry types to; they stay interfaces. */
    public static final List<String> SHARED_TARGETS = List.of(
            CONTENT + "shared/LegacyCompostingChanceRegistry",
            CONTENT + "shared/LegacyFuelRegistry",
            CONTENT + "shared/LegacyPotionBrewingBuilder",
            CONTENT + "shared/LegacyBrewingBuildCallback");

    private ContentRows() {}

    /** Registers the helper owners and shared targets; the call rows come from the rename table. */
    public static void register(LoaderApiRegistration registration) {
        for (String helper : HELPERS) {
            registration.claim(RedirectKey.type(helper));
            registration.transformer().registerClassOwner(helper);
        }
        for (String target : SHARED_TARGETS) registration.claim(RedirectKey.type(target));
        // The trade factory type 26.1 removed: a generated interface each mod embeds with the old
        // getOffer, which LegacyTrades calls. FARMER and the other profession reads get the profession.
        // Claimed first: a claim clears what is registered under the type, the synthetic included.
        registration.claim(RedirectKey.type(com.retromod.shim.forge.LegacyTradeBridge.ITEM_LISTING));
        registration.claim(RedirectKey.type(com.retromod.shim.forge.LegacyTradeBridge.PROFESSIONS));
        com.retromod.shim.forge.LegacyTradeBridge.registerItemListings(registration.transformer());
    }
}
